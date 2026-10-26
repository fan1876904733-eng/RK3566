package com.weiguang.drcontroller.hardware.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.weiguang.drcontroller.hardware.radar.HlkLd6002Protocol;
import com.weiguang.drcontroller.hardware.radar.RadarReading;

import java.util.Locale;

/**
 * HLK-LD6002 + CP2104 实际串口数据适配器。
 *
 * V3.6 的“连接”定义被拆成三层：
 * 1) USB/CP2104 已打开；
 * 2) RX 真正收到串口字节；
 * 3) 收到校验正确的 HLK TinyFrame 业务帧。
 * 只有 2/3 发生后，页面才会显示真实数据状态，避免“USB连接了但其实没数据”的假象。
 */
public final class HlkLd6002RadarAdapter implements UsbRadarAdapter {

    private static final String TAG = "WG-RADAR";

    public static final int CP210X_VENDOR_ID = 0x10C4;
    public static final int CP210X_PRODUCT_ID = 0xEA60;
    public static final int BAUD_RATE = 115_200;

    private static final int REQ_TYPE_HOST_TO_INTERFACE = 0x41;
    private static final int CP210X_IFC_ENABLE = 0x00;
    private static final int CP210X_SET_LINE_CTL = 0x03;
    private static final int CP210X_SET_MHS = 0x07;
    private static final int CP210X_PURGE = 0x12;
    private static final int CP210X_EMBED_EVENTS = 0x15;
    private static final int CP210X_SET_BAUDRATE = 0x1E;

    private static final int UART_ENABLE = 0x0001;
    private static final int LINE_CTL_8N1 = 0x0800;
    // bit0/1 = DTR/RTS state; bit8/9 = corresponding update masks.
    private static final int MHS_DTR_RTS_ON = 0x0303;
    private static final int PURGE_TX_RX = 0x000F;

    // 官方 V1.3：查询固件状态 TYPE=0xFFFF，零长度 DATA。
    private static final byte[] QUERY_FIRMWARE_FRAME = new byte[] {
            0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };

    private final UsbManager usbManager;

    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint inputEndpoint;
    private UsbEndpoint outputEndpoint;
    private Listener listener;
    private Thread readThread;
    private volatile boolean running;

    private volatile long totalRxBytes;
    private volatile long totalReadPackets;
    private volatile long lastRxTime;
    private volatile long lastStatusNotifyTime;

    public HlkLd6002RadarAdapter(Context context) {
        usbManager = (UsbManager) context.getApplicationContext()
                .getSystemService(Context.USB_SERVICE);
    }

    public static boolean isSupportedDevice(UsbDevice device) {
        return device != null
                && device.getVendorId() == CP210X_VENDOR_ID
                && device.getProductId() == CP210X_PRODUCT_ID;
    }

    @Override
    public synchronized void connect(UsbDevice device, Listener listener) {
        disconnect();
        this.listener = listener;
        totalRxBytes = 0L;
        totalReadPackets = 0L;
        lastRxTime = 0L;
        lastStatusNotifyTime = 0L;

        if (usbManager == null) {
            notifyError("无法获得Android USB服务");
            return;
        }
        if (!isSupportedDevice(device)) {
            notifyError("当前USB设备不是CP2104/CP210x雷达串口桥");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            notifyError("尚未获得毫米波雷达USB访问权限");
            return;
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            notifyError("无法打开毫米波雷达USB设备");
            return;
        }

        if (!findBulkInterface(device)) {
            closeConnection();
            notifyError("CP2104未找到可用Bulk IN/OUT端点");
            return;
        }

        if (!connection.claimInterface(usbInterface, true)) {
            closeConnection();
            notifyError("无法占用CP2104 USB接口");
            return;
        }

        if (!configureCp210x()) {
            closeConnection();
            notifyError("CP2104串口初始化失败（115200 8N1）");
            return;
        }

        Log.i(TAG, "CP2104 configured: 115200 8N1 interface=" + usbInterface.getId()
                + " in=" + inputEndpoint.getAddress() + " out=" + outputEndpoint.getAddress());

        final String baseDeviceLabel = String.format(
                Locale.CHINA,
                "毫米波雷达 / CP2104 (VID %04X / PID %04X) · 115200 8N1",
                device.getVendorId(),
                device.getProductId()
        );

        final HlkLd6002Protocol parser = new HlkLd6002Protocol(
                new HlkLd6002Protocol.Listener() {
                    @Override
                    public void onReading(RadarReading reading) {
                        Listener current = HlkLd6002RadarAdapter.this.listener;
                        if (current != null) {
                            current.onRadarReading(reading);
                        }
                    }

                    @Override
                    public void onProtocolError(String message) {
                        // 连续串口流可能从半帧开始；错误帧不主动断线。
                    }
                }
        );
        parser.setDeviceName(baseDeviceLabel);

        // 这里只能说明 CP2104 已打开，不能说明雷达有业务数据。
        notifyReading(new RadarReading(
                true,
                baseDeviceLabel,
                "CP2104已打开，等待串口RX数据",
                "-- cm",
                "-- bpm",
                "-- bpm",
                System.currentTimeMillis()
        ));

        running = true;
        readThread = new Thread(() -> readLoop(parser, baseDeviceLabel), "HLK-LD6002-USB");
        readThread.start();

        // 主动询问一次固件状态。即使主动上传尚未稳定，只要 UART TX/RX 正常，
        // 官方协议的 0xFFFF 回复也能证明真实串口链路已经打通。
        sendFirmwareQuery();
    }

