package com.weiguang.drcontroller.hardware.radar;

/**
 * 毫米波雷达的一组最新业务数据。
 *
 * 协议中的“有人/无人、距离、呼吸速率、心率”分别来自：
 * 0x0F09、0x0A16、0x0A14、0x0A15。
 */
public final class RadarReading {

    private final boolean connected;
    private final String deviceName;
    private final String targetStatus;
    private final String distance;
    private final String breathRate;
    private final String heartRate;
    private final long dataTimeMillis;

    public RadarReading(
            boolean connected,
            String deviceName,
            String targetStatus,
            String distance,
            String breathRate,
            String heartRate,
            long dataTimeMillis
    ) {
        this.connected = connected;
        this.deviceName = deviceName;
        this.targetStatus = targetStatus;
        this.distance = distance;
        this.breathRate = breathRate;
        this.heartRate = heartRate;
        this.dataTimeMillis = dataTimeMillis;
    }

    public static RadarReading empty() {
        return new RadarReading(
                false,
                "毫米波雷达：--",
                "未检测到物体",
                "-- cm",
                "-- bpm",
                "-- bpm",
                0L
        );
    }

    public boolean isConnected() {
        return connected;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getTargetStatus() {
        return targetStatus;
    }

    public String getDistance() {
        return distance;
    }

    public String getBreathRate() {
        return breathRate;
    }

    public String getHeartRate() {
        return heartRate;
    }

    public long getDataTimeMillis() {
        return dataTimeMillis;
    }
}
