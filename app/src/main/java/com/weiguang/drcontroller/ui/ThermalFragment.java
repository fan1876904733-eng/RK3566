package com.weiguang.drcontroller.ui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;
import com.weiguang.drcontroller.hardware.storage.ImageRepository;
import com.weiguang.drcontroller.hardware.thermal.HikThermalSdkBridge;
import com.weiguang.drcontroller.hardware.thermal.ThermalFrameManager;

/** DS-2TM01-3XF/TB preview through HCUSBSDK, not Android Camera2. */
public class ThermalFragment extends Fragment {
    private static final String TAG = "WG-THERMAL";
    private static final String ACTION_USB_PERMISSION =
            "com.weiguang.drcontroller.HIK_USB_PERMISSION";
    private static final int HIK_VID = 0x2bdf;
    private static final int HIK_PID = 0x0101;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThermalFrameManager frameManager = ThermalFrameManager.getInstance();
    private final HikThermalSdkBridge.Listener sdkListener = new HikThermalSdkBridge.Listener() {
        @Override public void onStatus(String status) {
            mainHandler.post(() -> {
                if (!isAdded()) return;
                statusText.setText(status);
                if (status.contains("正常")) {
                    starting = false;
                    running = true;
                    frameManager.setConnectionState(true, "热成像");
                    updateMainStatus(true, status);
                    if (captureButton != null) {
                        captureButton.setEnabled(true);
                        captureButton.setText("拍照");
                    }
                    // 启动"无帧自愈"检查：状态正常但画面迟迟不来（USB栈异常/流中断）时自动重连
                    lastFrameArrivalMs = System.currentTimeMillis();
                    armNoFrameCheck();
                }
            });
        }

        @Override public void onFrame(Bitmap bitmap) {
            long now = System.currentTimeMillis();
            lastFrameArrivalMs = now;
            if (now - lastDisplayedMs < 66) {
                // 节流跳过。注意：绝不能 recycle 该帧——单例 SDK 会把同一帧广播给
                // 所有监听器，这里回收会令其他监听器/帧管理器拿到已回收的黑图。
                return;
            }
            lastDisplayedMs = now;
            mainHandler.post(() -> displayFrame(bitmap));
        }

        @Override public void onError(String error) {
            mainHandler.post(() -> fail("热成像连接失败：" + error));
        }
    };
    private ImageView imageView;
    private View blackMask;
    private TextView statusText;
    private TextView deviceText;
    private Button controlButton;
    private Button captureButton;
    private HikThermalSdkBridge sdkBridge;
    private Bitmap displayedBitmap;
    private boolean receiverRegistered;
    private boolean starting;
    private boolean running;
    private boolean retryScheduled;
    private long lastDisplayedMs;
    private long lastFrameArrivalMs;
    private int noFrameRetries;
    private boolean autoCaptureOnFirstFrame;

    /**
     * 无帧自愈：SDK 状态"正常"但若干秒内收不到任何帧（RK3566 USB 主机栈偶发异常时，
     * USB_StartStreamCallback 返回成功但流回调中断），自动 stop→start 重连。
     */
    private final Runnable noFrameCheck = new Runnable() {
        @Override public void run() {
            if (!isAdded() || !running || starting) return;
            long idle = System.currentTimeMillis() - lastFrameArrivalMs;
            if (idle < 4000) {
                armNoFrameCheck(); // 有帧在更新，继续观察
                return;
            }
            if (noFrameRetries >= 2) {
                Log.e(TAG, "热成像持续无帧，多次自动重连未恢复");
                if (statusText != null) {
                    statusText.setText("● 热成像画面异常：多次自动重连未恢复，请重启设备或检查USB连接");
                }
                return;
            }
            noFrameRetries++;
            Log.w(TAG, "热成像无帧 " + (idle / 1000) + " 秒，自动重连（第" + noFrameRetries + "次）");
            stopThermal("热成像无画面，正在自动重连...");
            mainHandler.postDelayed(() -> {
                if (isAdded() && !starting && !running) requestPermissionAndStart();
            }, 1500L);
        }
    };

    private void armNoFrameCheck() {
        mainHandler.removeCallbacks(noFrameCheck);
        mainHandler.postDelayed(noFrameCheck, 5000L);
    }

    public ThermalFragment() {
        super(R.layout.fragment_thermal);
    }

