package com.weiguang.drcontroller.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.storage.ImageRepository;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;
import com.weiguang.drcontroller.hardware.camera.CameraFrameManager;
import com.weiguang.drcontroller.hardware.thermal.HikThermalSdkBridge;
import com.weiguang.drcontroller.hardware.thermal.ThermalFrameManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * RK3566 + OV5647 内置 MIPI 摄像头 V3.6.6。
 *
 * MIPI CSI 是硬件总线，Android 应用不能直接访问 CSI 引脚。
 * 摄像头经过 RK3566 ISP/V4L2/Camera HAL 后由 Camera2 暴露。
 * 本版本不再把 External Camera HAL 暴露的 100+ ID（例如 110）当作 MIPI 摄像头。
 * 只选择 Android 正常注册的非 External 内置摄像头，并优先后置摄像头。
 *
 * 所有 Camera2 回调运行在后台线程；任何 View 更新统一切回主线程，避免
 * CalledFromWrongThreadException。
 */
public class PreviewFragment extends Fragment {

    private static final String TAG = "WG-CAMERA";
    private static final long FIRST_FRAME_TIMEOUT_MS = 6000L;

    private TextureView cameraTextureView;
    private TextView cameraStatusText;
    private Button captureButton;
    private Button pairedCaptureButton;
    private Button cameraControlButton;
    private View cameraBlackMask;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private Surface previewSurface;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String selectedCameraId;
    private Size previewSize;
    private volatile boolean cameraStarting;
    private volatile boolean cameraRunning;
    private volatile boolean firstFrameReceived;
    private boolean pairedCaptureRunning;
    // 语音"同时拍照"：等待摄像头+热成像就绪后自动拍照
    private boolean autoCaptureOnReady;
    private Runnable autoCaptureCheck;
    private int autoCaptureWaitCount;
    // 一键全检支持：同时拍照成功后的回调（由 MainActivity 设置，用于触发 AI 分析）
    private CaseCaptureListener caseCaptureListener;
    private String lastCaptureId;
    private long lastCaptureTimeMillis;
    private long lastCameraFrameCacheTimeMs;
    private final CameraFrameManager cameraFrameManager = CameraFrameManager.getInstance();

