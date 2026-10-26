package com.weiguang.drcontroller.hardware.radar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * HLK-LD6002 TinyFrame协议流解析器。
 *
 * 帧结构：SOF(1) + ID(2,BE) + LEN(2,BE) + TYPE(2,BE) + HEAD_CKSUM(1)
 *        + DATA(N, 小端数据语义) + DATA_CKSUM(1, N>0时)。
 */
public final class HlkLd6002Protocol {

    public interface Listener {
        void onReading(RadarReading reading);
        void onProtocolError(String message);
    }

    private static final int MAX_DATA_LENGTH = 1024;
    private static final int TYPE_FW_STATUS = 0xFFFF;
    private static final int TYPE_HUMAN = 0x0F09;
    private static final int TYPE_BREATH = 0x0A14;
    private static final int TYPE_HEART = 0x0A15;
    private static final int TYPE_DISTANCE = 0x0A16;
    private static final int TYPE_TRACK_POSITION = 0x0A17;

    private final Listener listener;
    private byte[] pending = new byte[4096];
    private int pendingLength = 0;

    private boolean connected = true;
    private String deviceName = "毫米波雷达 · 115200 8N1";
    private String targetStatus = "等待雷达数据";
    private String distance = "-- cm";
    private String breathRate = "-- bpm";
    private String heartRate = "-- bpm";
    private long validFrameCount = 0L;
    private long readingFrameCount = 0L;
    private int lastType = -1;
    private final ArrayDeque<Float> distanceSamplesCm = new ArrayDeque<>();
    private long lastDirectDistanceTime = 0L;

    public HlkLd6002Protocol(Listener listener) {
        this.listener = listener;
    }

