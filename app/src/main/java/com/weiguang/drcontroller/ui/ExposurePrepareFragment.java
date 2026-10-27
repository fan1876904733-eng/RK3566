package com.weiguang.drcontroller.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.radar.RadarReading;
import com.weiguang.drcontroller.hardware.xray.Xray433Controller;

/** 简化的曝光准备台。实际 433 MHz 控制接入前，只保存准备数据，不发送曝光命令。 */
public class ExposurePrepareFragment extends Fragment {

    private static final long HOLD_DURATION_MS = 2000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView deviceStatusText;
    private TextView radarStatusText;
    private TextView exposureResultText;
    private Button exposureButton;
    private CheckBox cameraCheck;
    private CheckBox thermalCheck;
    private CheckBox xrayCheck;
    private TextView selectionSummaryText;
    private TextView xraySequenceStatusText;
    private Xray433Controller xrayController;
    private boolean holdCompleted;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable holdTask = () -> {
        holdCompleted = true;
        if (exposureButton != null) exposureButton.setText("松开执行");
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_exposure_prepare, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        deviceStatusText = view.findViewById(R.id.deviceStatusText);
        radarStatusText = view.findViewById(R.id.radarStatusText);
        exposureResultText = view.findViewById(R.id.exposureResultText);
        exposureButton = view.findViewById(R.id.exposureButton);
        cameraCheck = view.findViewById(R.id.selectCameraCheck);
        thermalCheck = view.findViewById(R.id.selectThermalCheck);
        xrayCheck = view.findViewById(R.id.selectXrayCheck);
        selectionSummaryText = view.findViewById(R.id.selectionSummaryText);
        xraySequenceStatusText = view.findViewById(R.id.xraySequenceStatusText);
        xrayController = Xray433Controller.getInstance(requireContext());
        android.widget.CompoundButton.OnCheckedChangeListener selectionListener =
                (button, checked) -> updateSelectionText();
        cameraCheck.setOnCheckedChangeListener(selectionListener);
        thermalCheck.setOnCheckedChangeListener(selectionListener);
        xrayCheck.setOnCheckedChangeListener(selectionListener);

        view.findViewById(R.id.cameraControlButton).setOnClickListener(v -> openCamera());
        view.findViewById(R.id.cameraPreviewEntry).setOnClickListener(v -> openCamera());
        view.findViewById(R.id.thermalControlButton).setOnClickListener(v -> openThermal());
        view.findViewById(R.id.thermalPreviewEntry).setOnClickListener(v -> openThermal());
        view.findViewById(R.id.xrayControlButton).setOnClickListener(v -> Toast.makeText(
                requireContext(), "433 MHz 控制接口尚未接入，暂不能控制 X-Ray 开关", Toast.LENGTH_LONG).show());
        view.findViewById(R.id.enableAllButton).setOnClickListener(v -> {
            refreshStatus();
            Toast.makeText(requireContext(), "设备状态已检查；未接入的控制通道不会发送命令", Toast.LENGTH_LONG).show();
        });
        view.findViewById(R.id.tubeCurrentButton).setOnClickListener(v ->
                showXrayResult(xrayController.enableTubeCurrent()));
        view.findViewById(R.id.tubeVoltageButton).setOnClickListener(v ->
                showXrayResult(xrayController.enableTubeVoltage()));
        view.findViewById(R.id.xrayStopButton).setOnClickListener(v ->
                showXrayResult(xrayController.stopAll()));

        exposureButton.setOnTouchListener((v, event) -> handleExposureTouch(event));
        refreshStatus();
        updateSelectionText();
    }

    @Override public void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override public void onPause() {
        handler.removeCallbacks(refreshTask);
        cancelHold();
        super.onPause();
    }