    public ThermalFragment(boolean autoCaptureOnFirstFrame) {
        super(R.layout.fragment_thermal);
        this.autoCaptureOnFirstFrame = autoCaptureOnFirstFrame;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        imageView = view.findViewById(R.id.thermalImageView);
        blackMask = view.findViewById(R.id.thermalBlackMask);
        statusText = view.findViewById(R.id.thermalStatusText);
        deviceText = view.findViewById(R.id.thermalDeviceText);
        controlButton = view.findViewById(R.id.thermalControlButton);
        controlButton.setOnClickListener(v -> {
            if (starting || running) stopThermal("热成像已停止");
            else requestPermissionAndStart();
        });
        captureButton = view.findViewById(R.id.thermalCaptureButton);
        captureButton.setOnClickListener(v -> takeThermalPhoto());
        showStopped("热成像待连接");
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            // 页面被隐藏（切到其他页面）：注销帧监听。
            // 避免多个页面实例共享单例 SDK 的同一帧流时互相 recycle，
            // 也避免隐藏页持续刷新浪费 CPU。
            if (sdkBridge != null) sdkBridge.removeListener(sdkListener);
        } else {
            // 重新显示：SDK 若仍在后台运行（hide 不停止 SDK），恢复监听并直接显示；
            // 若已停止则重新发起连接。
            HikThermalSdkBridge bridge = sdkBridge;
            if (bridge == null) {
                requestPermissionAndStart();
                return;
            }
            if (bridge.isRunning()) {
                if (!running) {
                    running = true;
                    frameManager.setConnectionState(true, "热成像");
                    updateMainStatus(true, "热成像");
                    statusText.setText("● 热成像画面正常");
                    if (captureButton != null) {
                        captureButton.setEnabled(true);
                        captureButton.setText("拍照");
                    }
                }
                bridge.addListener(sdkListener);
                lastFrameArrivalMs = System.currentTimeMillis();
                armNoFrameCheck();
            } else {
                requestPermissionAndStart();
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        registerUsbReceiver();
        requestPermissionAndStart();
    }

    @Override
    public void onStop() {
        stopThermal("热成像已停止", false);
        unregisterUsbReceiver();
        super.onStop();
    }

    private void requestPermissionAndStart() {
        if (!isAdded() || starting || running) return;
        UsbManager manager = (UsbManager) requireContext().getSystemService(Context.USB_SERVICE);
        UsbDevice device = findHikDevice(manager);
        if (device == null) {
            fail("未发现热成像设备，请检查连接");
            return;
        }
        deviceText.setText("热成像设备已识别");
        if (!manager.hasPermission(device)) {
            // 直接触发系统 USB 授权弹窗（"允许微光智造访问 hikcamera 吗？"），
            // 不再叠加应用内确认框，避免用户每次要点两次。
            // 系统弹窗文案由系统生成、不可定制，接受系统原文案。
            if (starting) return;
            starting = true;
            statusText.setText("等待USB访问授权...");
            requestThermalUsbPermission(device);
            return;
        }
        startVendorSdk();
    }

    /** 触发系统 USB 授权（授权过一次后系统不再弹窗）。 */
    private void requestThermalUsbPermission(UsbDevice device) {
        if (!isAdded()) return;
        UsbManager manager = (UsbManager) requireContext().getSystemService(Context.USB_SERVICE);
        if (manager == null || manager.hasPermission(device)) {
            startVendorSdk();
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(requireContext().getPackageName());
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        // UsbManager adds EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED to this callback.
        // Android 12+ therefore requires a mutable PendingIntent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(requireContext(), 0, intent,
                pendingFlags);
        manager.requestPermission(device, pending);
    }

    private void startVendorSdk() {
        if (!isAdded() || running) return;
        starting = true;
        blackMask.setVisibility(View.VISIBLE);
        controlButton.setText("停止热成像");
        // 全局单例 SDK：整个进程只有一个实例连接热成像设备，
        // 摄像头页后台采集与本页显示共用同一连接，互不抢占。
        sdkBridge = HikThermalSdkBridge.getInstance(requireContext());
        sdkBridge.addListener(sdkListener);
        if (sdkBridge.isRunning()) {
            // SDK 已在后台运行（可能由摄像头页后台采集启动），直接恢复显示状态
            starting = false;
            running = true;
            frameManager.setConnectionState(true, "热成像");
            updateMainStatus(true, "热成像");
            statusText.setText("● 热成像画面正常");
            if (captureButton != null) {
                captureButton.setEnabled(true);
                captureButton.setText("拍照");
            }
            lastFrameArrivalMs = System.currentTimeMillis();
            armNoFrameCheck();
            return;
        }
        sdkBridge.start();
    }

    private void displayFrame(Bitmap bitmap) {
        // 该帧可能同时被其他监听器/帧管理器使用，一律不主动 recycle，
        // 交给 GC 回收，避免"显示中的 Bitmap 被回收"导致画面变黑。
        if (!isAdded() || !running || imageView == null || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        displayedBitmap = bitmap;
        imageView.setImageBitmap(bitmap);
        blackMask.setVisibility(View.GONE);
        statusText.setText("● 热成像画面正常");
        if (autoCaptureOnFirstFrame) {
            autoCaptureOnFirstFrame = false;
            mainHandler.postDelayed(this::takeThermalPhoto, 300L);
        }
    }

    private void stopThermal(String message) {
        stopThermal(message, true);
    }

    private void stopThermal(String message, boolean updateActivityStatus) {
        starting = false;
        running = false;
        retryScheduled = false;
        mainHandler.removeCallbacksAndMessages(null);
        HikThermalSdkBridge bridge = sdkBridge;
        sdkBridge = null;
        if (bridge != null) {
            bridge.removeListener(sdkListener);
            bridge.stop();
        }
        frameManager.disconnect();
        if (blackMask != null) blackMask.setVisibility(View.VISIBLE);
        if (controlButton != null) controlButton.setText("启动热成像");
        if (captureButton != null) {
            captureButton.setEnabled(false);
            captureButton.setText("拍照");
        }
        if (statusText != null) statusText.setText(message);
        if (updateActivityStatus) updateMainStatus(false, message);
    }

    private void fail(String message) {
        Log.e(TAG, message);
        stopThermal(message);
        if (isAdded() && deviceText != null && findHikDevice((UsbManager) requireContext()
                .getSystemService(Context.USB_SERVICE)) == null) deviceText.setText("等待设备连接");
        // 瞬时失败（SDK初始化抖动/设备枚举延迟）时自动重试一次，降低"连不上"概率
        if (isAdded() && !retryScheduled && !starting && !running) {
            retryScheduled = true;
            mainHandler.postDelayed(() -> {
                retryScheduled = false;
                if (isAdded() && !starting && !running) {
                    requestPermissionAndStart();
                }
            }, 1200L);
        }
    }

    private void showStopped(String message) {
        statusText.setText(message);
        deviceText.setText("等待设备连接");
        blackMask.setVisibility(View.VISIBLE);
        controlButton.setText("启动热成像");
        if (captureButton != null) {
            captureButton.setEnabled(false);
            captureButton.setText("拍照");
        }
    }

    /** 热成像页拍照：保存当前热成像帧为独立案例（含雷达快照），与摄像头页"同时拍照"互补。 */
    private void takeThermalPhoto() {
        if (!running) {
            Toast.makeText(requireContext(), "请先启动热成像画面", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!frameManager.hasFrame()) {
            Toast.makeText(requireContext(), "热成像画面尚未就绪，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        long captureTimeMillis = System.currentTimeMillis();
        String captureId = ImageRepository.buildCaptureId(captureTimeMillis);
        CaseRepository.activateCase(requireContext(), captureId, captureTimeMillis);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).registerCaseCapture(captureId, captureTimeMillis);
        }
        if (captureButton != null) {
            captureButton.setEnabled(false);
            captureButton.setText("保存中...");
        }
        frameManager.captureLatestFrame(
                requireContext().getApplicationContext(),
                captureId,
                new ThermalFrameManager.CaptureCallback() {
                    @Override public void onSuccess(Uri uri) {
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            if (captureButton != null) {
                                captureButton.setEnabled(running);
                                captureButton.setText("拍照");
                            }
                            Toast.makeText(requireContext(), "热成像图已保存", Toast.LENGTH_LONG).show();
                        });
                    }
                    @Override public void onFailure(String message) {
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            if (captureButton != null) {
                                captureButton.setEnabled(running);
                                captureButton.setText("拍照");
                            }
                            Toast.makeText(requireContext(), "热成像保存失败：" + message, Toast.LENGTH_LONG).show();
                        });
                    }
                }
        );
    }

    private UsbDevice findHikDevice(UsbManager manager) {
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == HIK_VID && device.getProductId() == HIK_PID) return device;
        }
        return null;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(action)) {
                starting = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startVendorSdk();
                else stopThermal("USB访问权限被拒绝，请重新点击\u201c启动热成像\u201d后在系统弹窗中允许");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)
                    && device != null && device.getVendorId() == HIK_VID
                    && device.getProductId() == HIK_PID) {
                stopThermal("热成像设备已断开");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                requestPermissionAndStart();
            }
        }
    };

    private void registerUsbReceiver() {
        if (receiverRegistered || !isAdded()) return;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(
                requireContext(),
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiverRegistered = true;
    }

    private void unregisterUsbReceiver() {
        if (!receiverRegistered || !isAdded()) return;
        try { requireContext().unregisterReceiver(usbReceiver); } catch (Throwable ignored) { }
        receiverRegistered = false;
    }

    private void updateMainStatus(boolean connected, String message) {
        if (getActivity() instanceof MainActivity)
            ((MainActivity) getActivity()).updateThermalStatus(connected, message);
    }
}
