package com.weiguang.drcontroller.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.radar.RadarReading;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 毫米波雷达独立详情页：真实数据、距离位置和最近60秒趋势。 */
public class RadarDetailFragment extends Fragment {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private TextView connectionTitle, deviceName, targetValue, distanceValue;
    private TextView breathValue, heartValue, qualityValue, timeValue;
    private ProgressBar distanceBar;
    private RadarTrendView trendView;
    private RadarPhaseView phaseView;
    private TextView phaseStatus;
    private long lastTrendSampleTime;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            renderLatest();
            handler.postDelayed(this, 100L);
        }
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_radar_detail, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        connectionTitle = view.findViewById(R.id.radarConnectionTitle);
        deviceName = view.findViewById(R.id.radarDeviceName);
        targetValue = view.findViewById(R.id.radarTargetValue);
        distanceValue = view.findViewById(R.id.radarDistanceValue);
        breathValue = view.findViewById(R.id.radarBreathValue);
        heartValue = view.findViewById(R.id.radarHeartValue);
        qualityValue = view.findViewById(R.id.radarQualityValue);
        timeValue = view.findViewById(R.id.radarTimeValue);
        distanceBar = view.findViewById(R.id.radarDistanceBar);
        trendView = view.findViewById(R.id.radarTrendView);
        phaseView = view.findViewById(R.id.radarPhaseView);
        phaseStatus = view.findViewById(R.id.radarPhaseStatus);

        view.findViewById(R.id.radarConnectButton).setOnClickListener(v -> {
            MainActivity activity = activity();
            if (activity != null) activity.connectRadarByCommand();
        });
        view.findViewById(R.id.saveRadarSnapshotButton).setOnClickListener(v -> {
            MainActivity activity = activity();
            if (activity == null) return;
            String caseId = activity.saveExposurePreparation();
            Toast.makeText(requireContext(), "雷达数据已保存到案例 " + caseId, Toast.LENGTH_LONG).show();
        });
        renderLatest();
    }

    @Override public void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override public void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void renderLatest() {
        MainActivity activity = activity();
        if (activity == null || connectionTitle == null) return;
        RadarReading reading = activity.getLatestRadarReading();
        boolean connected = reading.isConnected();
        connectionTitle.setText(connected ? "● 毫米波雷达已连接" : "● 毫米波雷达待连接");
        connectionTitle.setTextColor(Color.parseColor(connected ? "#00E58B" : "#FFAA00"));
        deviceName.setText(reading.getDeviceName());
        targetValue.setText("目标\n" + reading.getTargetStatus());
        distanceValue.setText("距离\n" + reading.getDistance());
        breathValue.setText("呼吸\n" + reading.getBreathRate());
        heartValue.setText("心率\n" + reading.getHeartRate());

        float distance = number(reading.getDistance());
        float breath = number(reading.getBreathRate());
        float heart = number(reading.getHeartRate());
        distanceBar.setProgress(Float.isNaN(distance) ? 0 : Math.round(Math.min(150f, distance)));
        qualityValue.setText("数据质量：" + quality(connected, distance, breath, heart));
        timeValue.setText("更新时间：" + (reading.getDataTimeMillis() > 0
                ? timeFormat.format(new Date(reading.getDataTimeMillis())) : "--"));
        long now = System.currentTimeMillis();
        if (now - lastTrendSampleTime >= 1000L) {
            lastTrendSampleTime = now;
            trendView.addSample(distance, breath, heart);
        }
        if (reading.hasPhaseData()) {
            phaseView.add(reading.getTotalPhase(), reading.getBreathPhase(), reading.getHeartPhase());
            phaseStatus.setText("实时 0x0A13 数据");
            phaseStatus.setTextColor(Color.parseColor("#00E58B"));
        } else {
            phaseStatus.setText("等待 0x0A13 相位帧");
            phaseStatus.setTextColor(Color.parseColor("#FFD36A"));
        }
    }

    private String quality(boolean connected, float distance, float breath, float heart) {
        if (!connected) return "雷达未连接";
        if (Float.isNaN(distance)) return "正在建立距离数据";
        if (distance < 40f) return "距离过近";
        if (distance > 150f) return "距离过远";
        if (Float.isNaN(breath) || Float.isNaN(heart)) return "生命体征建立中";
        return "有效 · 目标位于测量范围";
    }

    private float number(String text) {
        if (text == null || text.contains("--")) return Float.NaN;
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) return Float.NaN;
        try { return Float.parseFloat(matcher.group()); }
        catch (NumberFormatException ignored) { return Float.NaN; }
    }

    private MainActivity activity() {
        return getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
    }
}