    public synchronized void setDeviceName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            deviceName = name.trim();
        }
    }

    public synchronized void reset() {
        pendingLength = 0;
        targetStatus = "等待雷达数据";
        distance = "-- cm";
        breathRate = "-- bpm";
        heartRate = "-- bpm";
        validFrameCount = 0L;
        readingFrameCount = 0L;
        lastType = -1;
        distanceSamplesCm.clear();
        lastDirectDistanceTime = 0L;
    }

    public synchronized long getValidFrameCount() {
        return validFrameCount;
    }

    public synchronized long getReadingFrameCount() {
        return readingFrameCount;
    }

    public synchronized int getLastType() {
        return lastType;
    }

    public synchronized void feed(byte[] data, int length) {
        if (data == null || length <= 0) {
            return;
        }
        ensureCapacity(pendingLength + length);
        System.arraycopy(data, 0, pending, pendingLength, length);
        pendingLength += length;
        parseAvailableFrames();
    }

    private void parseAvailableFrames() {
        int cursor = 0;

        while (pendingLength - cursor >= 8) {
            if ((pending[cursor] & 0xFF) != 0x01) {
                cursor++;
                continue;
            }

            int len = readU16Be(pending, cursor + 3);
            if (len < 0 || len > MAX_DATA_LENGTH) {
                cursor++;
                reportError("LD6002帧长度异常：" + len);
                continue;
            }

            int frameLength = 8 + len + (len > 0 ? 1 : 0);
            if (pendingLength - cursor < frameLength) {
                break;
            }

            int expectedHeaderChecksum = checksum(pending, cursor, 7);
            int actualHeaderChecksum = pending[cursor + 7] & 0xFF;
            if (expectedHeaderChecksum != actualHeaderChecksum) {
                cursor++;
                continue;
            }

            if (len > 0) {
                int expectedDataChecksum = checksum(pending, cursor + 8, len);
                int actualDataChecksum = pending[cursor + 8 + len] & 0xFF;
                if (expectedDataChecksum != actualDataChecksum) {
                    cursor += frameLength;
                    reportError("LD6002数据校验失败");
                    continue;
                }
            }

            int type = readU16Be(pending, cursor + 5);
            validFrameCount++;
            lastType = type;
            byte[] payload = len == 0
                    ? new byte[0]
                    : Arrays.copyOfRange(pending, cursor + 8, cursor + 8 + len);
            handleFrame(type, payload);
            cursor += frameLength;
        }

        if (cursor > 0) {
            int remaining = pendingLength - cursor;
            if (remaining > 0) {
                System.arraycopy(pending, cursor, pending, 0, remaining);
            }
            pendingLength = remaining;
        }
    }

    private void handleFrame(int type, byte[] payload) {
        boolean changed = false;

        switch (type) {
            case TYPE_FW_STATUS:
                if (payload.length >= 4) {
                    int project = payload[0] & 0xFF;
                    int major = payload[1] & 0xFF;
                    int sub = payload[2] & 0xFF;
                    int modified = payload[3] & 0xFF;
                    if ("等待雷达数据".equals(targetStatus)) {
                        targetStatus = "串口通讯正常 · 固件 "
                                + major + "." + sub + "." + modified
                                + " · 项目 " + project;
                    }
                    changed = true;
                }
                break;

            case TYPE_HUMAN:
                if (payload.length >= 2) {
                    int human = readU16Le(payload, 0);
                    targetStatus = human == 1 ? "检测到物体" : "未检测到物体";
                    if (human != 1) {
                        distance = "-- cm";
                        distanceSamplesCm.clear();
                        lastDirectDistanceTime = 0L;
                    }
                    changed = true;
                }
                break;

            case TYPE_BREATH:
                if (payload.length >= 4) {
                    float rate = readFloatLe(payload, 0);
                    if (isReasonable(rate, 0f, 200f)) {
                        breathRate = formatRate(rate);
                        changed = true;
                    }
                }
                break;

            case TYPE_HEART:
                if (payload.length >= 4) {
                    float rate = readFloatLe(payload, 0);
                    if (isReasonable(rate, 0f, 300f)) {
                        heartRate = formatRate(rate);
                        changed = true;
                    }
                }
                break;

            case TYPE_DISTANCE:
                /*
                 * 距离显示只使用雷达当前帧，不再做5帧中值滤波，也不再用0x0A17
                 * 坐标距离顶替，避免实机测试时看起来像“写死”。
                 *
                 * 官方V1.3表格对LEN有排版矛盾：0x0A16列出flag(uint32)+range(float)
                 * 共8字节，但LEN示例写4。当前兼容两种实机固件：
                 *  - >=8字节：flag + range(cm)；flag=1才有效。
                 *  - 恰好4字节：仅当这4字节能解析成合理float距离时才接受；
                 *    0/1这样的flag值不会被误当成距离。
                 */
                if (payload.length >= 8) {
                    long flag = readU32Le(payload, 0);
                    float rawRangeCm = readFloatLe(payload, 4);
                    if (flag == 1L && isReasonable(rawRangeCm, 1f, 2000f)) {
                        distance = formatDistance(rawRangeCm);
                        lastDirectDistanceTime = System.currentTimeMillis();
                    } else {
                        distance = "-- cm";
                        lastDirectDistanceTime = 0L;
                    }
                    changed = true;
                } else if (payload.length == 4) {
                    float rawRangeCm = readFloatLe(payload, 0);
                    long rawU32 = readU32Le(payload, 0);
                    // 0/1常见于flag；极小非正规float也不接受。
                    if (rawU32 != 0L && rawU32 != 1L
                            && isReasonable(rawRangeCm, 1f, 2000f)) {
                        distance = formatDistance(rawRangeCm);
                        lastDirectDistanceTime = System.currentTimeMillis();
                    } else {
                        distance = "-- cm";
                        lastDirectDistanceTime = 0L;
                    }
                    changed = true;
                }
                break;

            case TYPE_TRACK_POSITION:
                // 0x0A17不再用来覆盖“物体距离”；仅保留协议识别，不改变距离显示。
                break;

            default:
                break;
        }

        if (changed && listener != null) {
            readingFrameCount++;
            listener.onReading(new RadarReading(
                    connected,
                    deviceName,
                    targetStatus,
                    (lastDirectDistanceTime > 0L
                            && System.currentTimeMillis() - lastDirectDistanceTime <= 2500L)
                            ? distance : "-- cm",
                    breathRate,
                    heartRate,
                    System.currentTimeMillis()
            ));
        }
    }

    private float stabilizeDistance(float valueCm) {
        if (distanceSamplesCm.size() >= 5) {
            distanceSamplesCm.removeFirst();
        }
        distanceSamplesCm.addLast(valueCm);
        List<Float> values = new ArrayList<>(distanceSamplesCm);
        Collections.sort(values);
        int n = values.size();
        if ((n & 1) == 1) {
            return values.get(n / 2);
        }
        return (values.get(n / 2 - 1) + values.get(n / 2)) / 2f;
    }

    private String formatRate(float value) {
        return String.format(Locale.CHINA, "%.1f bpm", value);
    }

    private String formatDistance(float value) {
        return String.format(Locale.CHINA, "%.1f cm", value);
    }

    private boolean isReasonable(float value, float min, float max) {
        return !Float.isNaN(value)
                && !Float.isInfinite(value)
                && value >= min
                && value <= max;
    }

    private void reportError(String message) {
        if (listener != null) {
            listener.onProtocolError(message);
        }
    }

    private int checksum(byte[] data, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value ^= data[offset + i] & 0xFF;
        }
        return (~value) & 0xFF;
    }

    private int readU16Be(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8)
                | (data[offset + 1] & 0xFF);
    }

    private int readU16Le(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8);
    }

    private long readU32Le(byte[] data, int offset) {
        return ((long) data[offset] & 0xFFL)
                | (((long) data[offset + 1] & 0xFFL) << 8)
                | (((long) data[offset + 2] & 0xFFL) << 16)
                | (((long) data[offset + 3] & 0xFFL) << 24);
    }

    private float readFloatLe(byte[] data, int offset) {
        int bits = (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
        return Float.intBitsToFloat(bits);
    }

    private void ensureCapacity(int required) {
        if (required <= pending.length) {
            return;
        }
        int newSize = pending.length;
        while (newSize < required) {
            newSize *= 2;
        }
        pending = Arrays.copyOf(pending, newSize);
    }
}
