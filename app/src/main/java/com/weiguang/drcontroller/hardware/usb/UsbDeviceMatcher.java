package com.weiguang.drcontroller.hardware.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * USB设备枚举与VID/PID匹配工具。
 *
 * 目前没有具体设备参数，因此只负责安全枚举，不会把第一个USB设备直接认作
 * 摄像头、热成像或毫米波雷达。获得厂商VID/PID后调用findByVidPid即可。
 */
public final class UsbDeviceMatcher {

    private UsbDeviceMatcher() {
    }

    public static List<UsbDevice> listAll(UsbManager usbManager) {
        if (usbManager == null || usbManager.getDeviceList().isEmpty()) {
            return Collections.emptyList();
        }

        List<UsbDevice> devices =
                new ArrayList<>(usbManager.getDeviceList().values());

        devices.sort(Comparator.comparing(UsbDeviceMatcher::describe));
        return devices;
    }

    public static UsbDevice findByVidPid(
            UsbManager usbManager,
            int vendorId,
            int productId
    ) {
        for (UsbDevice device : listAll(usbManager)) {
            if (device.getVendorId() == vendorId
                    && device.getProductId() == productId) {
                return device;
            }
        }
        return null;
    }

    public static String describe(UsbDevice device) {
        if (device == null) {
            return "USB设备：--";
        }

        String productName = null;
        try {
            productName = device.getProductName();
        } catch (SecurityException ignored) {
            // Android未授权访问USB描述符时仍可使用VID/PID进行匹配。
        }
        String prefix = productName == null || productName.trim().isEmpty()
                ? "USB设备"
                : productName.trim();

        return prefix
                + "（VID " + device.getVendorId()
                + " / PID " + device.getProductId() + "）";
    }
}