    private final Runnable firstFrameTimeout = () -> {
        if (!isAdded() || !cameraRunning || firstFrameReceived) return;
        showStatus("⚠ 摄像头已打开，但6秒没有收到预览帧"
                + (previewSize == null ? "" : " · " + previewSize.getWidth() + "×" + previewSize.getHeight()));
        updateMainCameraStatus(false, "摄像头已打开但没有预览帧");
    };

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) startCamera();
                        else {
                            showStatus("摄像头权限被拒绝");
                            updateMainCameraStatus(false, "摄像头无权限");
                            setControlState(false);
                        }
                    }
            );

    public PreviewFragment() {
        this(false);
    }

    /** 语音指令"同时拍照"时使用：摄像头就绪后自动执行一次同时拍照 */
    /** 一键全检：同时拍照（实时图+热成像）成功后回调，携带新案例 ID 与拍摄时间。 */
    public interface CaseCaptureListener {
        void onPairedCaseSaved(String caseId, long timeMillis);

        default void onAutoCaptureFailed(String message) {
        }
    }

    public void setCaseCaptureListener(CaseCaptureListener listener) {
        this.caseCaptureListener = listener;
    }

    public PreviewFragment(boolean autoCaptureOnReady) {
        super(R.layout.fragment_preview);
        this.autoCaptureOnReady = autoCaptureOnReady;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cameraTextureView = view.findViewById(R.id.cameraTextureView);
        cameraStatusText = view.findViewById(R.id.cameraStatusText);
        captureButton = view.findViewById(R.id.captureButton);
        pairedCaptureButton = view.findViewById(R.id.pairedCaptureButton);
        cameraControlButton = view.findViewById(R.id.cameraControlButton);
        cameraBlackMask = view.findViewById(R.id.cameraBlackMask);

        cameraManager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
        if (cameraBlackMask != null) cameraBlackMask.setVisibility(View.VISIBLE);
        setControlState(false);

        cameraTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (cameraStarting && cameraDevice == null) {
                    openMipiCamera(width, height);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) { }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                closeCameraResources();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                if (!firstFrameReceived && cameraRunning) {
                    firstFrameReceived = true;
                    mainHandler.removeCallbacks(firstFrameTimeout);
                    if (cameraBlackMask != null) cameraBlackMask.setVisibility(View.GONE);
                    String size = previewSize == null ? "" : " · " + previewSize.getWidth() + "×" + previewSize.getHeight();
                    showStatus("● 摄像头画面正常" + size);
                    cameraFrameManager.setConnected(true);
                    updateMainCameraStatus(true, "摄像头画面正常");
                    setControlState(true);
                    if (autoCaptureOnReady) {
                        autoCaptureOnReady = false;
                        scheduleAutoCapture();
                    }
                }
                cacheCurrentCameraFrameIfNeeded();
            }
        });

        cameraControlButton.setOnClickListener(v -> {
            if (cameraRunning || cameraStarting) stopCamera();
            else checkCameraPermission();
        });
        captureButton.setOnClickListener(v -> takeCameraOnlyPhoto());
        pairedCaptureButton.setOnClickListener(v -> takePairedPhoto());

        checkCameraPermission();
    }

    /**
     * 确保全局热成像 SDK 已在后台采集（"同时拍照"需要最新热成像帧）。
     * 注意：这里不再挂载子热成像页面。此前每个摄像头页实例都会挂载一个
     * ThermalFragment，各自 new 一个 SDK 实例同时抢占同一 USB 设备，
     * 导致所有实例 USB_Init/USB_Login 互相冲突、热成像全部连接失败。
     * 现在整个进程只有 HikThermalSdkBridge 一个单例连接热成像，
     * 帧由 SDK 直接写入 ThermalFrameManager，"同时拍照"随时可取帧。
     */
    private void ensurePairedThermalCapture() {
        if (!isAdded() || getView() == null) return;
        HikThermalSdkBridge.getInstance(requireContext().getApplicationContext()).start();
    }

    private void checkCameraPermission() {
        if (!isAdded()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        if (!isAdded() || cameraStarting || cameraRunning) return;
        // 确保全局热成像 SDK 在后台采集（单例，供"同时拍照"取最新热成像帧）
        ensurePairedThermalCapture();
        cameraStarting = true;
        firstFrameReceived = false;
        mainHandler.removeCallbacks(firstFrameTimeout);
        if (cameraBlackMask != null) cameraBlackMask.setVisibility(View.VISIBLE);
        setControlState(false);
        showStatus("正在打开摄像头...");
        startCameraThread();

        if (cameraTextureView != null && cameraTextureView.isAvailable()) {
            openMipiCamera(cameraTextureView.getWidth(), cameraTextureView.getHeight());
        } else {
            showStatus("等待摄像头预览...");
        }
    }

    @SuppressLint("MissingPermission")
    private void openMipiCamera(int viewWidth, int viewHeight) {
        if (!cameraStarting || cameraManager == null || cameraHandler == null) return;
        try {
            selectedCameraId = chooseMipiCameraId();
            if (selectedCameraId == null) {
                failCamera("系统尚未注册可用摄像头，请检查摄像头连接与系统驱动");
                return;
            }

            CameraCharacteristics c = cameraManager.getCameraCharacteristics(selectedCameraId);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] outputs = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
            if (outputs == null || outputs.length == 0) {
                failCamera("摄像头不支持实时预览");
                return;
            }

            previewSize = chooseStablePreviewSize(outputs);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Log.i(TAG, "open MIPI camera id=" + selectedCameraId
                    + " lens=" + facing
                    + " sizes=" + Arrays.toString(outputs)
                    + " selected=" + previewSize);

            postUi(() -> {
                if (cameraTextureView instanceof AutoFitTextureView) {
                    ((AutoFitTextureView) cameraTextureView).setAspectRatio(
                            previewSize.getWidth(), previewSize.getHeight());
                }
                showStatus("正在打开摄像头 · " + previewSize.getWidth() + "×" + previewSize.getHeight());
            });

            cameraManager.openCamera(selectedCameraId, cameraStateCallback, cameraHandler);
        } catch (Throwable t) {
            failCamera("摄像头打开失败：" + safeMessage(t));
        }
    }

    /**
     * 只选择内置 Camera HAL 注册的摄像头。External Camera HAL 的 LensFacing.EXTERNAL
     * 以及 100+ ID 不再作为 OV5647 MIPI 摄像头使用。优先后置，其次任意非 External。
     */
    private String chooseMipiCameraId() throws CameraAccessException {
        String[] ids = cameraManager.getCameraIdList();
        Log.i(TAG, "Camera HAL ids=" + Arrays.toString(ids));
        String firstInternal = null;
        for (String id : ids) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Log.i(TAG, "camera id=" + id + " lens=" + facing);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                Log.w(TAG, "skip external camera id=" + id + " for OV5647 MIPI");
                continue;
            }
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] outputs = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
            if (outputs == null || outputs.length == 0) continue;
            if (firstInternal == null) firstInternal = id;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return firstInternal;
    }

    /**
     * OV5647 bring-up 优先使用 HAL 明确公布的 640x480，成功后再提高分辨率。
     * 若无 640x480，则尝试1280x720，最后选择不超过1080p的可用尺寸。
     */
    private Size chooseStablePreviewSize(Size[] sizes) {
        for (Size s : sizes) if (s.getWidth() == 640 && s.getHeight() == 480) return s;
        for (Size s : sizes) if (s.getWidth() == 1280 && s.getHeight() == 720) return s;
        List<Size> list = Arrays.asList(sizes);
        return list.stream()
                .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                .min(Comparator.comparingLong(s -> Math.abs((long) s.getWidth() * s.getHeight() - 1280L * 720L)))
                .orElse(sizes[0]);
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            try { camera.close(); } catch (Throwable ignored) { }
            if (cameraDevice == camera) cameraDevice = null;
            failCamera("摄像头已断开");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            try { camera.close(); } catch (Throwable ignored) { }
            if (cameraDevice == camera) cameraDevice = null;
            failCamera("摄像头发生错误");
        }
    };

    private void createPreviewSession() {
        CameraDevice device = cameraDevice;
        TextureView textureView = cameraTextureView;
        if (device == null || textureView == null || !textureView.isAvailable() || previewSize == null) {
            failCamera("摄像头已打开，但预览画面不可用");
            return;
        }

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                failCamera("无法获得摄像头预览画面");
                return;
            }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            previewSurface = surface;

            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            previewRequestBuilder = builder;

            device.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                // 不额外强制AF/AE模式，交给Rockchip HAL默认值，减少厂商HAL兼容问题。
                                session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler);
                                cameraStarting = false;
                                cameraRunning = true;
                                postUi(() -> {
                                    showStatus("摄像头已打开，等待第一帧...");
                                    setControlState(false);
                                    mainHandler.removeCallbacks(firstFrameTimeout);
                                    mainHandler.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
                                });
                            } catch (Throwable t) {
                                failCamera("摄像头预览请求失败：" + safeMessage(t));
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            failCamera("摄像头预览会话创建失败 · " + previewSize.getWidth() + "×" + previewSize.getHeight());
                        }
                    },
                    cameraHandler
            );
        } catch (Throwable t) {
            failCamera("创建摄像头预览失败：" + safeMessage(t));
        }
    }

    private void cacheCurrentCameraFrameIfNeeded() {
        if (!cameraRunning || cameraTextureView == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCameraFrameCacheTimeMs < 350L) return;
        lastCameraFrameCacheTimeMs = now;
        Bitmap frame = getCurrentCameraBitmap();
        if (frame == null) return;
        cameraFrameManager.updateLatestFrame(frame);
        frame.recycle();
    }

    private Bitmap getCurrentCameraBitmap() {
        if (cameraTextureView == null) return null;
        if (previewSize != null && previewSize.getWidth() > 0 && previewSize.getHeight() > 0) {
            try {
                return cameraTextureView.getBitmap(
                        previewSize.getWidth(),
                        previewSize.getHeight()
                );
            } catch (Throwable ignored) {
                // 回退到TextureView当前尺寸，不改变现有Camera2连接。
            }
        }
        return cameraTextureView.getBitmap();
    }

    /** 语音指令触发：等摄像头与热成像都就绪后自动"同时拍照"，最长等 8 秒 */
    private void scheduleAutoCapture() {
        autoCaptureWaitCount = 0;
        autoCaptureCheck = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || getView() == null) return;
                if (!cameraRunning || !firstFrameReceived) return;
                if (ThermalFrameManager.getInstance().hasFrame()) {
                    autoCaptureCheck = null;
                    takePairedPhoto();
                } else if (++autoCaptureWaitCount >= 16) {
                    autoCaptureCheck = null;
                    String message = "热成像在8秒内未就绪，本次自动全检已停止";
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    if (caseCaptureListener != null) {
                        caseCaptureListener.onAutoCaptureFailed(message);
                    }
                } else {
                    mainHandler.postDelayed(this, 500);
                }
            }
        };
        mainHandler.postDelayed(autoCaptureCheck, 500);
    }

    private void takePairedPhoto() {
        if (!cameraRunning || !firstFrameReceived || cameraTextureView == null || pairedCaptureRunning) {
            Toast.makeText(requireContext(), "请先等待摄像头画面正常", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ThermalFrameManager.getInstance().hasFrame()) {
            Toast.makeText(requireContext(), "请先等待热成像画面正常，再进行同时拍照", Toast.LENGTH_SHORT).show();
            ensurePairedThermalCapture();
            return;
        }

        Bitmap bitmap = getCurrentCameraBitmap();
        if (bitmap == null) {
            Toast.makeText(requireContext(), "无法取得当前摄像头画面", Toast.LENGTH_SHORT).show();
            return;
        }

        pairedCaptureRunning = true;
        setPairedCaptureBusy(true);
        long captureTimeMillis = System.currentTimeMillis();
        String captureId = ImageRepository.buildCaptureId(captureTimeMillis);
        lastCaptureId = captureId;
        lastCaptureTimeMillis = captureTimeMillis;
        CaseRepository.activateCase(requireContext(), captureId, captureTimeMillis);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).registerCaseCapture(captureId, captureTimeMillis);
        }
        PairCaptureResult result = new PairCaptureResult();

        ThermalFrameManager.getInstance().captureLatestFrame(
                requireContext().getApplicationContext(),
                captureId,
                new ThermalFrameManager.CaptureCallback() {
                    @Override public void onSuccess(Uri uri) {
                        result.finishThermal(true, "热成像已保存");
                        maybeFinishPairCapture(result);
                    }
                    @Override public void onFailure(String message) {
                        result.finishThermal(false, message);
                        maybeFinishPairCapture(result);
                    }
                }
        );

        ImageRepository.saveBitmap(
                requireContext().getApplicationContext(),
                bitmap,
                ImageRepository.ImageType.CAMERA,
                captureId,
                new ImageRepository.SaveCallback() {
                    @Override public void onSaved(Uri uri) {
                        result.finishCamera(true, "摄像头图像已保存");
                        maybeFinishPairCapture(result);
                    }
                    @Override public void onError(String message) {
                        result.finishCamera(false, message);
                        maybeFinishPairCapture(result);
                    }
                }
        );
    }

    private void maybeFinishPairCapture(PairCaptureResult result) {
        postUi(() -> {
            if (!result.isComplete() || !pairedCaptureRunning || !isAdded()) return;
            pairedCaptureRunning = false;
            setPairedCaptureBusy(false);
            String message;
            if (result.cameraSuccess && result.thermalSuccess) {
                message = "摄像头和热成像已同时保存";
                // 一键全检：拍摄成功，通知上层（MainActivity）启动 AI 分析
                if (caseCaptureListener != null && lastCaptureId != null) {
                    final String cid = lastCaptureId;
                    final long ctime = lastCaptureTimeMillis;
                    caseCaptureListener.onPairedCaseSaved(cid, ctime);
                }
            } else if (result.cameraSuccess) message = "摄像头已保存；热成像未保存：" + result.thermalMessage;
            else if (result.thermalSuccess) message = "热成像已保存；摄像头未保存：" + result.cameraMessage;
            else message = "拍照失败：摄像头=" + result.cameraMessage + "；热成像=" + result.thermalMessage;
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
        });
    }

    /** 只拍实时图：保存当前摄像头画面为新案例，不等待热成像。 */
    private void takeCameraOnlyPhoto() {
        if (!cameraRunning || !firstFrameReceived || cameraTextureView == null) {
            Toast.makeText(requireContext(), "请先等待摄像头画面正常", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bitmap = getCurrentCameraBitmap();
        if (bitmap == null) {
            Toast.makeText(requireContext(), "无法取得当前摄像头画面", Toast.LENGTH_SHORT).show();
            return;
        }
        long captureTimeMillis = System.currentTimeMillis();
        String captureId = ImageRepository.buildCaptureId(captureTimeMillis);
        CaseRepository.activateCase(requireContext(), captureId, captureTimeMillis);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).registerCaseCapture(captureId, captureTimeMillis);
        }
        setSingleCaptureBusy(true);
        ImageRepository.saveBitmap(
                requireContext().getApplicationContext(),
                bitmap,
                ImageRepository.ImageType.CAMERA,
                captureId,
                new ImageRepository.SaveCallback() {
                    @Override public void onSaved(Uri uri) {
                        postUi(() -> {
                            if (!isAdded()) return;
                            setSingleCaptureBusy(false);
                            Toast.makeText(requireContext(), "实时图已保存", Toast.LENGTH_LONG).show();
                        });
                    }
                    @Override public void onError(String message) {
                        postUi(() -> {
                            if (!isAdded()) return;
                            setSingleCaptureBusy(false);
                            Toast.makeText(requireContext(), "拍照失败：" + message, Toast.LENGTH_LONG).show();
                        });
                    }
                }
        );
    }

    private void setSingleCaptureBusy(boolean busy) {
        if (captureButton == null) return;
        captureButton.setText(busy ? "保存中..." : "拍照");
        captureButton.setEnabled(!busy && cameraRunning && firstFrameReceived);
    }

    private void setPairedCaptureBusy(boolean busy) {
        if (pairedCaptureButton == null) return;
        pairedCaptureButton.setText(busy ? "保存中..." : "同时拍照");
        pairedCaptureButton.setEnabled(!busy && cameraRunning && firstFrameReceived);
    }

    private void setControlState(boolean ready) {
        if (cameraControlButton != null) {
            cameraControlButton.setEnabled(true);
            cameraControlButton.setText((cameraRunning || cameraStarting) ? "停止摄像头" : "启动摄像头");
            cameraControlButton.setBackgroundTintList(ColorStateList.valueOf(
                    Color.parseColor((cameraRunning || cameraStarting) ? "#C0392B" : "#1976D2")));
        }
        if (captureButton != null) {
            boolean enabled = ready && cameraRunning && firstFrameReceived;
            captureButton.setEnabled(enabled);
            captureButton.setAlpha(enabled ? 1.0f : 0.45f);
        }
        if (pairedCaptureButton != null && !pairedCaptureRunning) {
            boolean enabled = ready && cameraRunning && firstFrameReceived;
            pairedCaptureButton.setEnabled(enabled);
            pairedCaptureButton.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    private void stopCamera() {
        mainHandler.removeCallbacks(firstFrameTimeout);
        cameraStarting = false;
        cameraRunning = false;
        firstFrameReceived = false;
        pairedCaptureRunning = false;
        cameraFrameManager.disconnect();
        closeCameraResources();
        stopCameraThread();
        if (cameraBlackMask != null) cameraBlackMask.setVisibility(View.VISIBLE);
        showStatus("● 摄像头已停止");
        updateMainCameraStatus(false, "摄像头已停止");
        setControlState(false);
    }

    private synchronized void closeCameraResources() {
        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            try { session.stopRepeating(); } catch (Throwable ignored) { }
            try { session.close(); } catch (Throwable ignored) { }
        }
        CameraDevice device = cameraDevice;
        cameraDevice = null;
        if (device != null) {
            try { device.close(); } catch (Throwable ignored) { }
        }
        Surface surface = previewSurface;
        previewSurface = null;
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) { }
        }
        previewRequestBuilder = null;
    }

    private void failCamera(String message) {
        Log.e(TAG, message);
        cameraStarting = false;
        cameraRunning = false;
        firstFrameReceived = false;
        cameraFrameManager.disconnect();
        closeCameraResources();
        postUi(() -> {
            mainHandler.removeCallbacks(firstFrameTimeout);
            if (!isAdded()) return;
            if (cameraBlackMask != null) cameraBlackMask.setVisibility(View.VISIBLE);
            showStatus(message);
            updateMainCameraStatus(false, message);
            setControlState(false);
        });
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("WG-MIPI-Camera2");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        HandlerThread thread = cameraThread;
        cameraThread = null;
        cameraHandler = null;
        if (thread != null) thread.quitSafely();
    }

    private void updateMainCameraStatus(boolean connected, String message) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateCameraStatus(connected, message);
        }
    }

    private void postUi(Runnable runnable) {
        mainHandler.post(() -> {
            if (getView() != null) runnable.run();
        });
    }

    private void showStatus(String message) {
        if (cameraStatusText != null) cameraStatusText.setText(message);
    }

    private String safeId(String id) { return id == null ? "--" : id; }

    private String safeMessage(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }

    @Override
    public void onDestroyView() {
        mainHandler.removeCallbacks(firstFrameTimeout);
        if (autoCaptureCheck != null) {
            mainHandler.removeCallbacks(autoCaptureCheck);
            autoCaptureCheck = null;
        }
        cameraStarting = false;
        cameraRunning = false;
        cameraFrameManager.disconnect();
        closeCameraResources();
        stopCameraThread();
        cameraTextureView = null;
        cameraStatusText = null;
        captureButton = null;
        pairedCaptureButton = null;
        cameraControlButton = null;
        cameraBlackMask = null;
        super.onDestroyView();
    }

    private static final class PairCaptureResult {
        boolean cameraDone;
        boolean thermalDone;
        boolean cameraSuccess;
        boolean thermalSuccess;
        String cameraMessage = "--";
        String thermalMessage = "--";
        synchronized void finishCamera(boolean success, String message) {
            cameraDone = true; cameraSuccess = success; cameraMessage = message == null ? "--" : message;
        }
        synchronized void finishThermal(boolean success, String message) {
            thermalDone = true; thermalSuccess = success; thermalMessage = message == null ? "--" : message;
        }
        synchronized boolean isComplete() { return cameraDone && thermalDone; }
    }
}