    private boolean findBulkInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint endpoint = candidate.getEndpoint(e);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    in = endpoint;
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    out = endpoint;
                }
            }
            if (in != null && out != null) {
                usbInterface = candidate;
                inputEndpoint = in;
                outputEndpoint = out;
                return true;
            }
        }
        return false;
    }

    private boolean configureCp210x() {
        int interfaceId = usbInterface.getId();

        // 先清空旧 FIFO，避免上次会话残留半帧。
        connection.controlTransfer(
                REQ_TYPE_HOST_TO_INTERFACE,
                CP210X_PURGE,
                PURGE_TX_RX,
                interfaceId,
                null,
                0,
                1000
        );

        int enable = connection.controlTransfer(
                REQ_TYPE_HOST_TO_INTERFACE,
                CP210X_IFC_ENABLE,
                UART_ENABLE,
                interfaceId,
                null,
                0,
                1000
        );
        if (enable < 0) {
            return false;
        }

        // 禁止 CP210x 往数据流中插入状态事件，确保收到的是纯 UART 字节。
        connection.controlTransfer(
                REQ_TYPE_HOST_TO_INTERFACE,
                CP210X_EMBED_EVENTS,
                0,
                interfaceId,
                null,
                0,
                1000
        );

        byte[] baud = new byte[] {
                (byte) (BAUD_RATE & 0xFF),
                (byte) ((BAUD_RATE >> 8) & 0xFF),
                (byte) ((BAUD_RATE >> 16) & 0xFF),
                (byte) ((BAUD_RATE >> 24) & 0xFF)
        };
        int baudResult = connection.controlTransfer(
                REQ_TYPE_HOST_TO_INTERFACE,
                CP210X_SET_BAUDRATE,
                0,
                interfaceId,
                baud,
                baud.length,
                1000
        );
        if (baudResult != baud.length) {
            return false;
        }

        int lineResult = connection.controlTransfer(
                REQ_TYPE_HOST_TO_INTERFACE,
                CP210X_SET_LINE_CTL,
                LINE_CTL_8N1,
                interfaceId,
                null,
                0,
                1000
        );
        if (lineResult < 0) {
            return false;
        }

        // 部分 CP2104 底板/外设要求 DTR/RTS 置位后 UART 才真正进入工作状态。
        connection.controlTransfer(
                REQ_TYPE_HOST_TO_INTERFACE,
                CP210X_SET_MHS,
                MHS_DTR_RTS_ON,
                interfaceId,
                null,
                0,
                1000
        );
        return true;
    }

    private void sendFirmwareQuery() {
        if (connection == null || outputEndpoint == null) {
            return;
        }
        try {
            connection.bulkTransfer(
                    outputEndpoint,
                    QUERY_FIRMWARE_FRAME,
                    QUERY_FIRMWARE_FRAME.length,
                    1000
            );
        } catch (Exception ignored) {
            // 主动查询只是链路诊断，不影响被动接收线程继续运行。
        }
    }

    private void readLoop(HlkLd6002Protocol parser, String baseDeviceLabel) {
        int packet = inputEndpoint == null
                ? 64
                : Math.max(64, inputEndpoint.getMaxPacketSize());
        byte[] buffer = new byte[Math.max(4096, packet * 32)];
        long loopStart = System.currentTimeMillis();
        long lastQuery = loopStart;

        try {
            while (running && connection != null) {
                int read = connection.bulkTransfer(
                        inputEndpoint,
                        buffer,
                        buffer.length,
                        700
                );
                long now = System.currentTimeMillis();

                if (read > 0) {
                    totalRxBytes += read;
                    if (totalRxBytes <= 256L) {
                        Log.i(TAG, "RX " + read + " bytes: " + toHex(buffer, Math.min(read, 64)));
                    }
                    totalReadPackets++;
                    lastRxTime = now;

                    long validBefore = parser.getValidFrameCount();
                    long readingBefore = parser.getReadingFrameCount();
                    parser.feed(buffer, read);
                    long validAfter = parser.getValidFrameCount();
                    long readingAfter = parser.getReadingFrameCount();

                    // 如果确实收到了 UART 字节，但暂时还没形成业务数据，
                    // 页面明确显示 RX 计数，避免误判为“没作用”。
                    if (parser.getReadingFrameCount() == 0L
                            && readingAfter == readingBefore
                            && now - lastStatusNotifyTime >= 700L) {
                        String state;
                        if (validAfter > validBefore || validAfter > 0) {
                            state = String.format(
                                    Locale.CHINA,
                                    "串口有数据 · RX %,d B · TF %,d 帧 · 最近TYPE 0x%04X",
                                    totalRxBytes,
                                    validAfter,
                                    Math.max(0, parser.getLastType())
                            );
                        } else {
                            state = String.format(
                                    Locale.CHINA,
                                    "串口有原始数据 · RX %,d B · 尚未匹配TF协议",
                                    totalRxBytes
                            );
                        }
                        notifyReading(new RadarReading(
                                true,
                                baseDeviceLabel + " · RX " + totalRxBytes + " B",
                                state,
                                "-- cm",
                                "-- bpm",
                                "-- bpm",
                                now
                        ));
                        lastStatusNotifyTime = now;
                    }
                } else {
                    // 每约 3 秒主动查询一次固件，帮助确认双向串口链路。
                    if (now - lastQuery >= 3000L) {
                        sendFirmwareQuery();
                        lastQuery = now;
                    }

                    // USB 打开并不代表 UART 有数据。5 秒后仍 RX=0 时明确报状态。
                    if (totalRxBytes == 0L
                            && now - loopStart >= 5000L
                            && now - lastStatusNotifyTime >= 2500L) {
                        notifyReading(new RadarReading(
                                true,
                                baseDeviceLabel,
                                "USB已打开，但串口RX=0 · 检查雷达供电及UART TX/RX",
                                "-- cm",
                                "-- bpm",
                                "-- bpm",
                                now
                        ));
                        lastStatusNotifyTime = now;
                    }
                }
            }
        } catch (Exception exception) {
            if (running) {
                notifyError("毫米波雷达读取异常：" + safeMessage(exception));
            }
        } finally {
            if (running) {
                notifyDisconnected("毫米波雷达连接已结束");
            }
            running = false;
        }
    }

    @Override
    public synchronized void disconnect() {
        running = false;
        Thread thread = readThread;
        readThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        if (connection != null && usbInterface != null) {
            try {
                connection.releaseInterface(usbInterface);
            } catch (Exception ignored) {
                // ignore
            }
        }
        closeConnection();
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        connection = null;
        usbInterface = null;
        inputEndpoint = null;
        outputEndpoint = null;
    }

    @Override
    public void release() {
        disconnect();
        listener = null;
    }

    private void notifyReading(RadarReading reading) {
        Listener current = listener;
        if (current != null) {
            current.onRadarReading(reading);
        }
    }

    private void notifyDisconnected(String message) {
        Listener current = listener;
        if (current != null) {
            current.onDisconnected(message);
        }
    }

    private void notifyError(String message) {
        Listener current = listener;
        if (current != null) {
            current.onError(message);
        }
    }

    private String toHex(byte[] data, int length) {
        StringBuilder builder = new StringBuilder();
        int safeLength = Math.min(length, data == null ? 0 : data.length);
        for (int i = 0; i < safeLength; i++) {
            if (i > 0) builder.append(' ');
            builder.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return builder.toString();
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null
                || throwable.getMessage().trim().isEmpty()) {
            return throwable == null ? "未知异常" : throwable.getClass().getSimpleName();
        }
        return throwable.getMessage();
    }
}
