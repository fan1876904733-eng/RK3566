package com.weiguang.drcontroller.hardware.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/** Activity-owned camera cache when the live-preview page does not own the camera. */
public final class BackgroundCameraController {
    public interface Listener { void onState(boolean connected, String message); }
    private final Context context;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("CameraStartup");
    private final Handler worker;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean enabled;
    private int generation;
    private long lastFrame;
    private long openedAt;
    public BackgroundCameraController(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        thread.start(); worker = new Handler(thread.getLooper());
    }
    public void start() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        worker.post(() -> {
            long startupNow = android.os.SystemClock.elapsedRealtime();
            if (enabled && startupNow - Math.max(openedAt, lastFrame) < 8000L) return;
            if (enabled) closeInternal();
            enabled = true;
            openedAt = startupNow;
            lastFrame = 0;
            final int token = ++generation;
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                String[] ids = manager.getCameraIdList();
                if (ids.length == 0) throw new IllegalStateException("未发现摄像头");
                String id = ids[0];
                Size[] sizes = manager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes == null || sizes.length == 0) throw new IllegalStateException("摄像头不支持后台预览格式");
                Size size = sizes[0];
                for (Size option : sizes) if (Math.abs((long) option.getWidth() * option.getHeight() - 640L * 480)
                        < Math.abs((long) size.getWidth() * size.getHeight() - 640L * 480)) size = option;
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                reader.setOnImageAvailableListener(source -> {
                    try (Image image = source.acquireLatestImage()) {
                        if (image == null || token != generation || !enabled) return;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastFrame < 250) return;
                        lastFrame = now;
                        cacheFrame(image);
                        listener.onState(true, "摄像头已自动连接");
                    } catch (Exception error) { android.util.Log.w("WG-CAMERA", "Background frame unavailable", error); }
                }, worker);
                manager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        if (token != generation || !enabled) { device.close(); return; }
                        camera = device;
                        try {
                            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession capture) {
                                    if (token != generation || !enabled) { capture.close(); return; }
                                    session = capture;
                                    try {
                                        CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        request.addTarget(reader.getSurface());
                                        capture.setRepeatingRequest(request.build(), null, worker);
                                    } catch (Exception error) { fail(token, "摄像头自动预览失败"); }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession capture) { fail(token, "摄像头自动连接失败"); }
                            }, worker);
                        } catch (Exception error) { fail(token, "摄像头自动连接失败"); }
                    }
                    @Override public void onDisconnected(CameraDevice device) { device.close(); fail(token, "摄像头已断开"); }
                    @Override public void onError(CameraDevice device, int error) { device.close(); fail(token, "摄像头连接错误 " + error); }
                }, worker);
            } catch (Exception error) { fail(token, "摄像头待连接"); }
        });
    }
    private void cacheFrame(Image image) {
        int width = image.getWidth(), height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        for (int p = 0; p < 3; p++) {
            ByteBuffer data = planes[p].getBuffer();
            int rows = p == 0 ? height : height / 2, cols = p == 0 ? width : width / 2;
            for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
                int target = p == 0 ? y * width + x : width * height + y * width + 2 * x + (p == 1 ? 1 : 0);
                nv21[target] = data.get(y * planes[p].getRowStride() + x * planes[p].getPixelStride());
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(new Rect(0, 0, width, height), 85, bytes);
        byte[] jpeg = bytes.toByteArray();
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (decoded == null) return;
        Matrix transform = new Matrix(); transform.postRotate(180);
        Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), transform, false);
        CameraFrameManager.getInstance().updateLatestFrame(rotated);
        if (rotated != decoded) rotated.recycle();
        decoded.recycle();
    }
    private void fail(int token, String message) {
        if (token != generation) return;
        closeInternal(); CameraFrameManager.getInstance().setConnected(false); listener.onState(false, message);
    }
    private void closeInternal() {
        enabled = false; generation++;
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
    }
    public void stopForPreview() {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        worker.post(() -> { closeInternal(); closed.countDown(); });
        try { closed.await(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    public void release() { worker.post(() -> { closeInternal(); thread.quitSafely(); }); }
}
