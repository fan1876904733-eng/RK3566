package com.weiguang.drcontroller.hardware.usb;

import android.hardware.usb.UsbDevice;

import com.weiguang.drcontroller.hardware.radar.RadarReading;

/**
 * USB毫米波雷达统一适配接口。
 *
 * 不论后续设备属于USB串口、HID还是自定义Bulk协议，解析层最终都通过
 * Listener输出RadarReading，UI层无需关心底层协议。
 */
public interface UsbRadarAdapter {

    interface Listener {
        void onRadarReading(RadarReading reading);

        void onDisconnected(String message);

        void onError(String message);
    }

    void connect(UsbDevice device, Listener listener);

    void disconnect();

    void release();
}
