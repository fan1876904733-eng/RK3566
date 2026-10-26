package com.weiguang.drcontroller.hardware.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * X-Ray蓝牙影像会话管理器。
 *
 * 连接方式：使用经典蓝牙RFCOMM/SPP连接已在Android系统中完成配对的主机。
 * 默认数据帧支持两种格式（均为大端序）：
 *
 * 1. 推荐格式：
 *    4字节魔数 "XRY1" + 8字节拍摄时间戳 + 4字节图像长度 + JPEG/PNG字节
 * 2. 兼容格式：
 *    4字节图像长度 + JPEG/PNG字节
 *
 * 主机软件协议不一致时，只需修改readLoop()，Fragment和界面无需重做。
 */
public final class BluetoothXrayManager {

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    public interface Listener {
        void onConnectionStateChanged(
                ConnectionState state,
                String deviceName,
                String message
        );

        void onImageReceived(byte[] imageBytes, long captureTimeMillis);

        void onError(String message);
    }

    public static final class SessionSnapshot {
        private final ConnectionState state;
        private final String deviceName;
        private final byte[] imageBytes;
        private final long captureTimeMillis;

        private SessionSnapshot(
                ConnectionState state,
                String deviceName,
                byte[] imageBytes,
                long captureTimeMillis
        ) {
            this.state = state;
            this.deviceName = deviceName;
            this.imageBytes = imageBytes;
            this.captureTimeMillis = captureTimeMillis;
        }

        public ConnectionState getState() {
            return state;
        }

        public String getDeviceName() {
            return deviceName;
        }

        public byte[] getImageBytes() {
            return imageBytes == null
                    ? null
                    : Arrays.copyOf(imageBytes, imageBytes.length);
        }

        public long getCaptureTimeMillis() {
            return captureTimeMillis;
        }
    }

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int MAGIC_XRY1 = 0x58525931;
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;

    private static volatile BluetoothXrayManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object socketLock = new Object();
    private final AtomicInteger connectionGeneration = new AtomicInteger(0);

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String deviceName = "--";
    private volatile byte[] lastImageBytes;
    private volatile long lastCaptureTimeMillis;
    private BluetoothSocket socket;

    private BluetoothXrayManager(Context context) {
        appContext = context.getApplicationContext();
    }

    public static BluetoothXrayManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BluetoothXrayManager.class) {
                if (instance == null) {
                    instance = new BluetoothXrayManager(context);
                }
            }
        }
        return instance;
    }

    public BluetoothAdapter getAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public SessionSnapshot getSnapshot() {
        return new SessionSnapshot(
                state,
                deviceName,
                lastImageBytes,
                lastCaptureTimeMillis
        );
    }

    public boolean isConnected() {
        return state == ConnectionState.CONNECTED;
    }

    public boolean isConnecting() {
        return state == ConnectionState.CONNECTING;
    }

    public void connect(BluetoothDevice device) {
        if (device == null || isConnected() || isConnecting()) {
            return;
        }

        final String selectedName = safeDeviceName(device);
        final int generation = connectionGeneration.incrementAndGet();
        setState(
                ConnectionState.CONNECTING,
                selectedName,
                "正在连接蓝牙主机..."
        );

        ioExecutor.execute(() -> {
            closeSocketQuietly();

            try {
                BluetoothSocket newSocket =
                        device.createRfcommSocketToServiceRecord(SPP_UUID);

                synchronized (socketLock) {
                    socket = newSocket;
                }

                newSocket.connect();

                if (generation != connectionGeneration.get()) {
                    newSocket.close();
                    return;
                }

                setState(
                        ConnectionState.CONNECTED,
                        selectedName,
                        "X-Ray主机已连接，等待拍照"
                );
                readLoop(newSocket, generation);

            } catch (SecurityException exception) {
                if (generation == connectionGeneration.get()) {
                    failConnection("缺少蓝牙连接权限");
                }
            } catch (IOException exception) {
                if (generation == connectionGeneration.get()) {
                    failConnection(
                            "蓝牙连接或传输失败：" + safeMessage(exception)
                    );
                }
            } finally {
                closeSocketQuietly();
                if (generation == connectionGeneration.get()
                        && state != ConnectionState.DISCONNECTED) {
                    setState(
                            ConnectionState.DISCONNECTED,
                            selectedName,
                            "蓝牙主机已断开"
                    );
                }
            }
        });
    }

    public void disconnect() {
        connectionGeneration.incrementAndGet();
        closeSocketQuietly();
        setState(
                ConnectionState.DISCONNECTED,
                deviceName,
                "蓝牙主机已断开"
        );
    }

    public void clearLastImage() {
        lastImageBytes = null;
        lastCaptureTimeMillis = 0L;
    }

    private void readLoop(BluetoothSocket activeSocket, int generation) throws IOException {
        DataInputStream input = new DataInputStream(
                new BufferedInputStream(activeSocket.getInputStream())
        );

        while (state == ConnectionState.CONNECTED
                && generation == connectionGeneration.get()) {
            int firstValue;
            try {
                firstValue = input.readInt();
            } catch (EOFException exception) {
                return;
            }

            long captureTimeMillis;
            int imageLength;

            if (firstValue == MAGIC_XRY1) {
                captureTimeMillis = input.readLong();
                imageLength = input.readInt();
            } else {
                captureTimeMillis = System.currentTimeMillis();
                imageLength = firstValue;
            }

            if (imageLength <= 0 || imageLength > MAX_IMAGE_BYTES) {
                throw new IOException("收到无效图像长度：" + imageLength);
            }

            byte[] imageBytes = new byte[imageLength];
            input.readFully(imageBytes);

            lastImageBytes = Arrays.copyOf(imageBytes, imageBytes.length);
            lastCaptureTimeMillis = captureTimeMillis;
            notifyImageReceived(imageBytes, captureTimeMillis);
        }
    }

    private void failConnection(String message) {
        setState(
                ConnectionState.DISCONNECTED,
                deviceName,
                message
        );
        notifyError(message);
    }

    private void setState(
            ConnectionState newState,
            String newDeviceName,
            String message
    ) {
        state = newState;
        deviceName = newDeviceName == null || newDeviceName.trim().isEmpty()
                ? "--"
                : newDeviceName.trim();

        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onConnectionStateChanged(
                        newState,
                        deviceName,
                        message
                );
            }
        });
    }

    private void notifyImageReceived(
            byte[] imageBytes,
            long captureTimeMillis
    ) {
        byte[] callbackBytes = Arrays.copyOf(imageBytes, imageBytes.length);
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onImageReceived(callbackBytes, captureTimeMillis);
            }
        });
    }

    private void notifyError(String message) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onError(message);
            }
        });
    }

    private void closeSocketQuietly() {
        BluetoothSocket socketToClose;
        synchronized (socketLock) {
            socketToClose = socket;
            socket = null;
        }

        if (socketToClose != null) {
            try {
                socketToClose.close();
            } catch (IOException ignored) {
                // 关闭阶段无需再次提示。
            }
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
            return device.getAddress();
        } catch (SecurityException exception) {
            return "蓝牙主机";
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