    private boolean handleExposureTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            holdCompleted = false;
            exposureButton.setText("继续按住 2 秒…");
            handler.postDelayed(holdTask, HOLD_DURATION_MS);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(holdTask);
            exposureButton.performClick();
            if (holdCompleted) savePreparation();
            else resetExposureButton();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            cancelHold();
            return true;
        }
        return true;
    }

    private void savePreparation() {
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
            String caseId = activity.saveExposurePreparation();
            Xray433Controller.SendResult result = xrayController.startExposure();
            exposureResultText.setText(result.message + "\n准备记录：" + caseId);
            refreshXraySequenceStatus();
        } else {
            exposureResultText.setText("正在启动所选模块…");
        }
        if (camera && thermal) activity.capturePairedPhotoByCommand();
        else if (camera) activity.captureCameraOnlyFromExposure();
        else if (thermal) activity.captureThermalOnlyFromExposure();
    }

    private void refreshStatus() {
        MainActivity activity = activity();
        if (activity == null || deviceStatusText == null || radarStatusText == null) return;
        deviceStatusText.setText("摄像头：" + state(activity.isCameraConnected())
                + "    热成像：" + state(activity.isThermalConnected())
                + "    X-Ray 控制：待接入");
        RadarReading reading = activity.getLatestRadarReading();
        radarStatusText.setText("毫米波雷达：" + (reading.isConnected() ? "已连接" : "待连接")
                + "\n目标：" + reading.getTargetStatus()
                + "    距离：" + reading.getDistance()
                + "\n呼吸：" + reading.getBreathRate()
                + "    心率：" + reading.getHeartRate());
        refreshXraySequenceStatus();
    }

    private void showXrayResult(Xray433Controller.SendResult result) {
        if (exposureResultText != null) exposureResultText.setText(result.message);
        refreshXraySequenceStatus();
        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show();
    }

    private void refreshXraySequenceStatus() {
        if (xrayController == null || xraySequenceStatusText == null) return;
        Xray433Controller.State state = xrayController.getState();
        String channels;
        if (state == Xray433Controller.State.VOLTAGE_ON) channels = "管电流开 · 管电压开";
        else if (state == Xray433Controller.State.CURRENT_ON) channels = "管电流开 · 管电压关";
        else if (state == Xray433Controller.State.FAULT) channels = "异常，请检查并停止";
        else channels = "管电流关 · 管电压关";
        xraySequenceStatusText.setText((xrayController.isHardwareReady() ? "433 MHz 实机" : "433 MHz 安全演练")
                + "：" + channels);
    }

    private String state(boolean connected) { return connected ? "已连接" : "待连接"; }

    private void openCamera() {
        MainActivity activity = activity();
        if (activity != null) activity.openCameraPageByCommand();
    }

    private void openThermal() {
        MainActivity activity = activity();
        if (activity != null) activity.openThermalPageByCommand();
    }

    private MainActivity activity() {
        return getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
    }

    private void cancelHold() {
        handler.removeCallbacks(holdTask);
        resetExposureButton();
    }

    private void resetExposureButton() {
        holdCompleted = false;
        updateSelectionText();
    }

    private void updateSelectionText() {
        if (cameraCheck == null || thermalCheck == null || xrayCheck == null) return;
        boolean camera = cameraCheck.isChecked();
        boolean thermal = thermalCheck.isChecked();
        boolean xray = xrayCheck.isChecked();
        StringBuilder modules = new StringBuilder();
        if (camera) modules.append("可见光拍照");
        if (thermal) appendModule(modules, "热成像拍照");
        if (xray) appendModule(modules, "开启 X-Ray 射线源");
        String action = modules.length() == 0 ? "尚未选择采集模块" : modules.toString();
        if (selectionSummaryText != null) selectionSummaryText.setText("本次操作：" + action);
        if (exposureButton != null) {
            exposureButton.setEnabled(modules.length() > 0);
            if (!camera && !thermal && xray) exposureButton.setText("长按 2 秒开启 X-Ray 射线源");
            else if (camera && !thermal && !xray) exposureButton.setText("长按 2 秒拍摄可见光");
            else if (!camera && thermal && !xray) exposureButton.setText("长按 2 秒拍摄热成像");
            else exposureButton.setText("长按 2 秒开始组合采集");
        }
    }

    private void appendModule(StringBuilder builder, String module) {
        if (builder.length() > 0) builder.append(" + ");
        builder.append(module);
    }
}
