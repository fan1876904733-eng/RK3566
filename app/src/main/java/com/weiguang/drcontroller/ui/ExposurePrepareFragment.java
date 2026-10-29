package com.weiguang.drcontroller.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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
    private boolean voltagePressed;

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

        exposureButton.setOnTouchListener((v, event) -> handleExposureTouch(event));
        refreshXraySequenceStatus();
        updateSelectionText();
    }

    @Override public void onPause() {
        cancelHold();
        super.onPause();
    }

    private boolean handleExposureTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            boolean xray = xrayCheck != null && xrayCheck.isChecked();
            if (xray) {
                Xray433Controller.SendResult result = xrayController.pressTubeVoltage();
                voltagePressed = result.success;
                showXrayResult(result);
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            exposureButton.performClick();
            finishMomentaryCapture();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            releaseVoltageOnly();
            resetExposureButton();
            return true;
        }
        return true;
    }

    private void finishMomentaryCapture() {
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
            if (!voltagePressed) {
                exposureResultText.setText("未开始曝光：请先开启总开关，再按住曝光按钮");
                resetExposureButton();
                return;
            }
            String caseId = activity.saveExposurePreparation();
            Xray433Controller.SendResult result = xrayController.releaseTubeVoltage();
            voltagePressed = false;
            exposureResultText.setText(result.message + "\n准备记录：" + caseId);
            refreshXraySequenceStatus();
        } else {
            exposureResultText.setText("正在启动所选模块…");
        }
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

    private void cancelHold() {
        releaseVoltageOnly();
        resetExposureButton();
    }

    private void releaseVoltageOnly() {
        if (voltagePressed && xrayController != null) xrayController.releaseTubeVoltage();
        voltagePressed = false;
    }

    private void resetExposureButton() {
        updateSelectionText();
    }

    private void updateSelectionText() {
        if (cameraCheck == null || thermalCheck == null || xrayCheck == null) return;
        boolean camera = cameraCheck.isChecked();
        boolean thermal = thermalCheck.isChecked();
        boolean xray = xrayCheck.isChecked();
        boolean hasSelection = camera || thermal || xray;
        if (xrayMasterSwitch != null) xrayMasterSwitch.setVisibility(xray ? View.VISIBLE : View.GONE);
        if (exposureButton != null) {
            exposureButton.setEnabled(hasSelection);
            exposureButton.setText(xray ? "长按曝光" : "开始拍摄");
        }
    }

    public void setSelectedModules(boolean camera, boolean thermal, boolean xray) {
        if (cameraCheck == null || thermalCheck == null || xrayCheck == null) return;
        cameraCheck.setChecked(camera);
        thermalCheck.setChecked(thermal);
        xrayCheck.setChecked(xray);
        updateSelectionText();
    }
}
