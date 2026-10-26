package com.weiguang.drcontroller.hardware.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.weiguang.drcontroller.hardware.storage.ImageRepository;

/**
 * 摄像头最新帧的应用级缓存。
 * X-Ray页面可在后台摄像头预览运行时冻结拍摄瞬间的实时图。
 */
public final class CameraFrameManager {

    public interface CaptureCallback {
        void onSuccess(Uri uri);
        void onFailure(String message);
    }

    private static volatile CameraFrameManager instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Bitmap latestFrame;
    private boolean connected;

    private CameraFrameManager() { }

    public static CameraFrameManager getInstance() {
        if (instance == null) {
            synchronized (CameraFrameManager.class) {
                if (instance == null) instance = new CameraFrameManager();
            }
        }
        return instance;
    }

    public synchronized void setConnected(boolean connected) {
        this.connected = connected;
        if (!connected) clearLatestFrameLocked();
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public synchronized boolean hasFrame() {
        return latestFrame != null && !latestFrame.isRecycled();
    }

    public synchronized void updateLatestFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;
        Bitmap copy = frame.copy(
                frame.getConfig() == null ? Bitmap.Config.ARGB_8888 : frame.getConfig(),
                false
        );
        if (copy == null) return;
        clearLatestFrameLocked();
        latestFrame = copy;
        connected = true;
    }

    public synchronized Bitmap getLatestFrameCopy() {
        if (latestFrame == null || latestFrame.isRecycled()) return null;
        return latestFrame.copy(
                latestFrame.getConfig() == null ? Bitmap.Config.ARGB_8888 : latestFrame.getConfig(),
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
            if (callback != null) {
                mainHandler.post(() -> callback.onFailure(
                        connected ? "摄像头尚未收到实时画面" : "摄像头尚未连接"
                ));
            }
            return;
        }
        ImageRepository.saveBitmap(
                context,
                frameCopy,
                ImageRepository.ImageType.CAMERA,
                captureId,
                new ImageRepository.SaveCallback() {
                    @Override
                    public void onSaved(Uri uri) {
                        frameCopy.recycle();
                        if (callback != null) callback.onSuccess(uri);
                    }

                    @Override
                    public void onError(String message) {
                        frameCopy.recycle();
                        if (callback != null) callback.onFailure(message);
                    }
                }
        );
    }

    public synchronized void disconnect() {
        connected = false;
        clearLatestFrameLocked();
    }

    private void clearLatestFrameLocked() {
        if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
        latestFrame = null;
    }
}
