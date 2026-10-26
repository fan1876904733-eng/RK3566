package com.weiguang.drcontroller;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.weiguang.drcontroller.hardware.ai.AiAnalyzer;
import com.weiguang.drcontroller.hardware.bluetooth.BluetoothXrayManager;
import com.weiguang.drcontroller.hardware.radar.RadarReading;
import com.weiguang.drcontroller.hardware.storage.ImageRepository;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;
import com.weiguang.drcontroller.hardware.wifi.WifiXrayServer;
import com.weiguang.drcontroller.hardware.camera.CameraFrameManager;
import com.weiguang.drcontroller.hardware.thermal.ThermalFrameManager;
import com.weiguang.drcontroller.hardware.usb.HlkLd6002RadarAdapter;
import com.weiguang.drcontroller.hardware.usb.UsbDeviceMatcher;
import com.weiguang.drcontroller.hardware.usb.UsbRadarAdapter;
import com.weiguang.drcontroller.ui.AiFragment;
import com.weiguang.drcontroller.ui.HistoryFragment;
import com.weiguang.drcontroller.ui.HomeFragment;
import com.weiguang.drcontroller.ui.PreviewFragment;
import com.weiguang.drcontroller.ui.ThermalFragment;
import com.weiguang.drcontroller.ui.XrayFragment;
import com.weiguang.drcontroller.ui.CaseDetailFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String SETTINGS_PREFS = "weiguang_system_settings";
    private static final String KEY_BOOT_LOCK_ENABLED = "boot_lock_enabled";
    private static final String ACTION_USB_RADAR_PERMISSION =
            "com.weiguang.drcontroller.USB_RADAR_PERMISSION";


    private static final int IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private enum RadarPanelMode {
        HIDDEN,
        LIVE
    }

    private static final int MENU_SELECTED_COLOR = Color.rgb(25, 118, 210);
    private static final int MENU_NORMAL_COLOR = Color.rgb(38, 56, 68);
    private static final int STATUS_OK_COLOR = Color.rgb(0, 255, 136);
    private static final int STATUS_WAIT_COLOR = Color.rgb(255, 170, 0);
    private static final int STATUS_ERROR_COLOR = Color.rgb(239, 83, 80);

    private TextView pageTitle;
    private TextView pageDescription;

    private TextView cameraConnectionText;
    private TextView thermalConnectionText;
    private TextView xrayConnectionText;
    private TextView radarSummaryText;

    private View radarPanel;
    private View radarPanelSpacer;
    private TextView radarTitleText;
    private TextView radarSubtitleText;
    private TextView radarConnectionText;
    private TextView radarDeviceText;
    private TextView radarTargetText;
    private TextView radarDistanceText;
    private TextView radarBreathText;
    private TextView radarHeartText;
    private TextView radarTimestampLabel;
    private TextView radarTimestampText;

    private Button homeButton;
    private Button cameraPreviewButton;
    private Button thermalPreviewButton;
    private Button radarDetailButton;
    private Button exposurePrepareButton;
    private Button xrayPreviewButton;
    private Button aiButton;
    private Button historyButton;
    private Button aiAssistantButton;
    private Button radarControlButton;
    private View bottomStatusBar;

    private RadarPanelMode radarPanelMode = RadarPanelMode.HIDDEN;
    private boolean cameraConnected;
    private boolean thermalConnected;
    private boolean xrayConnected;
    private RadarReading latestRadarReading = RadarReading.empty();

    private UsbManager usbManager;
    private HlkLd6002RadarAdapter radarAdapter;
    private UsbDevice connectedRadarUsbDevice;

    private final BroadcastReceiver radarUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (ACTION_USB_RADAR_PERMISSION.equals(action)) {
                if (device != null
                        && HlkLd6002RadarAdapter.isSupportedDevice(device)
                        && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connectRadarDevice(device);
                } else {
                    showRadarConnectionError("未获得毫米波雷达USB访问权限");
                }
                return;
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (HlkLd6002RadarAdapter.isSupportedDevice(device)) {
                    tryAutoConnectRadar(false);
                }
                return;
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (device != null
                        && connectedRadarUsbDevice != null
                        && device.getDeviceId() == connectedRadarUsbDevice.getDeviceId()) {
                    disconnectRadar("毫米波雷达已断开");
                }
            }
        }
    };

    private BluetoothXrayManager xrayManager;
    private WifiXrayServer wifiXrayServer;
    private SharedPreferences systemPreferences;
    private boolean bootLockEnabled = true;
    private HistoryFragment historyFragment;
    private com.weiguang.drcontroller.ui.ChatFragment chatFragment;
    private Fragment currentPageFragment;
    private boolean latestMiniHostConnected = false;
    private String latestMiniHostName = "--";
    private String latestMiniHostMessage = "等待迷你主机连接";
    private final BluetoothXrayManager.Listener mainXrayListener =
            new BluetoothXrayManager.Listener() {
                @Override
                public void onConnectionStateChanged(
                        BluetoothXrayManager.ConnectionState state,
                        String deviceName,
                        String message
                ) {
                    updateXrayStatus(
                            state == BluetoothXrayManager.ConnectionState.CONNECTED,
                            message
                    );
                }

                @Override
                public void onImageReceived(
                        byte[] imageBytes,
                        long captureTimeMillis
                ) {
                    handleXrayImageReceived(imageBytes, captureTimeMillis);
                }

                @Override
                public void onError(String message) {
                    updateXrayStatus(false, message);
                }
            };

    /** WiFi 迷你主机专用监听：连接状态只在设备首页展示，不写入底部状态条。 */
    private final WifiXrayServer.Listener wifiXrayListener =
            new WifiXrayServer.Listener() {
                @Override
                public void onConnectionStateChanged(
                        WifiXrayServer.ConnectionState state,
                        String deviceName,
                        String message
                ) {
                    updateMiniHostStatus(
                            state == WifiXrayServer.ConnectionState.CONNECTED,
                            deviceName,
                            message
                    );
                }

                @Override
                public void onImageReceived(
                        byte[] imageBytes,
                        long captureTimeMillis
                ) {
                    handleWifiXrayImageReceived(imageBytes, captureTimeMillis);
                }

                @Override
                public void onReportReceived(
                        String reportText,
                        long captureTimeMillis
                ) {
                    handleMiniHostReport(reportText, captureTimeMillis);
                }

                @Override
                public void onError(String message) {
                    updateMiniHostStatus(false, "--", "迷你主机连接异常");
                }
            };

    /**
     * 保存迷你主机返回的 AI 报告到对应案例。
     * 报告帧时间戳与图片帧一致时，自动归入同一案例。
     */
    private void handleMiniHostReport(String reportText, long captureTimeMillis) {
        if (reportText == null || reportText.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        long validTime = captureTimeMillis > 0L ? captureTimeMillis : now;
        if (Math.abs(validTime - now) > 10L * 60L * 1000L) {
            validTime = now;
        }
        String caseId = ImageRepository.buildCaptureId(validTime);
        CaseRepository.saveAiReport(MainActivity.this, caseId, reportText);
        CaseRepository.ensureCaseTime(MainActivity.this, caseId, validTime);
        Toast.makeText(
                MainActivity.this,
                "已收到迷你主机AI分析报告",
                Toast.LENGTH_LONG
        ).show();
    }

    /**
     * WiFi 迷你主机推过来的 X-Ray 图：每张都是独立拍片，直接新建案例，不复用活跃案例。
     */
    private void handleWifiXrayImageReceived(byte[] imageBytes, long captureTimeMillis) {
        long now = System.currentTimeMillis();
        long validCaptureTime = captureTimeMillis > 0L ? captureTimeMillis : now;
        if (Math.abs(validCaptureTime - now) > 10L * 60L * 1000L) {
            validCaptureTime = now;
        }
        String caseId = ImageRepository.buildCaptureId(validCaptureTime);
        CaseRepository.activateCase(MainActivity.this, caseId, validCaptureTime);
        CaseRepository.saveRadarSnapshot(MainActivity.this, caseId, latestRadarReading, validCaptureTime);
        updateXrayStatus(true, "X-Ray图像已接收，正在归档");
        ImageRepository.saveXrayBytes(MainActivity.this, imageBytes, validCaptureTime, caseId, new ImageRepository.SaveCallback() {
            @Override public void onSaved(Uri uri) { updateXrayStatus(true, ""); Toast.makeText(MainActivity.this, "✓ 图片传输成功", Toast.LENGTH_SHORT).show(); }
            @Override public void onError(String message) { updateXrayStatus(true, "X-Ray已显示，保存失败"); }
        });
    }

    /**
     * X-Ray 图像统一归档入口（蓝牙与 WiFi 共用）。
     * 帧时间戳与设备当前时间偏差超过 10 分钟时，以设备（北京）时间为准，
     * 避免迷你主机系统时间错误导致历史记录时间不正确。
     */
    private void handleXrayImageReceived(
            byte[] imageBytes,
            long captureTimeMillis
    ) {
        long now = System.currentTimeMillis();
        long validCaptureTime = captureTimeMillis > 0L
                ? captureTimeMillis : now;
        if (Math.abs(validCaptureTime - now) > 10L * 60L * 1000L) {
            validCaptureTime = now;
        }
        // 与 WiFi 路径保持一致：每帧都新建独立案例，
        // 避免与 WiFi 路径的活跃案例互相覆盖导致历史记录错乱
        String caseId = ImageRepository.buildCaptureId(validCaptureTime);
        CaseRepository.activateCase(MainActivity.this, caseId, validCaptureTime);
        CaseRepository.saveRadarSnapshot(
                MainActivity.this,
                caseId,
                latestRadarReading,
                validCaptureTime
        );
        archiveCompanionFrames(caseId);
        updateXrayStatus(true, "X-Ray图像已接收，正在归档");
        final String archiveCaseId = caseId;
        ImageRepository.saveXrayBytes(
                MainActivity.this,
                imageBytes,
                validCaptureTime,
                archiveCaseId,
                new ImageRepository.SaveCallback() {
                    @Override
                    public void onSaved(Uri uri) {
                        updateXrayStatus(true, "");
                        Toast.makeText(MainActivity.this, "✓ 图片传输成功", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        updateXrayStatus(
                                true,
                                "X-Ray已显示，保存失败"
                        );
                        Toast.makeText(
                                MainActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        systemPreferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        bootLockEnabled = systemPreferences.getBoolean(KEY_BOOT_LOCK_ENABLED, true);
        applySystemUiMode();
        setContentView(R.layout.activity_main);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (bootLockEnabled) {
                    Toast.makeText(
                            MainActivity.this,
                            "界面已锁定，请在系统设置中关闭开机锁定",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        // Android 9 (API 28) 及以下写 MediaStore 需要运行时权限；Android 10+ 由 RELATIVE_PATH 免权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 3002);
            }
        }

        bindViews();
        setButtonEvents();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        radarAdapter = new HlkLd6002RadarAdapter(this);
        registerRadarUsbReceiver();

        xrayManager = BluetoothXrayManager.getInstance(this);
        xrayManager.addListener(mainXrayListener);
        updateXrayStatus(xrayManager.isConnected(), "");

        // WiFi X-Ray 接收服务：使用 XRY2/RPT2 + nonce + HMAC-SHA256 鉴权帧。
        wifiXrayServer = WifiXrayServer.getInstance();
        wifiXrayServer.addListener(wifiXrayListener);
        wifiXrayServer.start(WifiXrayServer.DEFAULT_MINI_HOST, WifiXrayServer.DEFAULT_PORT);

        // 如果系统已经保存了USB授权，开机后自动恢复LD6002连接；
        // 首次授权仍由用户点击右侧“连接毫米波雷达”触发。
        getWindow().getDecorView().postDelayed(
                () -> tryAutoConnectRadar(false),
                900L
        );

        // 开机即后台挂载小农助手（隐藏）：无需先点开助手页，
        // 唤醒词监听随 onCreateView 自动启动，任何页面都可用语音唤醒。
        if (chatFragment == null) {
            chatFragment = new com.weiguang.drcontroller.ui.ChatFragment();
        }
        getSupportFragmentManager().beginTransaction()
                .add(R.id.pageContainer, chatFragment)
                .hide(chatFragment)
                .commitAllowingStateLoss();

        openPage(
                new HomeFragment(),
                homeButton,
                "设备首页",
                "微光智造多模态影像终端",
                RadarPanelMode.HIDDEN
        );
    }

    private void bindViews() {
        pageTitle = findViewById(R.id.pageTitle);
        pageDescription = findViewById(R.id.pageDescription);

        cameraConnectionText = findViewById(R.id.cameraConnectionText);
        thermalConnectionText = findViewById(R.id.thermalConnectionText);
        xrayConnectionText = findViewById(R.id.xrayConnectionText);
        radarSummaryText = findViewById(R.id.systemConnectionText);

        radarPanel = findViewById(R.id.radarPanel);
        radarPanelSpacer = findViewById(R.id.radarPanelSpacer);
        radarTitleText = findViewById(R.id.radarTitleText);
        radarSubtitleText = findViewById(R.id.radarSubtitleText);
        radarConnectionText = findViewById(R.id.radarConnectionText);
        radarDeviceText = findViewById(R.id.radarDeviceText);
        radarTargetText = findViewById(R.id.radarTargetText);
        radarDistanceText = findViewById(R.id.radarDistanceText);
        radarBreathText = findViewById(R.id.radarBreathText);
        radarHeartText = findViewById(R.id.radarHeartText);
        radarTimestampLabel = findViewById(R.id.radarTimestampLabel);
        radarTimestampText = findViewById(R.id.radarTimestampText);

        homeButton = findViewById(R.id.homeButton);
        cameraPreviewButton = findViewById(R.id.cameraPreviewButton);
        thermalPreviewButton = findViewById(R.id.thermalPreviewButton);
        radarDetailButton = findViewById(R.id.radarDetailButton);
        exposurePrepareButton = findViewById(R.id.exposurePrepareButton);
        xrayPreviewButton = findViewById(R.id.xrayPreviewButton);
        aiButton = findViewById(R.id.aiButton);
        historyButton = findViewById(R.id.historyButton);
        aiAssistantButton = findViewById(R.id.aiAssistantButton);
        radarControlButton = findViewById(R.id.radarControlButton);
        bottomStatusBar = findViewById(R.id.bottomStatusBar);
    }

    private void setButtonEvents() {
        homeButton.setOnClickListener(view ->
                openPage(
                        new HomeFragment(),
                        homeButton,
                        "设备首页",
                        "设备连接状态、影像归档与系统设置",
                        RadarPanelMode.HIDDEN
                )
        );

        cameraPreviewButton.setOnClickListener(view ->
                openPage(
                        new PreviewFragment(),
                        cameraPreviewButton,
                        "摄像头",
                        "拍照只存实时图，右上角同时拍照可连存热成像",
                        RadarPanelMode.HIDDEN
                )
        );

        thermalPreviewButton.setOnClickListener(view ->
                openPage(
                        new ThermalFragment(),
                        thermalPreviewButton,
                        "热成像",
                        "热成像实时预览",
                        RadarPanelMode.HIDDEN
                )
        );

        radarDetailButton.setOnClickListener(view -> openRadarDetailPage());

        exposurePrepareButton.setOnClickListener(view -> openExposurePreparePage());

        xrayPreviewButton.setOnClickListener(view ->
                openPage(
                        new XrayFragment(),
                        xrayPreviewButton,
                        "X-Ray",
                        "选择历史X-Ray影像并查看AI分析结果",
                        RadarPanelMode.HIDDEN
                )
        );

        aiButton.setOnClickListener(view ->
                openPage(
                        new AiFragment(),
                        aiButton,
                        "AI分析",
                        "查看智能识别与分析结果",
                        RadarPanelMode.HIDDEN
                )
        );

        historyButton.setOnClickListener(view -> openHistoryPage());
        aiAssistantButton.setOnClickListener(view -> openChatPage());


        radarControlButton.setOnClickListener(view ->
                tryAutoConnectRadar(true)
        );
    }

    private void openPage(
            Fragment fragment,
            Button selectedButton,
            String title,
            String description,
            RadarPanelMode panelMode
    ) {
        FragmentManager fm = getSupportFragmentManager();
        // 立即执行排队中的页面切换事务：连续快速点击/语音连发指令时，上一次的
        // add/hide/remove 可能还在排队未执行，若此时基于过时快照做清理决策，
        // 旧页面不会被移除，导致多个页面叠加显示（页面画面残留的根源）。
        fm.executePendingTransactions();
        FragmentTransaction tx = fm.beginTransaction();
        // 不使用淡入淡出动画：FragmentManager 的 fade 动画被新操作中断时，
        // 被 hide 的旧页面 View 不会置为 GONE，造成旧页面画面残留在背景（如首页残影）。
        // 直接 hide/remove，切换立即生效，无残留窗口。

        // 只保留目标页面可见。小农助手/历史记录实例保留复用（对话记录、列表滚动位置）；
        // 其余页面每次切换都是新实例，移除旧实例，避免 pageContainer 内实例堆积造成残留。
        // 热成像/摄像头连接由全局单例管理，页面实例销毁不影响连接（切换回来会重新绑定预览）。
        // 小农助手被 hide 后唤醒词监听仍随其视图持续运行，任何页面叫"小农"都能响应。
        for (Fragment added : fm.getFragments()) {
            if (added == fragment) continue;
            // 小农助手、历史记录需要保留实例（对话记录、列表滚动位置），切换时仅隐藏；
            // 其余页面每次切换都是新实例，直接移除旧实例，避免 pageContainer 内实例堆积，
            // 否则旧页面内容会残留在新页面背景中（配合无动画切换，彻底消除残影）。
            if (added == chatFragment || added == historyFragment) {
                if (added.isAdded() && !added.isHidden()) tx.hide(added);
            } else if (added.isAdded()) {
                tx.remove(added);
            }
        }

        if (fragment.isAdded()) {
            tx.show(fragment);
        } else {
            tx.add(R.id.pageContainer, fragment);
        }
        // 同步完成隐藏/移除/显示，避免旧 View 在下一帧继续参与绘制形成残影。
        tx.commitNowAllowingStateLoss();
        currentPageFragment = fragment;

        // 设备首页按设计稿隐藏底部状态条，其他页面保留
        if (bottomStatusBar != null) {
            bottomStatusBar.setVisibility(fragment instanceof HomeFragment ? View.GONE : View.VISIBLE);
        }

        pageTitle.setText(title);
        pageDescription.setText(description);
        selectMenuButton(selectedButton);
        setRadarPanelMode(panelMode);
    }

    private void selectMenuButton(Button selectedButton) {
        Button[] menuButtons = {
                homeButton,
                cameraPreviewButton,
                thermalPreviewButton,
                radarDetailButton,
                exposurePrepareButton,
                xrayPreviewButton,
                aiButton,
                historyButton,
                aiAssistantButton
        };

        for (Button button : menuButtons) {
            button.setBackgroundResource(R.drawable.bg_menu_normal);
        }

        if (selectedButton != null) {
            selectedButton.setBackgroundResource(R.drawable.bg_menu_selected);
        }
    }

    private void setRadarPanelMode(RadarPanelMode panelMode) {
        radarPanelMode = panelMode;

        if (panelMode == RadarPanelMode.HIDDEN) {
            radarPanel.setVisibility(View.GONE);
            radarPanelSpacer.setVisibility(View.GONE);
            return;
        }

        radarPanel.setVisibility(View.VISIBLE);
        radarPanelSpacer.setVisibility(View.VISIBLE);

        radarTitleText.setText("毫米波雷达实时数据");
        radarSubtitleText.setText("物体 / 距离 / 呼吸 / 心率");
        radarTimestampLabel.setText("数据时间");
        radarControlButton.setVisibility(View.VISIBLE);
        renderRadarReading(latestRadarReading);
    }

    /**
     * 摄像头页面调用此方法更新底部状态。
     */
    public void updateCameraStatus(boolean connected, String message) {
        cameraConnected = connected;
        runOnUiThread(() -> {
            cameraConnectionText.setText(connected ? "● 摄像头连接成功" : "● 摄像头待连接");
            cameraConnectionText.setTextColor(connected ? STATUS_OK_COLOR : STATUS_WAIT_COLOR);
            refreshHomeStatusCards();
        });
    }

    /**
     * 热成像页面调用此方法更新底部状态。
     */
    public void updateThermalStatus(boolean connected, String message) {
        thermalConnected = connected;
        runOnUiThread(() -> {
            thermalConnectionText.setText(connected ? "● 热成像连接成功" : "● 热成像待连接");
            thermalConnectionText.setTextColor(connected ? STATUS_OK_COLOR : STATUS_WAIT_COLOR);
            refreshHomeStatusCards();
        });
    }

    /**
     * X-Ray页面调用此方法更新底部状态。
     */
    public void updateXrayStatus(boolean connected, String message) {
        xrayConnected = connected;
        runOnUiThread(() -> {
            // 底部状态条不再固定显示"X-Ray连接成功"；
            // 连接信息统一在设备首页"迷你主机"卡片展示。
            if (message != null && !message.trim().isEmpty()) {
                xrayConnectionText.setVisibility(View.VISIBLE);
                xrayConnectionText.setText("● " + message.trim());
                xrayConnectionText.setTextColor(
                        connected ? STATUS_OK_COLOR : STATUS_WAIT_COLOR
                );
            } else {
                xrayConnectionText.setVisibility(View.GONE);
            }
            refreshHomeStatusCards();
        });
    }

    /**
     * 更新设备首页"迷你主机"连接状态（是否连接 + 连接的设备名称）。
     * 首页不在前台时先暂存，打开首页后自动同步。
     */
    public void updateMiniHostStatus(
            boolean connected,
            String deviceName,
            String message
    ) {
        latestMiniHostConnected = connected;
        latestMiniHostName = deviceName == null ? "--" : deviceName;
        latestMiniHostMessage = message;
        runOnUiThread(() -> {
            Fragment fragment = getSupportFragmentManager()
                    .findFragmentById(R.id.pageContainer);
            if (fragment instanceof HomeFragment) {
                ((HomeFragment) fragment).updateMiniHostStatus(
                        latestMiniHostConnected,
                        latestMiniHostName,
                        latestMiniHostMessage
                );
            }
        });
    }

    /** 设备首页打开/刷新时由 HomeFragment 调用，同步最新迷你主机状态。 */
    public void pushMiniHostStatusTo(HomeFragment fragment) {
        if (fragment != null) {
            fragment.updateMiniHostStatus(
                    latestMiniHostConnected,
                    latestMiniHostName,
                    latestMiniHostMessage
            );
        }
    }

    /**
     * 毫米波雷达解析出实时数据后统一刷新。
     * 摄像头和热成像页面实时更新，X-Ray页面保持拍摄瞬间快照。
     */
    public void updateRadarReading(RadarReading reading) {
        runOnUiThread(() -> {
            latestRadarReading = reading == null ? RadarReading.empty() : reading;
            if (radarSummaryText != null) {
                radarSummaryText.setText(
                        latestRadarReading.isConnected()
                                ? "● 毫米波雷达连接成功"
                                : "● 毫米波雷达待连接"
                );
                radarSummaryText.setTextColor(
                        latestRadarReading.isConnected()
                                ? STATUS_OK_COLOR : STATUS_WAIT_COLOR
                );
            }
            if (radarPanelMode == RadarPanelMode.LIVE) {
                renderRadarReading(latestRadarReading);
            }
            refreshHomeStatusCards();
        });
    }

    private void renderRadarReading(RadarReading reading) {
        RadarReading safeReading = reading == null
                ? RadarReading.empty()
                : reading;

        radarConnectionText.setText(
                safeReading.isConnected()
                        ? "● 雷达串口已连接"
                        : "● 雷达待连接"
        );
        radarConnectionText.setTextColor(
                safeReading.isConnected()
                        ? STATUS_OK_COLOR
                        : STATUS_WAIT_COLOR
        );

        radarDeviceText.setText(
                safeReading.isConnected() ? "数据已连接" : "等待数据连接"
        );
        radarTargetText.setText(
                safeText(safeReading.getTargetStatus(), "未检测到物体")
        );
        radarDistanceText.setText(
                safeText(safeReading.getDistance(), "-- cm")
        );
        radarBreathText.setText(
                safeText(safeReading.getBreathRate(), "-- bpm")
        );
        radarHeartText.setText(
                safeText(safeReading.getHeartRate(), "-- bpm")
        );

        radarTimestampText.setText(formatTime(safeReading.getDataTimeMillis()));

        radarControlButton.setText(
                safeReading.isConnected()
                        ? "重新连接毫米波雷达"
                        : "连接毫米波雷达"
        );
    }

    private void archiveCompanionFrames(String caseId) {
        if (caseId == null || caseId.trim().isEmpty()) return;
        CameraFrameManager.getInstance().captureLatestFrame(
                getApplicationContext(),
                caseId,
                new CameraFrameManager.CaptureCallback() {
                    @Override public void onSuccess(Uri uri) { }
                    @Override public void onFailure(String message) { }
                }
        );
        ThermalFrameManager.getInstance().captureLatestFrame(
                getApplicationContext(),
                caseId,
                new ThermalFrameManager.CaptureCallback() {
                    @Override public void onSuccess(Uri uri) { }
                    @Override public void onFailure(String message) { }
                }
        );
    }

    public void registerCaseCapture(String caseId, long captureTimeMillis) {
        if (caseId == null || caseId.trim().isEmpty()) return;
        long validCaptureTime = captureTimeMillis > 0L
                ? captureTimeMillis : System.currentTimeMillis();
        RadarReading radarAtCapture = latestRadarReading == null
                ? RadarReading.empty() : latestRadarReading;

        CaseRepository.activateCase(this, caseId, validCaptureTime);
        CaseRepository.saveRadarSnapshot(this, caseId, radarAtCapture, validCaptureTime);
    }

    public void openAiForCase(String caseId) {
        openPage(
                AiFragment.newInstance(caseId),
                aiButton,
                "AI分析",
                "选择历史案例进行AI辅助分析并保存报告",
                RadarPanelMode.HIDDEN
        );
    }

    public void openHistoryPage() {
        // 复用历史记录实例，保留列表数据与滚动位置；
        // 从案例详情返回后停留在原位置而不是回到列表顶部。
        if (historyFragment == null) {
            historyFragment = new HistoryFragment();
        }
        openPage(
                historyFragment,
                historyButton,
                "历史记录",
                "按时间查看案例、影像、AI报告和删除记录",
                RadarPanelMode.HIDDEN
        );
    }

    public void openChatPage() {
        // 复用小农助手实例，切走再回来对话记录不丢失
        if (chatFragment == null) {
            chatFragment = new com.weiguang.drcontroller.ui.ChatFragment();
        }
        openPage(
                chatFragment,
                aiAssistantButton,
                "小农助手",
                "AI 语音助手，支持对话和设备控制",
                RadarPanelMode.HIDDEN
        );
    }

    // ==================== 语音/文字指令控制入口（小农助手调用） ====================

    public void openCameraPageByCommand() {
        openPage(
                new PreviewFragment(),
                cameraPreviewButton,
                "摄像头",
                "一次拍照同时保存实时图和热成像图",
                RadarPanelMode.HIDDEN
        );
    }

    /** 语音指令"同时拍照"：打开摄像头页并自动拍一张（实时图+热成像） */
    public void capturePairedPhotoByCommand() {
        PreviewFragment preview = new PreviewFragment(true);
        // 记录最近一次拍摄的案例 ID，供小农"刚才那条"多轮指令打开
        preview.setCaseCaptureListener((caseId, timeMillis) -> lastCapturedCaseId = caseId);
        openPage(
                preview,
                cameraPreviewButton,
                "摄像头",
                "一次拍照同时保存实时图和热成像图",
                RadarPanelMode.HIDDEN
        );
    }

    public void captureCameraOnlyFromExposure() {
        openPage(
                new PreviewFragment(true, true), cameraPreviewButton,
                "摄像头", "曝光组合：自动拍摄可见光图像", RadarPanelMode.HIDDEN
        );
    }

    public void captureThermalOnlyFromExposure() {
        openPage(
                new ThermalFragment(true), thermalPreviewButton,
                "热成像", "曝光组合：自动拍摄热成像", RadarPanelMode.HIDDEN
        );
    }

    private String lastCapturedCaseId;

    /** 最近一次拍摄（同时拍照/一键全检）生成的案例 ID；没有则为 null */
    public String getLastCapturedCaseId() {
        return lastCapturedCaseId;
    }

    /**
     * 一键全检（小农指令"帮我拍一下看看/全面检查/全检"等触发）：
     * 打开摄像头页自动"同时拍照"（实时图+热成像）→ 拍摄成功后立即调用 AI 多模态分析
     * （可见光+热成像融合报告，含检测主体识别）→ 结果交给小农助手语音播报并存入历史记录。
     *
     * @param userHint 用户对拍摄目标的口语描述（可为空），作为分析的辅助参考
     */
    public void runFullInspectionByCommand(String userHint) {
        PreviewFragment preview = new PreviewFragment(true);
        final String hint = userHint == null ? "" : userHint.trim();
        preview.setCaseCaptureListener(new PreviewFragment.CaseCaptureListener() {
            @Override
            public void onPairedCaseSaved(String caseId, long timeMillis) {
                lastCapturedCaseId = caseId;
                if (chatFragment != null) chatFragment.notifyInspectionStarted(caseId);
                CaseRepository.CaseRecord record = CaseRepository.findCase(getApplicationContext(), caseId);
                if (record == null) {
                    if (chatFragment != null) chatFragment.notifyInspectionError("案例读取失败，无法开始分析", false);
                    return;
                }
                AiAnalyzer.analyzeCase(
                        getApplicationContext(),
                        record,
                        hint.isEmpty() ? null : hint,
                        new AiAnalyzer.Callback() {
                            @Override
                            public void onSuccess(String report) {
                                if (chatFragment != null) chatFragment.notifyInspectionDone(caseId, report);
                            }

                            @Override
                            public void onError(String error) {
                                if (chatFragment != null) chatFragment.notifyInspectionError(error, true);
                                // 自动补救：跳到 AI 分析页并选中该案例，提供手动重试入口
                                openAiForCase(caseId);
                            }
                        }
                );
            }

            @Override
            public void onAutoCaptureFailed(String message) {
                if (chatFragment != null) {
                    chatFragment.notifyInspectionError(message + "，请检查热成像连接后重试", false);
                }
            }
        });
        openPage(
                preview,
                cameraPreviewButton,
                "摄像头",
                "一键全检：自动拍摄并调用AI分析",
                RadarPanelMode.HIDDEN
        );
    }

    public void openThermalPageByCommand() {
        openPage(
                new ThermalFragment(),
                thermalPreviewButton,
                "热成像",
                "热成像实时预览",
                RadarPanelMode.HIDDEN
        );
    }

    public void openRadarDetailPage() {
        openPage(
                new com.weiguang.drcontroller.ui.RadarDetailFragment(),
                radarDetailButton,
                "毫米波雷达",
                "目标状态、距离、呼吸、心率与最近60秒趋势",
                RadarPanelMode.HIDDEN
        );
    }

    public void openExposurePreparePage() {
        openPage(
                new com.weiguang.drcontroller.ui.ExposurePrepareFragment(),
                exposurePrepareButton,
                "准备曝光",
                "确认设备状态、保存毫米波数据并执行曝光准备",
                RadarPanelMode.HIDDEN
        );
    }

    /**
     * 保存曝光前的毫米波快照。当前版本尚未接入 433 MHz 发射模块，
     * 因此这里只归档准备数据，绝不伪造已经发出 X-Ray 曝光命令的状态。
     */
    public String saveExposurePreparation() {
        long now = System.currentTimeMillis();
        String caseId = ImageRepository.buildCaptureId(now);
        CaseRepository.activateCase(this, caseId, now);
        CaseRepository.saveRadarSnapshot(this, caseId, getLatestRadarReading(), now);
        lastCapturedCaseId = caseId;
        return caseId;
    }

    public void openXrayPageByCommand() {
        openPage(
                new XrayFragment(),
                xrayPreviewButton,
                "X-Ray",
                "选择历史X-Ray影像并查看AI分析结果",
                RadarPanelMode.HIDDEN
        );
    }

    public void openAiAnalysisPageByCommand() {
        openPage(
                new AiFragment(),
                aiButton,
                "AI分析",
                "查看智能识别与分析结果",
                RadarPanelMode.HIDDEN
        );
    }

    public void openHomePageByCommand() {
        openPage(
                new HomeFragment(),
                homeButton,
                "设备首页",
                "设备连接状态、影像归档与系统设置",
                RadarPanelMode.HIDDEN
        );
    }

    public void openHistoryPageByCommand() {
        openHistoryPage();
    }

    /** 连接/重连毫米波雷达 */
    public void connectRadarByCommand() {
        tryAutoConnectRadar(true);
    }

    public void openCaseDetail(String caseId) {
        // 藏起历史记录页而不是销毁它，返回时直接显示原视图，零重建零卡顿。
        CaseDetailFragment detail = CaseDetailFragment.newInstance(caseId);
        FragmentManager fm = getSupportFragmentManager();
        // 先同步排队中的切换事务，避免旧页面未被移除导致叠加残留
        fm.executePendingTransactions();
        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction();
        if (historyFragment == null) {
            // 从首页历史区直接进入时，历史页可能从未打开过，需先创建并挂载
            historyFragment = new HistoryFragment();
        }
        // 先隐藏所有旧页面（对话页/热成像/首页等），避免多个页面叠加显示。
        // 语音"打开第N条"从小农助手页进入时，必须先把对话页藏起来再显示详情页。
        for (Fragment added : fm.getFragments()) {
            if (added == detail) continue;
            if (added.isAdded() && !added.isHidden()) transaction.hide(added);
        }
        if (!historyFragment.isAdded()) {
            transaction.add(R.id.pageContainer, historyFragment);
        }
        transaction
                .hide(historyFragment)
                .add(R.id.pageContainer, detail)
                .commitNowAllowingStateLoss();
        currentPageFragment = detail;
        pageTitle.setText("案例影像");
        pageDescription.setText("查看该案例的实时图、热成像、X-Ray和毫米波数据");
        selectMenuButton(historyButton);
        setRadarPanelMode(RadarPanelMode.HIDDEN);
    }

    public void backToHistoryFromDetail() {
        // 隐藏案例详情，直接显示之前的历史记录视图（未被销毁，滚动位置天然保留）。
        // 先同步排队中的切换事务，避免叠加残留
        getSupportFragmentManager().executePendingTransactions();
        getSupportFragmentManager()
                .beginTransaction()
                .remove(currentPageFragment)
                .show(historyFragment)
                .commitNowAllowingStateLoss();
        currentPageFragment = historyFragment;
        // 详情页查看后可能已把AI报告标记为已读，返回时刷新列表让蓝点消失。
        if (historyFragment != null) historyFragment.refreshHistory();
        pageTitle.setText("历史记录");
        pageDescription.setText("按时间查看案例、影像、AI报告和删除记录");
        selectMenuButton(historyButton);
        setRadarPanelMode(RadarPanelMode.HIDDEN);
    }

    public RadarReading getLatestRadarReading() {
        return latestRadarReading == null ? RadarReading.empty() : latestRadarReading;
    }

    public boolean isCameraConnected() { return cameraConnected; }

    public boolean isThermalConnected() { return thermalConnected; }

    public boolean isXrayConnected() {
        return xrayConnected || (xrayManager != null && xrayManager.isConnected());
    }

    public void refreshHomeStatusCards() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.pageContainer);
        if (fragment instanceof HomeFragment) {
            ((HomeFragment) fragment).refreshDeviceStatuses();
        }
    }

    private void registerRadarUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_RADAR_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        ContextCompat.registerReceiver(
                this,
                radarUsbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    /**
     * 查找实际已经接在RK3566上的CP2104串口桥。
     * 当前设备VID=0x10C4(4292)，PID=0xEA60(60000)。
     */
    private UsbDevice findLd6002UsbDevice() {
        if (usbManager == null) {
            return null;
        }
        List<UsbDevice> devices = UsbDeviceMatcher.listAll(usbManager);
        for (UsbDevice device : devices) {
            if (HlkLd6002RadarAdapter.isSupportedDevice(device)) {
                return device;
            }
        }
        return null;
    }

    private void tryAutoConnectRadar(boolean requestPermissionIfNeeded) {
        if (usbManager == null) {
            showRadarConnectionError("无法获得Android USB服务");
            return;
        }

        UsbDevice device = findLd6002UsbDevice();
        if (device == null) {
            if (requestPermissionIfNeeded) {
                Toast.makeText(
                        this,
                        "未检测到毫米波雷达，请检查连接后重试",
                        Toast.LENGTH_LONG
                ).show();
            }
            return;
        }

        if (usbManager.hasPermission(device)) {
            connectRadarDevice(device);
            return;
        }

        latestRadarReading = new RadarReading(
                false,
                "毫米波雷达 · 等待连接授权",
                "等待连接",
                "-- cm",
                "-- bpm",
                "-- bpm",
                System.currentTimeMillis()
        );
        if (radarPanelMode == RadarPanelMode.LIVE) {
            renderRadarReading(latestRadarReading);
        }

        if (!requestPermissionIfNeeded) {
            return;
        }

        // 直接触发系统 USB 授权弹窗（"允许微光智造访问..."），
        // 不再叠加应用内确认框，避免用户每次要点两次。
        requestRadarUsbPermission(device);
    }

    /** 触发系统 USB 授权（授权过一次后系统不再弹窗）。 */
    private void requestRadarUsbPermission(UsbDevice device) {
        if (usbManager == null) return;
        if (usbManager.hasPermission(device)) {
            connectRadarDevice(device);
            return;
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this,
                6002,
                new Intent(ACTION_USB_RADAR_PERMISSION).setPackage(getPackageName()),
                flags
        );
        usbManager.requestPermission(device, permissionIntent);
    }

    private void connectRadarDevice(UsbDevice device) {
        if (radarAdapter == null) {
            radarAdapter = new HlkLd6002RadarAdapter(this);
        }

        connectedRadarUsbDevice = device;
        radarAdapter.connect(device, new UsbRadarAdapter.Listener() {
            @Override
            public void onRadarReading(RadarReading reading) {
                updateRadarReading(reading);
            }

            @Override
            public void onDisconnected(String message) {
                runOnUiThread(() -> {
                    connectedRadarUsbDevice = null;
                    latestRadarReading = RadarReading.empty();
                    if (radarSummaryText != null) {
                        radarSummaryText.setText("● 毫米波雷达待连接");
                        radarSummaryText.setTextColor(STATUS_WAIT_COLOR);
                    }
                    if (radarPanelMode == RadarPanelMode.LIVE) {
                        renderRadarReading(latestRadarReading);
                    }
                    refreshHomeStatusCards();
                    Toast.makeText(MainActivity.this, "毫米波雷达连接已断开", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                showRadarConnectionError(message);
            }
        });
    }

    private void disconnectRadar(String message) {
        if (radarAdapter != null) {
            radarAdapter.disconnect();
        }
        connectedRadarUsbDevice = null;
        latestRadarReading = RadarReading.empty();
        if (radarSummaryText != null) {
            radarSummaryText.setText("● 毫米波雷达待连接");
            radarSummaryText.setTextColor(STATUS_WAIT_COLOR);
        }
        if (radarPanelMode == RadarPanelMode.LIVE) {
            renderRadarReading(latestRadarReading);
        }
        refreshHomeStatusCards();
        if (message != null && !message.trim().isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void showRadarConnectionError(String message) {
        runOnUiThread(() -> {
            latestRadarReading = new RadarReading(
                    false,
                    "毫米波雷达",
                    "连接异常",
                    "-- cm",
                    "-- bpm",
                    "-- bpm",
                    System.currentTimeMillis()
            );
            if (radarSummaryText != null) {
                radarSummaryText.setText("● 毫米波雷达待连接");
                radarSummaryText.setTextColor(STATUS_WAIT_COLOR);
            }
            if (radarPanelMode == RadarPanelMode.LIVE) {
                renderRadarReading(latestRadarReading);
            }
            refreshHomeStatusCards();
        });
    }

    /**
     * 严重错误时可以调用。
     */
    public void showSystemError(String message) {
        runOnUiThread(() -> Toast.makeText(
                MainActivity.this,
                safeText(message, "系统异常"),
                Toast.LENGTH_LONG
        ).show());
    }

    private String formatTime(long timeMillis) {
        if (timeMillis <= 0L) {
            return "--";
        }
        return new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.CHINA
        ).format(new Date(timeMillis));
    }

    private String safeText(String text, String fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        return text.trim();
    }

    /**
     * 系统设置页面读取当前界面锁定状态。
     */
    public boolean isBootLockEnabled() {
        return bootLockEnabled;
    }

    /**
     * 系统设置页面切换全屏与返回键锁定。
     * 默认HOME应用仍需在Android的“默认桌面”页面中选择。
     */
    public void applyBootLock(boolean enabled) {
        bootLockEnabled = enabled;
        if (systemPreferences == null) {
            systemPreferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        }
        systemPreferences.edit()
                .putBoolean(KEY_BOOT_LOCK_ENABLED, enabled)
                .apply();
        applySystemUiMode();
    }

    private void applySystemUiMode() {
        if (bootLockEnabled) {
            enterImmersiveMode();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_VISIBLE
            );
        }
    }

    @Override
    protected void onDestroy() {
        if (xrayManager != null) {
            xrayManager.removeListener(mainXrayListener);
        }
        if (radarAdapter != null) {
            radarAdapter.release();
        }
        try {
            unregisterReceiver(radarUsbReceiver);
        } catch (Exception ignored) {
            // receiver may already be unregistered during abnormal teardown
        }
        super.onDestroy();
    }

    /**
     * 隐藏状态栏和导航栏，使RK3566开机后直接进入全屏工控界面。
     */
    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applySystemUiMode();
        }
    }

}
