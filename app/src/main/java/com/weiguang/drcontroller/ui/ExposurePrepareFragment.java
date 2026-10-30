package com.weiguang.drcontroller.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.xray.Xray433Controller;

/** 简化的曝光准备台。通过 K11C GPIO3_D2/D3 控制 TXS01 双通道发射模块。 */
public class ExposurePrepareFragment extends Fragment {

    private TextView exposureResultText;
    private Button exposureButton;
    private CheckBox cameraCheck;
    private CheckBox thermalCheck;
    private CheckBox xrayCheck;
    private Xray433Controller xrayController;
    private Switch xrayMasterSwitch;
    private boolean syncingMasterSwitch;
    private View exposureDurationPanel;
    private TextView exposureDurationText;
    private ImageButton decreaseExposureButton;
    private ImageButton increaseExposureButton;
    private final Handler exposureHandler = new Handler(Looper.getMainLooper());
    private static final long MIN_EXPOSURE_MS = 300L;
    private static final long MAX_EXPOSURE_MS = 700L;
    private static final long EXPOSURE_STEP_MS = 50L;
    private long exposureDurationMs = 500L;
    private boolean exposureRunning;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_exposure_prepare, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        exposureResultText = view.findViewById(R.id.exposureResultText);
        exposureButton = view.findViewById(R.id.exposureButton);
        cameraCheck = view.findViewById(R.id.selectCameraCheck);
        thermalCheck = view.findViewById(R.id.selectThermalCheck);
        xrayCheck = view.findViewById(R.id.selectXrayCheck);
        xrayController = Xray433Controller.getInstance(requireContext());
        xrayMasterSwitch = view.findViewById(R.id.xrayMasterSwitch);
        exposureDurationPanel = view.findViewById(R.id.exposureDurationPanel);
        exposureDurationText = view.findViewById(R.id.exposureDurationText);
        decreaseExposureButton = view.findViewById(R.id.decreaseExposureButton);
        increaseExposureButton = view.findViewById(R.id.increaseExposureButton);
        android.widget.CompoundButton.OnCheckedChangeListener selectionListener = (button, checked) -> {
            if (button == xrayCheck && !checked
                    && xrayController.getState() != Xray433Controller.State.MASTER_OFF) {
                showXrayResult(xrayController.turnMasterOff());
            }
            updateSelectionText();
        };
        cameraCheck.setOnCheckedChangeListener(selectionListener);
        thermalCheck.setOnCheckedChangeListener(selectionListener);
        xrayCheck.setOnCheckedChangeListener(selectionListener);

        xrayMasterSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingMasterSwitch) return;
            showXrayResult(checked ? xrayController.turnMasterOn() : xrayController.turnMasterOff());
        });

        decreaseExposureButton.setOnClickListener(v -> adjustExposureDuration(-EXPOSURE_STEP_MS));
        increaseExposureButton.setOnClickListener(v -> adjustExposureDuration(EXPOSURE_STEP_MS));
        exposureButton.setOnClickListener(v -> startSelectedCapture());
        updateExposureDurationUi();
        refreshXraySequenceStatus();
        updateSelectionText();
    }

    @Override public void onPause() {
        cancelExposure();
        super.onPause();
    }

    private void startSelectedCapture() {
        if (exposureRunning) return;
        MainActivity activity = activity();
        if (activity == null) return;
        boolean camera = cameraCheck != null && cameraCheck.isChecked();
        boolean thermal = thermalCheck != null && thermalCheck.isChecked();
        boolean xray = xrayCheck != null && xrayCheck.isChecked();
        if (!camera && !thermal && !xray) {
            exposureResultText.setText("请至少选择一个采集模块");
            return;
        }
        if (xray) {
            if (xrayController.getState() != Xray433Controller.State.MASTER_ON) {
                exposureResultText.setText("请先开启射线源总开关");
                return;
            }
            activity.saveExposurePreparation();
            Xray433Controller.SendResult result = xrayController.startTimedExposureMillis(exposureDurationMs);
            if (!result.success) {
                showXrayResult(result);
                return;
            }
            exposureRunning = true;
            updateSelectionText();
            exposureResultText.setText("正在曝光 " + formatDuration(exposureDurationMs));
            exposureHandler.postDelayed(() -> finishTimedCapture(camera, thermal),
                    exposureDurationMs);
        } else {
            exposureResultText.setText("正在启动所选模块…");
            captureSelectedModules(activity, camera, thermal);
        }
    }

    private void finishTimedCapture(boolean camera, boolean thermal) {
        MainActivity activity = activity();
        exposureRunning = false;
        refreshXraySequenceStatus();
        updateSelectionText();
        if (activity == null) return;
        exposureResultText.setText("曝光完成");
        captureSelectedModules(activity, camera, thermal);
    }

    private void captureSelectedModules(MainActivity activity, boolean camera, boolean thermal) {
        if (camera && thermal) activity.capturePairedPhotoByCommand();
        else if (camera) activity.captureCameraOnlyFromExposure();
        else if (thermal) activity.captureThermalOnlyFromExposure();
    }

    private void showXrayResult(Xray433Controller.SendResult result) {
        if (exposureResultText != null) exposureResultText.setText(result.message);
        refreshXraySequenceStatus();
    }

    private void refreshXraySequenceStatus() {
        if (xrayController == null) return;
        Xray433Controller.State state = xrayController.getState();
        if (xrayMasterSwitch != null) {
            boolean shouldBeOn = state == Xray433Controller.State.MASTER_ON
                    || state == Xray433Controller.State.EXPOSING;
            if (xrayMasterSwitch.isChecked() != shouldBeOn) {
                syncingMasterSwitch = true;
                xrayMasterSwitch.setChecked(shouldBeOn);
                syncingMasterSwitch = false;
            }
        }
    }

    private MainActivity activity() {
        return getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
    }

    private void cancelExposure() {
        exposureHandler.removeCallbacksAndMessages(null);
        if (exposureRunning && xrayController != null) xrayController.releaseTubeVoltage();
        exposureRunning = false;
    }

    private void adjustExposureDuration(long deltaMs) {
        exposureDurationMs = Math.max(MIN_EXPOSURE_MS,
                Math.min(MAX_EXPOSURE_MS, exposureDurationMs + deltaMs));
        updateExposureDurationUi();
    }

    private void updateExposureDurationUi() {
        if (exposureDurationText != null) exposureDurationText.setText(formatDuration(exposureDurationMs));
        if (decreaseExposureButton != null) {
            decreaseExposureButton.setEnabled(!exposureRunning && exposureDurationMs > MIN_EXPOSURE_MS);
        }
        if (increaseExposureButton != null) {
            increaseExposureButton.setEnabled(!exposureRunning && exposureDurationMs < MAX_EXPOSURE_MS);
        }
    }

    private String formatDuration(long millis) {
        return String.format(java.util.Locale.CHINA, "%.2f s", millis / 1000.0);
    }

    private void updateSelectionText() {
        if (cameraCheck == null || thermalCheck == null || xrayCheck == null) return;
        boolean camera = cameraCheck.isChecked();
        boolean thermal = thermalCheck.isChecked();
        boolean xray = xrayCheck.isChecked();
        boolean hasSelection = camera || thermal || xray;
        if (xrayMasterSwitch != null) xrayMasterSwitch.setVisibility(xray ? View.VISIBLE : View.GONE);
        if (exposureDurationPanel != null) exposureDurationPanel.setVisibility(xray ? View.VISIBLE : View.GONE);
        if (xrayMasterSwitch != null) xrayMasterSwitch.setEnabled(!exposureRunning);
        if (exposureButton != null) {
            exposureButton.setEnabled(hasSelection && !exposureRunning);
            exposureButton.setText(xray ? "开始曝光" : "开始拍摄");
        }
        updateExposureDurationUi();
    }

    public void setSelectedModules(boolean camera, boolean thermal, boolean xray) {
        if (cameraCheck == null || thermalCheck == null || xrayCheck == null) return;
        cameraCheck.setChecked(camera);
        thermalCheck.setChecked(thermal);
        xrayCheck.setChecked(xray);
        updateSelectionText();
    }
}
