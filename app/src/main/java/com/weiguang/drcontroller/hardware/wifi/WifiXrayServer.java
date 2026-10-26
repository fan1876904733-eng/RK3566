package com.weiguang.drcontroller.hardware.wifi;

import android.os.Handler;
import android.os.Looper;

import com.weiguang.drcontroller.BuildConfig;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * X-Ray WiFi 客户端（RK3566 主动连接迷你主机）。
 *
 * <p>RK3566 作为客户端主动连接迷你主机的 TCP 服务，保持长连接，
 * 断线自动重连。v2 帧协议（大端序）：</p>
 *
 * <ol>
 *   <li>4字节魔数 "XRY2"/"RPT2" + 8字节时间戳(ms) + 16字节随机 nonce</li>
 *   <li>4字节载荷长度 + 载荷 + 32字节 HMAC-SHA256</li>
 *   <li>HMAC 覆盖：magic + timestamp + nonce + length + payload。旧 XRY1/RPT1 帧不再接受。</li>
 * </ol>
 */
public final class WifiXrayServer {

    /** 默认端口。 */
    public static final int DEFAULT_PORT = 50001;

    /** 迷你主机 IP（固定）。 */
    public static final String DEFAULT_MINI_HOST = "192.168.43.42";

    private static final int MAGIC_XRY2 = 0x58525932;
    private static final int MAGIC_RPT2 = 0x52505432;
    private static final int NONCE_BYTES = 16;
    private static final int MAC_BYTES = 32;
    private static final long MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L;
    private static final int RECENT_NONCE_LIMIT = 256;
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_REPORT_BYTES = 256 * 1024;

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    public interface Listener {
        void onConnectionStateChanged(ConnectionState state, String deviceName, String message);
        void onImageReceived(byte[] imageBytes, long captureTimeMillis);
        void onReportReceived(String reportText, long captureTimeMillis);
        void onError(String message);
    }

    private static volatile WifiXrayServer instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> recentNonces = new LinkedHashSet<>();

    private volatile Socket activeSocket;
    private String miniHostIp = DEFAULT_MINI_HOST;
    private int port = DEFAULT_PORT;

    private WifiXrayServer() {}

    public static WifiXrayServer getInstance() {
        if (instance == null) {
            synchronized (WifiXrayServer.class) {
                if (instance == null) instance = new WifiXrayServer();
            }
        }
        return instance;
    }

    public void addListener(Listener l) { if (l != null) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** 启动客户端，自动连接迷你主机并保持长连接。 */
    public void start(String miniHostIp, int port) {
        if (running.getAndSet(true)) return;
        this.miniHostIp = miniHostIp;
        this.port = port;
        ioExecutor.execute(this::connectLoop);
    }

    public void stop() {
        running.set(false);
        Socket s = activeSocket;
        activeSocket = null;
        if (s != null) { try { s.close(); } catch (IOException ignored) {} }
        notifyState(ConnectionState.DISCONNECTED, "--", "WiFi连接已停止");
    }

    public boolean isRunning() { return running.get(); }

    private void connectLoop() {
        while (running.get()) {
            notifyState(ConnectionState.CONNECTING, miniHostIp, "正在连接迷你主机 " + miniHostIp + ":" + port);
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(miniHostIp, port), 5000);
                activeSocket = socket;
                notifyState(ConnectionState.CONNECTED, "迷你主机(" + miniHostIp + ")", "已连接迷你主机，等待X-Ray图像");
                readLoop(socket);
            } catch (IOException e) {
                notifyState(ConnectionState.DISCONNECTED, miniHostIp, "连接失败，5秒后重试：" + safeMessage(e));
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                activeSocket = null;
            }
            if (!running.get()) break;
            try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
        }
    }

    private void readLoop(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        while (running.get() && !socket.isClosed()) {
            int firstValue;
            try {
                firstValue = input.readInt();
            } catch (EOFException e) {
                return;
            }
            if (firstValue != MAGIC_XRY2 && firstValue != MAGIC_RPT2) {
                notifyError("拒绝未鉴权或未知的 WiFi X-Ray 帧");
                return;
            }
            long ts = input.readLong();
            byte[] nonce = new byte[NONCE_BYTES];
            input.readFully(nonce);
            int len = input.readInt();
            int maxBytes = firstValue == MAGIC_XRY2 ? MAX_IMAGE_BYTES : MAX_REPORT_BYTES;
            if (len <= 0 || len > maxBytes) {
                notifyError("无效载荷长度：" + len);
                return;
            }
            byte[] payload = new byte[len];
            input.readFully(payload);
            byte[] receivedMac = new byte[MAC_BYTES];
            input.readFully(receivedMac);
            if (!verifyFrame(firstValue, ts, nonce, len, payload, receivedMac)) {
                notifyError("WiFi X-Ray 帧鉴权失败");
                return;
            }
            if (firstValue == MAGIC_RPT2) {
                notifyReportReceived(new String(payload, StandardCharsets.UTF_8), ts);
            } else {
                notifyImageReceived(payload, ts);
            }
        }
    }

    private boolean verifyFrame(
            int magic,
            long timestamp,
            byte[] nonce,
            int length,
            byte[] payload,
            byte[] receivedMac
    ) {
        if (Math.abs(System.currentTimeMillis() - timestamp) > MAX_CLOCK_SKEW_MS) return false;
        String nonceKey = toHex(nonce);
        synchronized (recentNonces) {
            if (recentNonces.contains(nonceKey)) return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    BuildConfig.WIFI_XRAY_SHARED_KEY.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            mac.update(ByteBuffer.allocate(4).putInt(magic).array());
            mac.update(ByteBuffer.allocate(8).putLong(timestamp).array());
            mac.update(nonce);
            mac.update(ByteBuffer.allocate(4).putInt(length).array());
            mac.update(payload);
            if (!MessageDigest.isEqual(mac.doFinal(), receivedMac)) return false;
            synchronized (recentNonces) {
                recentNonces.add(nonceKey);
                if (recentNonces.size() > RECENT_NONCE_LIMIT) {
                    String oldest = recentNonces.iterator().next();
                    recentNonces.remove(oldest);
                }
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private void notifyState(ConnectionState s, String name, String msg) {
        mainHandler.post(() -> { for (Listener l : listeners) l.onConnectionStateChanged(s, name, msg); });
    }
    private void notifyImageReceived(byte[] img, long ts) {
        byte[] copy = new byte[img.length];
        System.arraycopy(img, 0, copy, 0, img.length);
        mainHandler.post(() -> { for (Listener l : listeners) l.onImageReceived(copy, ts); });
    }
    private void notifyReportReceived(String r, long ts) {
        mainHandler.post(() -> { for (Listener l : listeners) l.onReportReceived(r, ts); });
    }
    private void notifyError(String msg) {
        mainHandler.post(() -> { for (Listener l : listeners) l.onError(msg); });
    }
    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
