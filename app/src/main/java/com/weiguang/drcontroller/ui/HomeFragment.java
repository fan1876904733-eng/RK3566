package com.weiguang.drcontroller.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.radar.RadarReading;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;
import com.weiguang.drcontroller.hardware.wifi.WifiXrayServer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class HomeFragment extends Fragment {

    private static final String SETTINGS_PREFS = "weiguang_system_settings";
    private static final String KEY_BRIGHTNESS_PERCENT = "brightness_percent";
    private static final int STATUS_OK_COLOR = Color.rgb(0, 255, 136);
    private static final int STATUS_WAIT_COLOR = Color.rgb(255, 170, 0);

    private TextView homeCameraStatusText;
    private TextView homeThermalStatusText;
    private TextView homeRadarStatusText;
    private TextView homeMiniHostStatusText;
    private TextView homeMiniHostIpText;
    private TextView overviewOnlineValue;
    private TextView overviewPendingValue;
    private TextView overviewNetworkValue;
    private TextView networkStatusText;
    private TextView soundValueText;
    private TextView brightnessValueText;
    private TextView bootLockStatusText;
    private SeekBar soundSeekBar;
    private SeekBar brightnessSeekBar;
    private Switch bootLockSwitch;
    private AudioManager audioManager;
    private SharedPreferences preferences;
    private LinearLayout homeHistoryList;
    private View homeHistoryEmpty;

    public HomeFragment() {
        super(R.layout.fragment_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        homeCameraStatusText = view.findViewById(R.id.homeCameraStatusText);
        homeThermalStatusText = view.findViewById(R.id.homeThermalStatusText);
        homeRadarStatusText = view.findViewById(R.id.homeRadarStatusText);
        homeMiniHostStatusText = view.findViewById(R.id.homeMiniHostStatusText);
        homeMiniHostIpText = view.findViewById(R.id.homeMiniHostIpText);
        overviewOnlineValue = view.findViewById(R.id.overviewOnlineValue);
        overviewPendingValue = view.findViewById(R.id.overviewPendingValue);
        overviewNetworkValue = view.findViewById(R.id.overviewNetworkValue);
        networkStatusText = view.findViewById(R.id.networkStatusText);
        soundValueText = view.findViewById(R.id.soundValueText);
        brightnessValueText = view.findViewById(R.id.brightnessValueText);
        bootLockStatusText = view.findViewById(R.id.bootLockStatusText);
        soundSeekBar = view.findViewById(R.id.soundSeekBar);
        brightnessSeekBar = view.findViewById(R.id.brightnessSeekBar);
        bootLockSwitch = view.findViewById(R.id.bootLockSwitch);

        preferences = requireContext().getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE);
        audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);

        // 迷你主机 IP（WiFi 发送端地址）
        if (homeMiniHostIpText != null) {
            homeMiniHostIpText.setText(WifiXrayServer.DEFAULT_MINI_HOST);
        }

        homeHistoryList = view.findViewById(R.id.homeHistoryList);
        homeHistoryEmpty = view.findViewById(R.id.homeHistoryEmpty);

        // 三个设备卡整卡可点击：分别跳转到对应的实时预览页
        view.findViewById(R.id.homeCameraCard).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openCameraPageByCommand();
            }
        });
        view.findViewById(R.id.homeThermalCard).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openThermalPageByCommand();
            }
        });
        view.findViewById(R.id.homeRadarCard).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).connectRadarByCommand();
            }
        });

        // 历史空状态也可点击 → 新建检查
        if (homeHistoryEmpty != null) {
            homeHistoryEmpty.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openCameraPageByCommand();
                }
            });
        }

        // 新建检查：进入摄像头页开始新的影像检测（通栏大按钮）
        view.findViewById(R.id.newCheckHeroButton).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openCameraPageByCommand();
            }
        });

        TextView systemVersionText = view.findViewById(R.id.systemVersionText);
        TextView screenInfoText = view.findViewById(R.id.screenInfoText);
        systemVersionText.setText("微光智造 kc2601");
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenInfoText.setText("屏幕：" + metrics.widthPixels + " × " + metrics.heightPixels);

        configureNetwork(view);        configureSound();
        configureBrightness();
        configureMaintenance(view);
        configureBootLock();
        refreshDeviceStatuses();
        updateNetworkStatus();
        loadHomeHistory();
    }

    /** 首页历史区：读取最近的检测案例，有记录显示列表，无记录显示空状态。 */
    private void loadHomeHistory() {
        if (!isAdded()) return;
        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            final List<CaseRepository.CaseRecord> loaded =
                    CaseRepository.queryCases(appContext, 5);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> renderHomeHistory(loaded));
        }).start();
    }

    private void renderHomeHistory(List<CaseRepository.CaseRecord> records) {
        if (homeHistoryList == null || homeHistoryEmpty == null) return;
        if (records == null || records.isEmpty()) {
            homeHistoryList.removeAllViews();
            homeHistoryList.setVisibility(View.GONE);
            homeHistoryEmpty.setVisibility(View.VISIBLE);
            return;
        }
        homeHistoryEmpty.setVisibility(View.GONE);
        homeHistoryList.setVisibility(View.VISIBLE);
        homeHistoryList.removeAllViews();
        for (CaseRepository.CaseRecord record : records) {
            addHomeHistoryRow(record);
        }
    }

    private void addHomeHistoryRow(CaseRepository.CaseRecord record) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#80142850"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#330096FF"));
        row.setBackground(bg);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(6);
        row.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openCaseDetail(record.getCaseId());
            }
        });
        homeHistoryList.addView(row, rowParams);

        LinearLayout info = new LinearLayout(requireContext());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        row.addView(info, infoParams);

        TextView time = new TextView(requireContext());
        time.setText(formatHomeTime(record.getTimeMillis()));
        time.setTextColor(Color.WHITE);
        time.setTextSize(13f);
        time.setTypeface(time.getTypeface(), android.graphics.Typeface.BOLD);
        info.addView(time);

        TextView summary = new TextView(requireContext());
        summary.setText(buildHomeSummary(record));
        summary.setTextColor(Color.parseColor("#9FB3C1"));
        summary.setTextSize(11f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(4);
        info.addView(summary, summaryParams);

        TextView arrow = new TextView(requireContext());
        arrow.setText("查看 >");
        arrow.setTextColor(Color.parseColor("#00AAFF"));
        arrow.setTextSize(11f);
        row.addView(arrow);
    }

    private String buildHomeSummary(CaseRepository.CaseRecord record) {
        List<String> parts = new ArrayList<>();
        if (record.hasCameraImage()) parts.add("实时图");
        if (record.hasThermalImage()) parts.add("热成像图");
        if (record.hasXrayImage()) parts.add("X-Ray图");
        if (record.getRadarSnapshot() != null) parts.add("毫米波数据");
        if (record.hasAiReport()) parts.add("AI分析");
        return parts.isEmpty() ? "暂无影像内容" : android.text.TextUtils.join(" · ", parts);
    }

    private String formatHomeTime(long timeMillis) {
        if (timeMillis <= 0L) return "时间未知";
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm", Locale.CHINA
        );
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timeMillis));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public void refreshDeviceStatuses() {
        if (!isAdded() || getView() == null) return;
        MainActivity activity = getActivity() instanceof MainActivity
                ? (MainActivity) getActivity() : null;
        if (activity == null) return;

        boolean cameraOk = activity.isCameraConnected();
        boolean thermalOk = activity.isThermalConnected();
        // 真实状态：以最近一次雷达数据解析为准，避免未连接时仍固定显示绿色误导操作者
        boolean radarOk = activity.getLatestRadarReading().isConnected();
        setStatus(homeCameraStatusText, cameraOk, "摄像头");
        setStatus(homeThermalStatusText, thermalOk, "热成像");
        setStatus(homeRadarStatusText, radarOk, "毫米波雷达");

        // 设备运行总览统计
        int online = (cameraOk ? 1 : 0) + (thermalOk ? 1 : 0) + (radarOk ? 1 : 0);
        if (overviewOnlineValue != null) overviewOnlineValue.setText(String.valueOf(online));
        if (overviewPendingValue != null) {
            overviewPendingValue.setText(String.valueOf(3 - online));
            overviewPendingValue.setTextColor(online == 3 ? STATUS_OK_COLOR : STATUS_WAIT_COLOR);
        }
        activity.pushMiniHostStatusTo(this);
    }

    /**
     * 设备首页展示迷你主机（WiFi 发送端）的连接状态与设备名称。
     */
    public void updateMiniHostStatus(
            boolean connected,
            String deviceName,
            String message
    ) {
        if (homeMiniHostStatusText == null) return;
        String text;
        int color;
        // 只以真实 TCP 连接状态为准：未建立连接一律显示"未连接"，
        // 避免"等待迷你主机连接"等初始状态被误显示为已连接
        if (connected) {
            text = "● 已连接";
            color = STATUS_OK_COLOR;
        } else {
            text = "● 未连接";
            color = STATUS_WAIT_COLOR;
        }
        homeMiniHostStatusText.setText(text);
        homeMiniHostStatusText.setTextColor(color);
    }

    private void setStatus(TextView view, boolean connected, String label) {
        if (view == null) return;
        view.setText(connected ? "● " + label + "已连接" : "● " + label + "待连接");
        view.setTextColor(connected ? STATUS_OK_COLOR : STATUS_WAIT_COLOR);
    }


    private void configureNetwork(View root) {
        Button settingsButton = root.findViewById(R.id.networkSettingsButton);
        Button refreshButton = root.findViewById(R.id.networkRefreshButton);
        settingsButton.setOnClickListener(v -> openSystemSettings(Settings.ACTION_WIRELESS_SETTINGS));
        refreshButton.setOnClickListener(v -> updateNetworkStatus());
    }

    private void updateNetworkStatus() {
        if (!isAdded() || networkStatusText == null) return;
        ConnectivityManager manager = (ConnectivityManager) requireContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = false;
        if (manager != null) {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null
                    ? null : manager.getNetworkCapabilities(network);
            connected = capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        networkStatusText.setText(connected ? "● 网络连接成功" : "● 网络待连接");
        networkStatusText.setTextColor(connected ? STATUS_OK_COLOR : STATUS_WAIT_COLOR);
        if (overviewNetworkValue != null) {
            overviewNetworkValue.setText(connected ? "正常" : "待连接");
            overviewNetworkValue.setTextColor(connected ? STATUS_OK_COLOR : STATUS_WAIT_COLOR);
        }
    }

    private void configureSound() {
        if (audioManager == null) {
            soundSeekBar.setEnabled(false);
            soundValueText.setText("媒体音量：不可用");
            return;
        }
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        soundSeekBar.setMax(max);
        soundSeekBar.setProgress(current);
        updateSoundLabel(current, max);
        soundSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || audioManager == null) return;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                updateSoundLabel(progress, max);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private void updateSoundLabel(int value, int max) {
        int percent = Math.round(value * 100f / Math.max(1, max));
        soundValueText.setText("媒体音量：" + percent + "%");
    }

    private void configureBrightness() {
        int saved = Math.max(10, Math.min(100, preferences.getInt(KEY_BRIGHTNESS_PERCENT, 70)));
        brightnessSeekBar.setMax(100);
        brightnessSeekBar.setProgress(saved);
        applyBrightness(saved);
        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int safe = Math.max(10, progress);
                if (progress != safe) seekBar.setProgress(safe);
                applyBrightness(safe);
                if (fromUser) preferences.edit().putInt(KEY_BRIGHTNESS_PERCENT, safe).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private void applyBrightness(int percent) {
        if (!isAdded()) return;
        WindowManager.LayoutParams params = requireActivity().getWindow().getAttributes();
        params.screenBrightness = Math.max(0.1f, Math.min(1f, percent / 100f));
        requireActivity().getWindow().setAttributes(params);
        brightnessValueText.setText("亮度：" + percent + "%");
    }

    private void configureMaintenance(View root) {
        root.findViewById(R.id.systemSettingsButton).setOnClickListener(
                v -> openSystemSettings(Settings.ACTION_SETTINGS));
        root.findViewById(R.id.appDetailsButton).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        });
        root.findViewById(R.id.homeSettingsButton).setOnClickListener(
                v -> openSystemSettings(Settings.ACTION_HOME_SETTINGS));
    }

    private void configureBootLock() {
        MainActivity activity = getActivity() instanceof MainActivity
                ? (MainActivity) getActivity() : null;
        boolean enabled = activity == null || activity.isBootLockEnabled();
        bootLockSwitch.setOnCheckedChangeListener(null);
        bootLockSwitch.setChecked(enabled);
        updateBootLockLabel(enabled);
        bootLockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).applyBootLock(checked);
            }
            updateBootLockLabel(checked);
        });
    }

    private void updateBootLockLabel(boolean enabled) {
        bootLockStatusText.setText(enabled ? "● 已启用" : "● 已关闭");
        bootLockStatusText.setTextColor(enabled ? STATUS_OK_COLOR : STATUS_WAIT_COLOR);
    }

    private void openSystemSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDeviceStatuses();
        updateNetworkStatus();
    }
}
