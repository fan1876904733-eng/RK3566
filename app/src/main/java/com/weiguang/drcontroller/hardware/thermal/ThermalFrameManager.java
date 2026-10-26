package com.weiguang.drcontroller.hardware.thermal;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.weiguang.drcontroller.hardware.storage.ImageRepository;

/**
 * 热成像实时帧的应用级缓存和拍照入口。
 *
 * 后续接入USB热成像厂商SDK时，把每一帧Bitmap调用updateLatestFrame()送入这里。
 * 即使当前界面停留在摄像头页面，也能用同一个拍照键保存当前热成像帧。
 */
public final class ThermalFrameManager {

    public interface CaptureCallback {
        void onSuccess(Uri uri);

        void onFailure(String message);
    }

    private static volatile ThermalFrameManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Bitmap latestFrame;
    private boolean connected;
    private String deviceName = "--";

    private ThermalFrameManager() {
    }

    public static ThermalFrameManager getInstance() {
        if (instance == null) {
            synchronized (ThermalFrameManager.class) {
                if (instance == null) {
                    instance = new ThermalFrameManager();
                }
            }
        }
        return instance;
    }

    public synchronized void setConnectionState(
            boolean connected,
            String deviceName
    ) {
        this.connected = connected;
        this.deviceName = deviceName == null || deviceName.trim().isEmpty()
                ? "--"
                : deviceName.trim();
        if (!connected) {
            clearLatestFrameLocked();
        }
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public synchronized String getDeviceName() {
        return deviceName;
    }

    public synchronized boolean hasFrame() {
        return latestFrame != null && !latestFrame.isRecycled();
    }

    public synchronized void updateLatestFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return;
        }

        Bitmap copy = frame.copy(
                frame.getConfig() == null
                        ? Bitmap.Config.ARGB_8888
                        : frame.getConfig(),
                false
        );
        if (copy == null) {
            return;
        }
        clearLatestFrameLocked();
        latestFrame = copy;
        connected = true;
    }

    public synchronized Bitmap getLatestFrameCopy() {
        if (latestFrame == null || latestFrame.isRecycled()) {
            return null;
        }
        return latestFrame.copy(
                latestFrame.getConfig() == null
                        ? Bitmap.Config.ARGB_8888
                        : latestFrame.getConfig(),
                false
        );
    }

    public void captureLatestFrame(
            Context context,
            String captureId,
            CaptureCallback callback
    ) {
        Bitmap frameCopy = getLatestFrameCopy();
        if (frameCopy == null) {
            mainHandler.post(() -> callback.onFailure(
                    connected
                            ? "热成像已连接，但尚未收到实时图像帧"
                            : "热成像尚未连接"
            ));
            return;
        }

        ImageRepository.saveBitmap(
                context,
                frameCopy,
                ImageRepository.ImageType.THERMAL,
                captureId,
                new ImageRepository.SaveCallback() {
                    @Override
                    public void onSaved(Uri uri) {
                        frameCopy.recycle();
                        callback.onSuccess(uri);
                    }

                    @Override
                    public void onError(String message) {
                        frameCopy.recycle();
                        callback.onFailure(message);
                    }
                }
        );
    }

    public synchronized void disconnect() {
        connected = false;
        deviceName = "--";
        clearLatestFrameLocked();
    }

    private void clearLatestFrameLocked() {
        if (latestFrame != null && !latestFrame.isRecycled()) {
            latestFrame.recycle();
        }
        latestFrame = null;
    }
}
