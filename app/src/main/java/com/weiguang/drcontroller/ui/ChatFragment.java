package com.weiguang.drcontroller.ui;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.media.AudioFormat;
import android.media.AudioRecord;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.BuildConfig;
import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.ai.AiAnalyzer;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 小农助手 —— 豆包式 AI 对话页
 * 欢迎引导 + 推荐问题 + 头像气泡 + 流式打字机输出 + 思考中动画
 */
public class ChatFragment extends Fragment {

    private LinearLayout chatList;
    private ScrollView scrollView;
    private EditText input;
    private TextView sendBtn;
    private View welcomePanel;
    private TextView statusText;

    private OkHttpClient client;
    /**
     * Speech recognition must fail fast.  Reusing the chat client here used to
     * leave the wake word path blocked for up to 60 seconds when Wi-Fi had no
     * Internet access, making every later utterance look as if it was ignored.
     */
    private OkHttpClient speechClient;
    private Handler mainHandler;
    private final JSONArray messages = createInitialMessages();
    private String pendingAnalysisCaseId;
    private String pendingDeleteCaseId;
    private String pendingRenameCaseId;
    private String pendingFullReportCaseId;

    private static final String API_KEY = BuildConfig.DEEPSEEK_API_KEY;
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String MODEL = "deepseek-chat";
    // 上下文滑动窗口：除 system 外最多保留最近 20 条对话，防止无限增长导致 DeepSeek token 超限
    private static final int MAX_CONTEXT_MESSAGES = 20;

    // ===== 百度语音识别（短语音） =====
    // Key 统一在 app/build.gradle.kts 的 buildConfigField 中维护
    private static final String BAIDU_ASR_API_KEY = BuildConfig.BAIDU_API_KEY;
    private static final String BAIDU_ASR_SECRET_KEY = BuildConfig.BAIDU_SECRET_KEY;
    private static final String BAIDU_TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String BAIDU_ASR_URL = "https://vop.baidu.com/server_api";
    private static final String BAIDU_TTS_URL = "https://tsn.baidu.com/text2audio";
    private static final String BAIDU_CUID = "weiguang-dr-rk3566";
    private static final int VOICE_SAMPLE_RATE = 16000;
    private static final int REQ_VOICE_PERMISSION = 1001;

    private TextView micBtn;
    private AudioRecord voiceRecorder;
    private Thread voiceThread;
    private ByteArrayOutputStream voicePcmBuffer;
    private volatile int voicePcmSize = 0;
    private volatile boolean voiceRecording = false;
    private volatile int voiceActualRate = 16000; // AudioRecord 实际采样率（可能不是16000）
    // TTS 语音播放
    private MediaPlayer ttsPlayer;
    private boolean ttsEnabled = true;
    // TTS 播放队列：连续播报排队串行播放，不再互相打断
    private final java.util.ArrayDeque<TtsTask> ttsQueue = new java.util.ArrayDeque<>();
    private boolean ttsPlaying = false;
    private int ttsGeneration = 0;
    private final ConcurrentHashMap<String, byte[]> shortTtsCache = new ConcurrentHashMap<>();

    private static final class TtsTask {
        final String spoken;
        final Runnable onDone;
        final int generation;
        TtsTask(String spoken, Runnable onDone, int generation) {
            this.spoken = spoken;
            this.onDone = onDone;
            this.generation = generation;
        }
    }

    // 在线唤醒
    private TextView wakeBtn;
    private TextView muteBtn;
    private boolean wakeEnabled = false;
    private AudioRecord wakeRecorder;
    private Thread wakeThread;
    private ByteArrayOutputStream wakeBuffer;
    private int silentFrames = 0;
    private int wakeAboveFrames = 0;
    private int wakeVoicedFrames = 0;
    private int wakeSegmentFrames = 0;
    private int questionVoicedFrames = 0;
    private int consecutiveSilentWakeCount = 0;
    private double wakeNoiseFloor = 100.0;
    private final ArrayDeque<byte[]> wakePreRoll = new ArrayDeque<>();
    private final AtomicBoolean wakeAsrInFlight = new AtomicBoolean(false);
    private volatile long wakeAsrStartedMs = 0L;
    private boolean wakeSpeaking = false;
    private boolean inDialogMode = false; // 唤醒成功后的对话模式
    private boolean autoSendOnWake = false; // 唤醒后自动发送
    private boolean wakeSessionActive = false; // 唤醒对话会话进行中，回复后恢复监听
    private static final int WAKE_SAMPLE_RATE = 16000;
    private static final double WAKE_MIN_START_THRESHOLD = 420.0;
    private static final double WAKE_MIN_END_THRESHOLD = 260.0;
    private static final int WAKE_START_FRAMES = 3; // 连续120ms超阈值才判定为人声
    private static final int WAKE_MIN_VOICED_FRAMES = 5; // 少于200ms的噪声脉冲不上传
    private static final int WAKE_SILENT_LIMIT = 14; // 约0.56秒静音，减少说完唤醒词后的等待
    private static final int WAKE_MAX_SEGMENT_FRAMES = 75; // 唤醒词段最长3秒，避免持续杂音拖慢识别
    private static final int QUESTION_MAX_BYTES = 384000; // 唤醒后问题最长约12秒
    private static final int QUESTION_MIN_VOICED_FRAMES = 3; // 至少120ms有效人声才送识别
    private static final long QUESTION_RESPONSE_GRACE_MS = 1600L; // “我在”播完后留出开口时间
    private static final long QUESTION_TTS_TAIL_MS = 250L; // 忽略扬声器尾音，避免把“我在”识别成问题
    private static final int SILENT_WAKE_HELP_THRESHOLD = 3;
    private static final int WAKE_PRE_ROLL_FRAMES = 6; // 保留240ms前导音，避免吞掉“小”
    private static final String[] WAKE_WORD_ALIASES = {
            "小农", "小浓", "晓农", "晓浓", "小侬", "晓侬",
            "小能", "晓能", "小弄", "晓弄", "小龙", "晓龙",
            "小隆", "晓隆", "小荣", "晓荣", "小融", "晓融", "农农",
            "笑容", "效能", "校农", "肖农", "小牛", "晓宁"
    };
    private static final String WAKE_FIRST_CHARS = "小晓肖萧消笑校效";
    private static final String WAKE_SECOND_CHARS = "农浓侬能弄龙隆荣融容牛宁";
    private static final int WSTATE_LISTEN = 0;  // 监听唤醒词
    private static final int WSTATE_WAIT_TTS = 1; // 播"我在"中，丢弃音频
    private static final int WSTATE_RECORD = 2;  // 录用户问题
    private volatile int wakeState = WSTATE_LISTEN;
    private volatile int wakeActualRate = 16000; // 唤醒录音实际采样率
    private int wakeInitRetries = 0; // 唤醒监听初始化失败重试次数
    private boolean wakePendingPermission = false; // 首次进入自动请求麦克风权限后待恢复的唤醒
    private long wakeTtsStartMs = 0;   // 唤醒成功瞬间（开始录音）
    private long wakeTtsDoneMs = 0;    // "我在"播完瞬间
    private volatile int wakeTrimPoint = -1; // "我在"播完时录音缓冲字节切点

    private final Runnable voiceTimeout = () -> {
        if (voiceRecording) {
            Toast.makeText(requireContext(), "录音超时（55秒），自动结束", Toast.LENGTH_SHORT).show();
            stopVoiceRecording();
        }
    };

    // 自动停止录音检测：检测到文件大小2秒没变化就自动停
    private long silenceLastSize = 0;
    private int silenceSameCount = 0;
    private final Runnable silenceCheck = new Runnable() {
        @Override
        public void run() {
            if (!voiceRecording) return;
            long size = voicePcmSize;
            if (size == silenceLastSize) {
                silenceSameCount++;
                if (silenceSameCount >= 10) {
                    stopVoiceRecording();
                    return;
                }
            } else {
                silenceSameCount = 0;
                silenceLastSize = size;
            }
            mainHandler.postDelayed(this, 200);
        }
    };

    // 流式输出状态
    private boolean streaming = false;
    private TextView streamingView;          // 正在输出的 AI 气泡
    private int thinkingDots = 0;            // 思考动画点数
    private boolean streamStarted = false;   // 是否已收到第一个字符

    private static JSONArray createInitialMessages() {
        JSONArray arr = new JSONArray();
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是小农，一个自然、亲切的智能助手，运行在微光智造手持智能DR设备上。你可以像正常人一样自然回答任何问题——闲聊、常识、知识、建议都可以，不要刻意反复强调自己的身份或设备功能，也不要每次都以\"我是设备助手\"开头。只有在用户询问设备操作、设备状态或希望控制设备时，才以简洁步骤说明并主动引导。设备支持：摄像头拍照（实时图+热成像同时拍照）、热成像、毫米波雷达（HLK-LD6002，可测距离/呼吸/心率）、X-Ray影像拍摄与历史记录、WiFi传图。当用户要求打开/调取某条历史记录的AI分析报告时，应直接打开该条目的分析详情页，而不是只打开历史记录列表页。回复支持Markdown格式（加粗、列表、代码块）。");
            arr.put(systemMsg);
        } catch (Exception ignored) {}
        return arr;
    }

    private final Runnable thinkingTick = new Runnable() {
        @Override
        public void run() {
            if (streaming && streamingView != null) {
                thinkingDots = (thinkingDots % 3) + 1;
                StringBuilder dots = new StringBuilder();
                for (int i = 0; i < thinkingDots; i++) dots.append(".");
                streamingView.setText("小农正在思考" + dots);
                mainHandler.postDelayed(this, 380);
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_chat, container, false);

        client = new OkHttpClient.Builder()
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        speechClient = new OkHttpClient.Builder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        mainHandler = new Handler(Looper.getMainLooper());
        preloadWakeAckAudio();

        chatList = root.findViewById(R.id.chatList);
        scrollView = root.findViewById(R.id.chatScroll);
        input = root.findViewById(R.id.chatInput);
        sendBtn = root.findViewById(R.id.sendBtn);
        micBtn = root.findViewById(R.id.micBtn);
        welcomePanel = root.findViewById(R.id.welcomePanel);
        statusText = root.findViewById(R.id.chatStatusText);

        // 语音输入：点一下开始聆听，再点一下结束并识别
        micBtn.setOnClickListener(v -> {
            if (voiceRecording) {
                stopVoiceRecording();
            } else {
                // 手动模式优先：先停掉唤醒监听，避免抢麦克风
                if (wakeEnabled) {
                    stopWakeListening();
                    wakeBtn.setTextColor(Color.parseColor("#556677"));
                }
                startVoiceRecording();
            }
        });

        // 唤醒词开关
        wakeBtn = root.findViewById(R.id.wakeBtn);
        wakeBtn.setOnClickListener(v -> {
            wakeEnabled = !wakeEnabled;
            if (wakeEnabled) {
                // 未授予麦克风权限时先请求权限，授权后自动启动监听，避免初始化失败
                if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    wakePendingPermission = true;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_VOICE_PERMISSION);
                } else {
                    startWakeListening();
                }
                wakeBtn.setTextColor(Color.parseColor("#70D69B"));
                Toast.makeText(requireContext(), "唤醒词已开启，说\"小农小农\"唤醒", Toast.LENGTH_SHORT).show();
            } else {
                stopWakeListening();
                wakeBtn.setTextColor(Color.parseColor("#556677"));
                Toast.makeText(requireContext(), "唤醒词已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        // 闭嘴按钮：立即停止语音播报
        muteBtn = root.findViewById(R.id.muteBtn);
        muteBtn.setOnClickListener(v -> stopTts());

        // 自动开启唤醒词监听（先完整停掉可能残留的旧监听，防止新旧线程抢麦克风）
        stopWakeListening();
        wakeInitRetries = 0;
        wakeEnabled = true;
        wakeBtn.setTextColor(Color.parseColor("#70D69B"));
        // 未授予麦克风权限时先请求权限，授权回调中再启动唤醒监听；
        // 否则 AudioRecord 初始化失败两次后唤醒会被禁用，用户还以为是"唤醒失灵"
        if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            wakePendingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_VOICE_PERMISSION);
        } else {
            startWakeListening();
        }

        // 推荐问题
        String[][] suggestions = {
                {"介绍这款DR设备", "怎么拍摄X光影像"},
                {"毫米波雷达能测什么", "设备使用注意事项"}
        };
        LinearLayout[] rows = {
                root.findViewById(R.id.suggestionRow1),
                root.findViewById(R.id.suggestionRow2)
        };
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < suggestions[r].length; c++) {
                final String q = suggestions[r][c];
                TextView card = buildSuggestionCard(q);
                card.setOnClickListener(v -> sendMessage(q));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                );
                if (c == 0) lp.rightMargin = dp(8);
                rows[r].addView(card, lp);
            }
        }

        // 发送
        sendBtn.setOnClickListener(v -> sendMessage(input.getText().toString()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(input.getText().toString());
                return true;
            }
            return false;
        });

        // 恢复历史对话（切换页面后再回来不丢失）
        if (messages.length() > 1) {
            welcomePanel.setVisibility(View.GONE);
            for (int i = 1; i < messages.length(); i++) {
                try {
                    JSONObject m = messages.getJSONObject(i);
                    String role = m.optString("role");
                    String content = m.optString("content");
                    if ("user".equals(role)) {
                        addUserBubble(content);
                    } else if ("assistant".equals(role) && !content.isEmpty()) {
                        createAiBubble(content);
                    }
                } catch (Exception ignored) {}
            }
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }

        return root;
    }

    private TextView buildSuggestionCard(String text) {
        TextView card = new TextView(requireContext());
        card.setText("  " + text);
        card.setTextColor(Color.parseColor("#CFE3F0"));
        card.setTextSize(11f);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#14232E"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#2B4558"));
        card.setBackground(bg);
        return card;
    }

    private void sendMessage(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty() || streaming) return;
        consecutiveSilentWakeCount = 0;

        // 本地快捷回复
        String lower = text.toLowerCase();
        if (lower.contains("你好") || lower.contains("hi") || lower.contains("hello")) {
            addUserBubble(text);
            hideWelcome();
            addAiBubble("你好！我是小农，微光智造 DR 设备的智能助手，可以帮你完成**设备操作**、**影像分析**和**使用答疑**。你想了解什么？");
            input.setText("");
            return;
        }

        // 设备控制指令：识别到就直接执行，不再问AI
        if (tryHandleDeviceCommand(text)) {
            return;
        }

        addUserBubble(text);
        hideWelcome();
        input.setText("");
        startStream(text);
    }

    /** 设备语音/文字控制指令识别。识别成功返回 true，不再走 AI 对话。 */
    private boolean tryHandleDeviceCommand(String text) {
        if (getActivity() == null || !(getActivity() instanceof MainActivity)) return false;
        MainActivity ma = (MainActivity) getActivity();
        String t = text.replaceAll("[\\s，。！？,.!?]", "");

        // 历史记录连续对话：确认分析/删除，或在下一句话提供新名称。
        if (pendingAnalysisCaseId != null && isAffirmative(t)) {
            String caseId = pendingAnalysisCaseId;
            pendingAnalysisCaseId = null;
            return analyzeAndReadCase(text, caseId);
        }
        if (pendingFullReportCaseId != null && isAffirmative(t)) {
            String caseId = pendingFullReportCaseId;
            pendingFullReportCaseId = null;
            return readFullReportNow(text, caseId);
        }
        if (pendingDeleteCaseId != null && isAffirmative(t)) {
            String caseId = pendingDeleteCaseId;
            pendingDeleteCaseId = null;
            return deleteCaseConfirmed(text, caseId);
        }
        if ((pendingAnalysisCaseId != null || pendingDeleteCaseId != null
                || pendingFullReportCaseId != null) && isNegative(t)) {
            pendingAnalysisCaseId = null;
            pendingDeleteCaseId = null;
            pendingFullReportCaseId = null;
            addUserBubble(text);
            hideWelcome();
            input.setText("");
            addAiBubble("好的，已取消操作。");
            speakLocalReply("好的，已取消");
            return true;
        }
        if (pendingRenameCaseId != null && !t.isEmpty()) {
            String caseId = pendingRenameCaseId;
            String extracted = extractNewCaseName(text);
            boolean stillLooksLikeCommand = t.contains("修改") || t.contains("重命名")
                    || t.contains("改名") || t.contains("历史记录") || t.matches(".*第[0-9一二三四五六七八九十]+条.*");
            if (!extracted.isEmpty()) {
                pendingRenameCaseId = null;
                return renameCaseNow(text, caseId, extracted);
            }
            if (stillLooksLikeCommand) {
                addUserBubble(text);
                hideWelcome();
                input.setText("");
                addAiBubble("我还没有听到新名称。请只说新的名称，例如：左手腕检测。");
                speakFollowUpQuestion("我还没有听到新名称。请只说新的名称，例如，左手腕检测");
                return true;
            }
            pendingRenameCaseId = null;
            return renameCaseNow(text, caseId, text.trim());
        }

        // 删除、重命名和朗读报告必须优先于普通“打开历史记录”。
        if (t.contains("删除") && (t.contains("历史") || t.contains("记录")
                || t.contains("案例") || t.contains("第") || t.contains("那条") || t.contains("这条"))) {
            return requestDeleteCase(text, t);
        }
        if ((t.contains("重命名") || t.contains("改名") || t.contains("命名为")
                || t.contains("名称为") || (t.contains("修改") && t.contains("名称")))
                && (t.contains("历史") || t.contains("记录") || t.contains("案例")
                || t.contains("第") || t.contains("那条") || t.contains("这条"))) {
            return requestRenameCase(text, t);
        }
        if ((t.contains("读取") || t.contains("朗读") || t.contains("念一下") || t.contains("读一下"))
                && (t.contains("分析") || t.contains("报告"))) {
            return readCaseReportCommand(text, t);
        }
        if ((t.contains("分析") || t.contains("生成报告"))
                && (t.contains("帮我") || t.contains("进行") || t.contains("开始")
                || t.contains("重新") || t.contains("生成"))
                && (t.contains("历史") || t.contains("记录") || t.contains("案例")
                || t.contains("第") || t.contains("那条") || t.contains("这条"))) {
            return analyzeCaseCommand(text, t);
        }

        // 用户是在询问口令，而不是查询已有报告数量，更不能在未确认时直接开始拍摄。
        if ((t.contains("怎么说") || t.contains("如何说") || t.contains("说什么"))
                && (t.contains("拍照") || t.contains("拍摄") || t.contains("全检"))
                && (t.contains("报告") || t.contains("分析"))) {
            addUserBubble(text);
            hideWelcome();
            input.setText("");
            String reply = "你可以直接说：**小农小农，帮我拍照并生成报告。**\n\n"
                    + "我会自动拍摄实时图和热成像图、生成分析报告，并为你播报简要结论。完整报告可在分析报告或历史记录中查看。";
            addAiBubble(reply);
            speakLocalReply("你可以说，小农小农，帮我拍照并生成报告。我会自动拍照、分析并播报简要结论。");
            return true;
        }

        // ===== 查询统计类（本地统计直接回答，不打开页面）=====
        // 例："历史记录页面有几条AI分析报告" → 回答数量，而不是跳转页面
        boolean asksForCount = t.contains("几") || t.contains("多少") || t.contains("哪些")
                || t.contains("几个") || t.contains("有没有");
        if (asksForCount && (t.contains("历史") || t.contains("记录") || t.contains("案例")
                || ((t.contains("分析") || t.contains("报告"))
                && !(t.contains("拍照") || t.contains("拍摄") || t.contains("全检"))))) {
            return answerHistoryStats(text);
        }

        // ===== 打开"刚才/刚刚"拍的案例（多轮意图，优先于"那条=最新一条"的默认） =====
        // 例："刚才那条" / "刚拍的" / "刚刚那个" → 打开最近一次拍摄的案例（全检或同时拍照）
        if (t.contains("刚才") || t.contains("刚刚") || t.contains("刚拍")) {
            return openJustCapturedCommand(text, t);
        }

        // ===== 按时间打开历史条目 =====
        // 例："昨天那条" / "前天拍的" / "上周的案例" / "9月20号那条" → 按时间定位并打开详情
        if (!isQueryLike(t) && hasTimeWord(t)
                && (t.contains("记录") || t.contains("历史") || t.contains("分析")
                || t.contains("案例") || t.contains("拍的") || t.contains("那条") || t.contains("这条"))) {
            return openHistoryByTimeCommand(text, t);
        }

        // ===== 打开指定历史条目 / 某条AI分析 =====
        // 例："打开历史记录里第2条" / "打开最新一条AI分析" / "打开那条历史记录的AI分析" /
        //      "调取某历史记录的AI分析报告"（未指明具体条数时默认打开最新一条）
        // 疑问句（"哪条有分析"、"怎么打开"）不触发，交给 AI 回答。
        if (!isQueryLike(t)
                && (t.contains("第") || t.contains("最新") || t.contains("最近") || t.contains("最后")
                || t.contains("那条") || t.contains("这条") || t.contains("某条") || t.contains("某")
                || t.contains("这个") || t.contains("那个") || t.contains("一条") || t.contains("上一条")
                || t.contains("上次"))
                && (t.contains("记录") || t.contains("历史")
                || t.contains("分析") || t.contains("案例"))) {
            return openHistoryItemCommand(text, t);
        }

        // ===== 准备曝光：由小农选择本次采集模块 =====
        if (!isQueryLike(t) && isExposureModuleSelectionCommand(t)) {
            boolean camera = containsAny(t, "摄像头", "可见光", "实时图");
            boolean thermal = containsAny(t, "热成像", "热传感", "热感", "热像", "红外");
            boolean xray = containsAny(t, "xray", "x-ray", "x光", "X光", "x射线", "X射线", "射线");
            if (containsAny(t, "不要xray", "不要x-ray", "不要x光", "不要X光", "不要射线",
                    "不选xray", "不选X射线", "取消xray", "取消X射线", "去掉xray", "去掉X射线")) {
                xray = false;
            }
            if (camera || thermal || xray) {
                final boolean selectedCamera = camera;
                final boolean selectedThermal = thermal;
                final boolean selectedXray = xray;
                StringBuilder selected = new StringBuilder();
                if (camera) selected.append("可见光");
                if (thermal) appendSelection(selected, "热成像");
                if (xray) appendSelection(selected, "X-Ray");
                return doDeviceCommand(text, "好的，已选择" + selected + "。",
                        () -> ma.selectExposureModulesByCommand(selectedCamera, selectedThermal, selectedXray));
            }
        }

        // ===== 页面切换类 =====
        if (t.contains("打开准备曝光") || t.contains("曝光准备页面")
                || t.contains("打开曝光页面")) {
            return doDeviceCommand(text, "好的，已打开**准备曝光**页面。", ma::openExposurePreparePage);
        }

        // X-Ray 射线源：总开关建立管电流，管电压为定时点动，到时必须自动松开。
        if ((t.contains("停止") || t.contains("关闭") || t.contains("断开"))
                && (t.contains("曝光") || t.contains("射线源") || t.contains("x光") || t.contains("X光"))) {
            addUserBubble(text);
            hideWelcome();
            input.setText("");
            com.weiguang.drcontroller.hardware.xray.Xray433Controller.SendResult result =
                    ma.stopXrayExposureByCommand();
            addAiBubble(result.message);
            speakLocalReply(result.message);
            return true;
        }
        if ((t.contains("开始曝光") || t.contains("开启曝光") || t.contains("开启射线源")
                || t.contains("接通射线源") || t.contains("帮我曝光") || t.contains("曝光一下"))
                && !isQueryLike(t)) {
            addUserBubble(text);
            hideWelcome();
            input.setText("");
            long exposureMillis = parseExposureMillis(t);
            if (exposureMillis < 0) exposureMillis = 500L;
            com.weiguang.drcontroller.hardware.xray.Xray433Controller.SendResult result =
                    ma.startXrayExposureByCommand(exposureMillis);
            addAiBubble(result.message);
            speakLocalReply(result.message);
            return true;
        }
        if (t.contains("打开摄像头") || t.contains("摄像头页面") || t.contains("拍照页面")
                || t.contains("实时图像") || t.contains("打开预览")) {
            return doDeviceCommand(text, "好的，已打开**摄像头**页面。", ma::openCameraPageByCommand);
        }
        // 热成像（含口语变体：热传感摄像头/热感/热像仪/红外）
        if (t.contains("打开热成像") || t.contains("热成像页面") || t.contains("红外")
                || t.contains("热传感") || t.contains("热感") || t.contains("热像")) {
            return doDeviceCommand(text, "好的，已打开**热成像**页面。", ma::openThermalPageByCommand);
        }
        if (t.contains("打开x光") || t.contains("打开X光") || t.contains("x光页面")
                || t.contains("xray") || t.contains("x-ray")
                || t.contains("打开x射线") || t.contains("打开X射线") || t.contains("打开射线")
                || t.contains("x射线页面") || t.contains("X射线页面")) {
            return doDeviceCommand(text, "好的，已打开**X-Ray**影像页面。", ma::openXrayPageByCommand);
        }
        if (t.contains("打开ai") || t.contains("打开AI") || t.contains("ai分析")
                || t.contains("智能分析") || t.contains("打开分析")) {
            return doDeviceCommand(text, "好的，已打开**AI分析**页面。", ma::openAiAnalysisPageByCommand);
        }
        if (t.contains("打开历史") || t.contains("历史记录") || t.contains("查看历史")) {
            return doDeviceCommand(text, "好的，已打开**历史记录**页面。", ma::openHistoryPageByCommand);
        }
        if (t.contains("回到首页") || t.contains("打开首页") || t.contains("设备首页")
                || t.contains("首页") || t.contains("主页") || t.contains("主界面") || t.contains("主页面")) {
            return doDeviceCommand(text, "好的，已回到**设备首页**。", ma::openHomePageByCommand);
        }

        // ===== 一键全检：自动拍摄 + AI 分析（优先于单纯拍照规则） =====
        // 例："帮我拍一下看看" / "全面检查" / "全检" / "看看是什么" / "轮拍" / "拍一遍" / "拍照分析"
        if (t.contains("全检") || t.contains("全面检查") || t.contains("检查一下看看")
                || t.contains("帮我拍") || t.contains("拍个看看") || t.contains("拍个分析")
                || t.contains("拍一下看看") || t.contains("看看是什么") || t.contains("看看是啥")
                || t.contains("轮拍") || t.contains("拍一遍") || t.contains("拍个全面的")
                || ((t.contains("拍照") || t.contains("拍摄"))
                && (t.contains("报告") || t.contains("出报告") || t.contains("生成报告")))
                || ((t.contains("拍") || t.contains("拍照")) && (t.contains("分析") || t.contains("识别")))) {
            // 携带用户对拍摄目标的口语描述（如"我要拍一只白色的小兔子"）作为分析参考
            String hint = text.replaceAll("^(小农|小农助手|助手|帮我|给我|请|你好)[，,。\\s]*", "").trim();
            return doDeviceCommand(text, "好的，小农开始**一键全检**：自动拍摄实时图+热成像，并调用AI多模态分析，稍后为你播报结论。",
                    () -> { if (ma != null) ma.runFullInspectionByCommand(hint); });
        }

        // ===== 拍照控制 =====
        if (t.contains("同时拍照") || t.contains("拍照") || t.contains("拍摄")
                || t.contains("拍一张") || t.contains("拍个照") || t.contains("照张相")
                || (t.contains("拍") && t.contains("热成像"))) {
            return doDeviceCommand(text, "好的，正在**同时拍照**，保存实时图和热成像图。", ma::capturePairedPhotoByCommand);
        }

        // ===== 毫米波雷达 =====
        if (((t.contains("连接雷达") || t.contains("打开雷达") || t.contains("启动雷达")
                || t.contains("连接毫米波") || t.contains("打开毫米波") || t.contains("毫米波雷达"))
                && !t.contains("关闭"))
                && !(t.contains("数据") || t.contains("状态") || t.contains("多少")
                || t.contains("测距") || t.contains("测一下") || t.contains("距离")
                || t.contains("呼吸") || t.contains("心率"))) {
            return doDeviceCommand(text, "好的，正在**连接毫米波雷达**…", ma::connectRadarByCommand);
        }

        // ===== 状态查询 =====
        if (t.contains("雷达数据") || t.contains("当前距离") || t.contains("雷达测距")
                || (t.contains("雷达") && (t.contains("多少") || t.contains("多少厘米")
                || t.contains("测一下") || t.contains("测距")))) {
            com.weiguang.drcontroller.hardware.radar.RadarReading r = ma.getLatestRadarReading();
            String reply = "当前毫米波雷达数据：\n\n"
                    + "- **目标**：" + (r.isConnected() ? r.getTargetStatus() : "未连接/未检测到") + "\n"
                    + "- **距离**：" + r.getDistance() + "\n"
                    + "- **呼吸**：" + r.getBreathRate() + "\n"
                    + "- **心率**：" + r.getHeartRate() + "\n\n"
                    + (r.isConnected() ? "数据时间 " + formatReadableTime(r.getDataTimeMillis())
                    : "雷达未连接，需要我帮你连接吗？");
            addUserBubble(text);
            hideWelcome();
            input.setText("");
            addAiBubble(reply);
            speakLocalReply(r.isConnected() ? "当前距离" + r.getDistance() + "，呼吸" + r.getBreathRate() + "，心率" + r.getHeartRate()
                    : "雷达尚未连接");
            return true;
        }

        if (t.contains("设备状态") || t.contains("连接状态") || t.contains("检查设备")
                || t.contains("一切正常") || t.contains("状态如何") || t.contains("状态怎么样")) {
            StringBuilder reply = new StringBuilder("当前设备状态：\n\n");
            reply.append("- **摄像头**：").append(ma.isCameraConnected() ? "已连接" : "待连接").append("\n");
            reply.append("- **热成像**：").append(ma.isThermalConnected() ? "已连接" : "待连接").append("\n");
            reply.append("- **X-Ray**：").append(ma.isXrayConnected() ? "已连接" : "待连接").append("\n");
            com.weiguang.drcontroller.hardware.radar.RadarReading r = ma.getLatestRadarReading();
            reply.append("- **毫米波雷达**：").append(r.isConnected() ? "已连接" : "待连接");
            addUserBubble(text);
            hideWelcome();
            input.setText("");
            addAiBubble(reply.toString());
            speakLocalReply(ma.isCameraConnected() && ma.isThermalConnected() && ma.isXrayConnected() && r.isConnected()
                    ? "所有设备均已连接" : "部分设备未连接，已显示详细状态");
            return true;
        }

        return false;
    }

    /** 是否疑问句（查询意图，不应触发页面切换） */
    private static boolean isQueryLike(String t) {
        return t.contains("几") || t.contains("多少") || t.contains("哪些")
                || t.contains("什么") || t.contains("为什么") || t.contains("为啥")
                || t.contains("哪条") || t.contains("几个") || t.contains("有没有")
                || t.contains("怎么") || t.contains("如何") || t.contains("在哪")
                || t.contains("哪里") || t.contains("哪儿");
    }

    private static long parseExposureMillis(String text) {
        Matcher numeric = Pattern.compile("(\\d+(?:\\.\\d+)?)秒").matcher(text);
        if (numeric.find()) {
            try {
                return Math.round(Double.parseDouble(numeric.group(1)) * 1000.0);
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        String[] words = {"一", "二", "两", "三"};
        int[] values = {1, 2, 2, 3};
        for (int i = 0; i < words.length; i++) {
            if (text.contains(words[i] + "秒")) return values[i] * 1000L;
        }
        return -1L;
    }

    private static boolean isExposureModuleSelectionCommand(String text) {
        return containsAny(text, "选择采集", "选择模块", "选择摄像头", "选择可见光", "选择热成像",
                "选择xray", "选择X-Ray", "选择X射线", "只拍摄", "只拍可见光", "只拍热成像", "只拍X射线");
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static void appendSelection(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append("、");
        builder.append(value);
    }

    private static boolean isAffirmative(String t) {
        return t.equals("需要") || t.equals("要") || t.equals("是") || t.equals("确认")
                || t.contains("需要分析") || t.contains("开始分析") || t.contains("帮我分析")
                || t.contains("可以") || t.contains("好的");
    }

    private static boolean isNegative(String t) {
        return t.contains("不需要") || t.contains("不要") || t.contains("取消") || t.contains("算了");
    }

    private boolean readCaseReportCommand(String userText, String t) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        final int target = parseOrdinal(t);
        new Thread(() -> {
            List<CaseRepository.CaseRecord> records =
                    CaseRepository.queryCases(requireContext().getApplicationContext(), 0);
            int index = target <= 0 ? -1 : target - 1;
            if (index < 0 || index >= records.size()) {
                mainHandler.post(() -> {
                    String reply = target > 0 ? "没有找到第 " + target + " 条历史记录。"
                            : "请告诉我要读取第几条历史记录的AI分析报告。";
                    addAiBubble(reply);
                    speakLocalReply(reply);
                });
                return;
            }
            CaseRepository.CaseRecord record = records.get(index);
            mainHandler.post(() -> {
                if (record.hasAiReport()) {
                    String summary = buildInspectionSpokenSummary(record.getAiReport());
                    addAiBubble("第 " + (index + 1) + " 条历史记录的报告简要概括：\n\n"
                            + summary + "\n\n需要我继续完整读取AI分析报告吗？");
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).openAiReport(record.getCaseId());
                    }
                    CaseRepository.markAiReportRead(requireContext(), record.getCaseId());
                    pendingFullReportCaseId = record.getCaseId();
                    speakFollowUpQuestion(summary + "。需要我继续完整读取AI分析报告吗");
                } else {
                    pendingAnalysisCaseId = record.getCaseId();
                    String reply = "第 " + (index + 1) + " 条历史记录还没有AI分析报告。需要我现在分析，完成后为你读取吗？";
                    addAiBubble(reply);
                    speakFollowUpQuestion(reply);
                }
            });
        }, "history-read-report").start();
        return true;
    }

    private boolean analyzeAndReadCase(String userText, String caseId) {
        if (userText != null && !userText.trim().isEmpty()) {
            addUserBubble(userText);
            hideWelcome();
            input.setText("");
        }
        CaseRepository.CaseRecord record = CaseRepository.findCase(requireContext(), caseId);
        if (record == null) {
            addAiBubble("这条历史记录已经不存在了。");
            speakLocalReply("这条历史记录已经不存在了");
            return true;
        }
        addAiBubble("好的，正在为这条历史记录生成AI分析报告，完成后我会自动读取。");
        speakLocalReply("好的，正在分析，完成后我会为你读取");
        AiAnalyzer.analyzeCase(requireContext().getApplicationContext(), record, null,
                new AiAnalyzer.Callback() {
                    @Override public void onSuccess(String report) {
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            String summary = buildInspectionSpokenSummary(report);
                            addAiBubble("AI分析已完成。简要概括：\n\n" + summary
                                    + "\n\n需要我继续完整读取AI分析报告吗？");
                            if (getActivity() instanceof MainActivity) {
                                ((MainActivity) getActivity()).openAiReport(caseId);
                            }
                            pendingFullReportCaseId = caseId;
                            speakFollowUpQuestion(summary + "。需要我继续完整读取AI分析报告吗");
                        });
                    }

                    @Override public void onError(String error) {
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            String reply = "AI分析失败：" + error;
                            addAiBubble(reply);
                            speakLocalReply(reply);
                        });
                    }
                });
        return true;
    }

    private boolean analyzeCaseCommand(String userText, String t) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        int target = parseOrdinal(t);
        List<CaseRepository.CaseRecord> records = CaseRepository.queryCases(requireContext(), 0);
        if (target <= 0 || target > records.size()) {
            String reply = "请明确告诉我要分析第几条历史记录。";
            addAiBubble(reply);
            speakFollowUpQuestion(reply);
            return true;
        }
        return analyzeAndReadCase(null, records.get(target - 1).getCaseId());
    }

    private boolean readFullReportNow(String userText, String caseId) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        CaseRepository.CaseRecord record = CaseRepository.findCase(requireContext(), caseId);
        if (record == null || !record.hasAiReport()) {
            addAiBubble("这条历史记录的AI分析报告已不存在。");
            speakLocalReply("这条历史记录的AI分析报告已不存在");
            return true;
        }
        addAiBubble("好的，开始完整读取AI分析报告。你可以说“小农小农”随时打断我。");
        speakReportText(record.getAiReport());
        return true;
    }

    private boolean requestDeleteCase(String userText, String t) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        int target = parseOrdinal(t);
        List<CaseRepository.CaseRecord> records = CaseRepository.queryCases(requireContext(), 0);
        if (target <= 0 || target > records.size()) {
            String reply = "请明确告诉我要删除第几条历史记录。";
            addAiBubble(reply);
            speakLocalReply(reply);
            return true;
        }
        CaseRepository.CaseRecord record = records.get(target - 1);
        pendingDeleteCaseId = record.getCaseId();
        String reply = "删除后无法恢复。确认删除第 " + target + " 条历史记录吗？请回答确认或取消。";
        addAiBubble(reply);
        speakFollowUpQuestion(reply);
        return true;
    }

    private boolean deleteCaseConfirmed(String userText, String caseId) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        CaseRepository.CaseRecord record = CaseRepository.findCase(requireContext(), caseId);
        if (record == null) {
            addAiBubble("这条历史记录已经不存在了。");
            return true;
        }
        CaseRepository.deleteCase(requireContext(), record, new CaseRepository.DeleteCallback() {
            @Override public void onDeleted() {
                addAiBubble("历史记录已删除。");
                speakLocalReply("历史记录已删除");
            }
            @Override public void onError(String message) {
                addAiBubble(message);
                speakLocalReply(message);
            }
        });
        return true;
    }

    private boolean requestRenameCase(String userText, String t) {
        int target = parseOrdinal(t);
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        List<CaseRepository.CaseRecord> records = CaseRepository.queryCases(requireContext(), 0);
        if (target <= 0 || target > records.size()) {
            String reply = "请明确告诉我要修改第几条历史记录的名称。";
            addAiBubble(reply);
            speakLocalReply(reply);
            return true;
        }
        String newName = extractNewCaseName(userText);
        String caseId = records.get(target - 1).getCaseId();
        if (newName.isEmpty()) {
            pendingRenameCaseId = caseId;
            addAiBubble("请告诉我新的名称。你可以直接说，例如：左手腕检测。 ");
            speakFollowUpQuestion("请告诉我新的名称");
            return true;
        }
        return renameCaseNow(null, caseId, newName);
    }

    private boolean renameCaseNow(String userText, String caseId, String newName) {
        if (userText != null) {
            addUserBubble(userText);
            hideWelcome();
            input.setText("");
        }
        String clean = newName == null ? "" : newName.trim();
        if (clean.length() > 20) clean = clean.substring(0, 20);
        CaseRepository.setCaseName(requireContext(), caseId, clean);
        String reply = "已将这条历史记录重命名为“" + clean + "”。";
        addAiBubble(reply);
        speakLocalReply(reply);
        return true;
    }

    private static String extractNewCaseName(String text) {
        if (text == null) return "";
        Matcher matcher = Pattern.compile("(?:修改名称为|名称为|重命名为|改名为|命名为|改成|叫做|叫)([^，。！？,.!?]+)").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private void speakReportText(String report) {
        if (report == null || report.trim().isEmpty()) return;
        String plain = stripEmoji(report).replaceAll("[*#`]+", "")
                .replaceAll("\\s+", " ").trim();
        if (plain.length() > 1800) plain = plain.substring(0, 1800) + "。其余内容请在报告页面查看";
        List<String> chunks = new java.util.ArrayList<>();
        while (!plain.isEmpty()) {
            int end = Math.min(180, plain.length());
            if (end < plain.length()) {
                int split = Math.max(plain.lastIndexOf('。', end), plain.lastIndexOf('；', end));
                if (split > 60) end = split + 1;
            }
            chunks.add(plain.substring(0, end));
            plain = plain.substring(end).trim();
        }
        if (wakeEnabled) wakeState = WSTATE_WAIT_TTS;
        for (int i = 0; i < chunks.size(); i++) {
            boolean last = i == chunks.size() - 1;
            speakText(chunks.get(i), last ? () -> {
                if (wakeEnabled) wakeState = WSTATE_LISTEN;
            } : null);
        }
    }

    /** 播报一个追问，播完后直接收听回答，不要求用户再次说唤醒词。 */
    private void speakFollowUpQuestion(String text) {
        if (!wakeEnabled) {
            speakText(text);
            return;
        }
        wakeState = WSTATE_WAIT_TTS;
        speakText(text, () -> {
            if (!wakeEnabled) return;
            wakeBuffer.reset();
            silentFrames = 0;
            questionVoicedFrames = 0;
            wakeTrimPoint = 0;
            wakeTtsStartMs = System.currentTimeMillis();
            wakeTtsDoneMs = wakeTtsStartMs;
            wakeState = WSTATE_RECORD;
            statusText.setText("● 等待回答");
            statusText.setTextColor(Color.parseColor("#FFB74D"));
        });
    }

    /** 本地统计历史/AI分析数量并直接回答，避免误触发"打开历史记录"页面 */
    private boolean answerHistoryStats(String userText) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        new Thread(() -> {
            try {
                List<CaseRepository.CaseRecord> records =
                        CaseRepository.queryCases(requireContext().getApplicationContext(), 0);
                int total = records.size();
                int withAi = 0;
                int withXray = 0;
                for (CaseRepository.CaseRecord r : records) {
                    if (r.hasAiReport()) withAi++;
                    if (r.hasXrayImage()) withXray++;
                }
                final String reply = "当前历史记录共有 **" + total + " 条案例**，其中 **"
                        + withAi + " 条**有AI分析报告，**" + withXray + " 条**包含X-Ray影像。";
                final String spoken = "当前历史记录共有" + total + "条案例，其中" + withAi + "条有AI分析报告，"
                        + withXray + "条包含X光影像。";
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    addAiBubble(reply);
                    speakLocalReply(spoken);
                });
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    addAiBubble("查询历史记录数量时出错，请稍后重试。");
                });
            }
        }, "history-stats").start();
        return true;
    }

    /** 打开历史记录中的指定条目（第N条/最新一条）或某条AI分析 */
    private boolean openHistoryItemCommand(String userText, String t) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        final int target = parseOrdinal(t);
        final boolean wantAi = t.contains("分析") || t.contains("报告");
        new Thread(() -> {
            try {
                List<CaseRepository.CaseRecord> records =
                        CaseRepository.queryCases(requireContext().getApplicationContext(), 0);
                final int safeTarget = (target <= 0 || target > records.size()) ? -1 : target;
                if (safeTarget < 0) {
                    final String failReply = "目前共有 " + records.size() + " 条案例"
                            + (target > 0 ? "，没有找到第 " + target + " 条" : "，请说\"第几条\"或\"最新一条\"");
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        addAiBubble(failReply);
                        speakLocalReply(failReply);
                    });
                    return;
                }
                final CaseRepository.CaseRecord rec = records.get(safeTarget - 1);
                final String caseId = rec.getCaseId();
                final String label = (rec.getDisplayName() == null || rec.getDisplayName().trim().isEmpty())
                        ? formatReadableTime(rec.getTimeMillis())
                        : rec.getDisplayName();
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    if (wantAi && !rec.hasAiReport()) {
                        pendingAnalysisCaseId = caseId;
                        String reply = "第 " + safeTarget + " 条历史记录还没有AI分析报告。需要我现在分析吗？";
                        addAiBubble(reply);
                        speakFollowUpQuestion(reply);
                        return;
                    }
                    String reply = "已打开第 " + safeTarget + " 条"
                            + (wantAi ? "的分析报告" : "历史记录") + "（" + label + "）。";
                    addAiBubble(reply);
                    speakLocalReply("已打开第" + safeTarget + "条" + (wantAi ? "的分析报告" : "历史记录"));
                    if (getActivity() instanceof MainActivity) {
                        MainActivity ma = (MainActivity) getActivity();
                        if (wantAi) ma.openAiReport(caseId);
                        else ma.openCaseDetail(caseId);
                    }
                });
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    addAiBubble("打开历史记录失败，请稍后重试。");
                });
            }
        }, "history-open").start();
        return true;
    }

    /** 打开"刚才/刚刚"拍摄的案例（多轮意图）：最近一次同时拍照或一键全检生成的案例 */
    private boolean openJustCapturedCommand(String userText, String t) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        if (getActivity() instanceof MainActivity) {
            MainActivity ma = (MainActivity) getActivity();
            String caseId = ma.getLastCapturedCaseId();
            if (caseId == null) {
                String reply = "你还没有拍过照片，可以对我说\"帮我拍一下看看\"来一键全检。";
                addAiBubble(reply);
                speakLocalReply("你还没有拍过照片，可以说帮我拍一下看看");
                return true;
            }
            addAiBubble("好的，已为你打开**刚才拍摄**的案例。");
            speakLocalReply("好的，已为你打开刚才拍摄的案例");
            ma.openCaseDetail(caseId);
        }
        return true;
    }

    /** 按时间筛选并打开历史条目："昨天那条" / "上周拍的" / "9月20号那条" */
    private boolean openHistoryByTimeCommand(String userText, String t) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        final TimeFilter filter = parseTimeFilter(t);
        final String when = t.contains("上上周") ? "上上周"
                : t.contains("上周") ? "上周"
                : t.contains("前天") ? "前天"
                : t.contains("昨天") ? "昨天"
                : t.contains("今天") ? "今天"
                : (t.contains("这周") || t.contains("本周")) ? "本周" : "该时间段";
        new Thread(() -> {
            try {
                List<CaseRepository.CaseRecord> records =
                        CaseRepository.queryCases(requireContext().getApplicationContext(), 0);
                CaseRepository.CaseRecord found = null;
                if (filter != null) {
                    for (CaseRepository.CaseRecord r : records) {
                        if (filter.matches(r.getTimeMillis())) {
                            found = r;
                            break;
                        }
                    }
                }
                if (found == null) {
                    final String reply = "没有找到" + when + "拍摄的案例"
                            + (filter == null ? "，可以换个说法试试" : "，换个时间试试");
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        addAiBubble(reply);
                        speakLocalReply(reply);
                    });
                    return;
                }
                final CaseRepository.CaseRecord rec = found;
                final String label = (rec.getDisplayName() == null || rec.getDisplayName().trim().isEmpty())
                        ? formatReadableTime(rec.getTimeMillis())
                        : rec.getDisplayName();
                final String reply = "已为你打开" + when + "拍摄的案例（" + label + "）"
                        + (rec.hasAiReport() ? "，该案例已有AI分析报告。" : "。");
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    addAiBubble(reply);
                    speakLocalReply("已为你打开" + when + "拍摄的案例");
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).openCaseDetail(rec.getCaseId());
                    }
                });
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    addAiBubble("按时间查找案例失败，请稍后重试。");
                });
            }
        }, "history-time-open").start();
        return true;
    }

    /** 是否包含时间词（今天/昨天/前天/上周/本周/这周/上上周/X月X日） */
    private static boolean hasTimeWord(String t) {
        return t.contains("今天") || t.contains("昨天") || t.contains("前天")
                || t.contains("上周") || t.contains("上上周")
                || t.contains("本周") || t.contains("这周")
                || java.util.regex.Pattern.compile("\\d{1,2}月\\d{1,2}[日号]").matcher(t).find();
    }

    /** 解析时间筛选区间；解析不了返回 null */
    private static TimeFilter parseTimeFilter(String t) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]").matcher(t);
        if (dm.find()) {
            int month = Integer.parseInt(dm.group(1));
            int day = Integer.parseInt(dm.group(2));
            java.util.Calendar start = (java.util.Calendar) now.clone();
            start.set(java.util.Calendar.MONTH, month - 1);
            start.set(java.util.Calendar.DAY_OF_MONTH, day);
            start.set(java.util.Calendar.HOUR_OF_DAY, 0);
            start.set(java.util.Calendar.MINUTE, 0);
            start.set(java.util.Calendar.SECOND, 0);
            start.set(java.util.Calendar.MILLISECOND, 0);
            java.util.Calendar end = (java.util.Calendar) start.clone();
            end.set(java.util.Calendar.HOUR_OF_DAY, 23);
            end.set(java.util.Calendar.MINUTE, 59);
            end.set(java.util.Calendar.SECOND, 59);
            end.set(java.util.Calendar.MILLISECOND, 999);
            return new TimeFilter(start, end);
        }
        if (t.contains("昨天")) {
            java.util.Calendar start = (java.util.Calendar) now.clone();
            start.add(java.util.Calendar.DAY_OF_MONTH, -1);
            start.set(java.util.Calendar.HOUR_OF_DAY, 0);
            start.set(java.util.Calendar.MINUTE, 0);
            start.set(java.util.Calendar.SECOND, 0);
            start.set(java.util.Calendar.MILLISECOND, 0);
            java.util.Calendar end = (java.util.Calendar) now.clone();
            end.set(java.util.Calendar.HOUR_OF_DAY, 0);
            end.set(java.util.Calendar.MINUTE, 0);
            end.set(java.util.Calendar.SECOND, 0);
            end.set(java.util.Calendar.MILLISECOND, 0);
            return new TimeFilter(start, end);
        }
        if (t.contains("前天")) {
            java.util.Calendar start = (java.util.Calendar) now.clone();
            start.add(java.util.Calendar.DAY_OF_MONTH, -2);
            start.set(java.util.Calendar.HOUR_OF_DAY, 0);
            start.set(java.util.Calendar.MINUTE, 0);
            start.set(java.util.Calendar.SECOND, 0);
            start.set(java.util.Calendar.MILLISECOND, 0);
            java.util.Calendar end = (java.util.Calendar) now.clone();
            end.add(java.util.Calendar.DAY_OF_MONTH, -1);
            end.set(java.util.Calendar.HOUR_OF_DAY, 0);
            end.set(java.util.Calendar.MINUTE, 0);
            end.set(java.util.Calendar.SECOND, 0);
            end.set(java.util.Calendar.MILLISECOND, 0);
            return new TimeFilter(start, end);
        }
        if (t.contains("上上周")) {
            java.util.Calendar thisMonday = mondayOf(now);
            java.util.Calendar start = (java.util.Calendar) thisMonday.clone();
            start.add(java.util.Calendar.DAY_OF_MONTH, -14);
            java.util.Calendar end = (java.util.Calendar) thisMonday.clone();
            end.add(java.util.Calendar.DAY_OF_MONTH, -7);
            return new TimeFilter(start, end);
        }
        if (t.contains("上周")) {
            java.util.Calendar thisMonday = mondayOf(now);
            java.util.Calendar start = (java.util.Calendar) thisMonday.clone();
            start.add(java.util.Calendar.DAY_OF_MONTH, -7);
            java.util.Calendar end = (java.util.Calendar) thisMonday.clone();
            return new TimeFilter(start, end);
        }
        if (t.contains("本周") || t.contains("这周")) {
            java.util.Calendar start = mondayOf(now);
            java.util.Calendar end = (java.util.Calendar) now.clone();
            return new TimeFilter(start, end);
        }
        if (t.contains("今天")) {
            java.util.Calendar start = (java.util.Calendar) now.clone();
            start.set(java.util.Calendar.HOUR_OF_DAY, 0);
            start.set(java.util.Calendar.MINUTE, 0);
            start.set(java.util.Calendar.SECOND, 0);
            start.set(java.util.Calendar.MILLISECOND, 0);
            java.util.Calendar end = (java.util.Calendar) now.clone();
            return new TimeFilter(start, end);
        }
        return null;
    }

    /** 本周一 0 点 */
    private static java.util.Calendar mondayOf(java.util.Calendar c) {
        java.util.Calendar m = (java.util.Calendar) c.clone();
        m.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY);
        m.set(java.util.Calendar.HOUR_OF_DAY, 0);
        m.set(java.util.Calendar.MINUTE, 0);
        m.set(java.util.Calendar.SECOND, 0);
        m.set(java.util.Calendar.MILLISECOND, 0);
        return m;
    }

    /** 时间区间（含端点） */
    private static class TimeFilter {
        final long startMillis;
        final long endMillis;

        TimeFilter(java.util.Calendar start, java.util.Calendar end) {
            this.startMillis = start.getTimeInMillis();
            this.endMillis = end.getTimeInMillis() + 999L;
        }

        boolean matches(long timeMillis) {
            return timeMillis >= startMillis && timeMillis <= endMillis;
        }
    }

    /** 解析"第N条/第一条/最新一条"，返回序号（1 起）；无法解析返回 -1 */
    private static int parseOrdinal(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("第([0-9一二三四五六七八九十]+)条").matcher(t);
        if (m.find()) {
            String s = m.group(1);
            if (s.matches("\\d+")) {
                try { return Integer.parseInt(s); } catch (Exception ignored) { }
            }
            return chineseToNumber(s);
        }
        if (t.contains("最新") || t.contains("最近") || t.contains("最后")) {
            return 1;
        }
        if (t.contains("那条") || t.contains("这条") || t.contains("某条") || t.contains("某")
                || t.contains("这个") || t.contains("那个") || t.contains("一条")
                || t.contains("上一条") || t.contains("上次")) {
            // 无具体序号的口语指代（"那条/这条/某条/上次那条"等）：
            // 无上下文时默认打开最新一条（倒序第1条）
            return 1;
        }
        return -1;
    }

    /** 中文数字转阿拉伯数字（一到九十九） */
    private static int chineseToNumber(String s) {
        if (s == null || s.isEmpty()) return -1;
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (s.length() == 1) {
            for (int i = 0; i < digits.length; i++) if (digits[i].equals(s)) return i;
            return -1;
        }
        int result = 0;
        boolean found = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '十') {
                result = (result == 0 ? 1 : result) * 10;
                found = true;
            } else {
                for (int d = 0; d < digits.length; d++) {
                    if (digits[d].charAt(0) == c) {
                        result += d;
                        found = true;
                        break;
                    }
                }
            }
        }
        return found ? result : -1;
    }

    /** 执行指令：上屏用户指令 → 显示确认 → 播报确认 → 执行动作 */
    private boolean doDeviceCommand(String userText, String ack, Runnable action) {
        addUserBubble(userText);
        hideWelcome();
        input.setText("");
        addAiBubble(ack);
        speakLocalReply(ack.replaceAll("[*#`\\-]", ""));
        action.run();
        return true;
    }

    /**
     * 播报本地回复；唤醒会话中播报完成后自动恢复唤醒监听，
     * 避免状态卡在 WAIT_TTS（该状态会丢弃音频，导致后续叫唤无响应）。
     */
    private void speakLocalReply(String text) {
        speakInterruptibleText(text);
    }

    /** 播报时继续监听连续两次唤醒词；命中后立即停止当前语音并重新聆听。 */
    private void speakInterruptibleText(String text) {
        if (wakeEnabled) {
            wakeState = WSTATE_WAIT_TTS;
        }
        speakText(text, () -> {
            if (wakeEnabled && wakeState == WSTATE_WAIT_TTS) {
                wakeState = WSTATE_LISTEN;
                statusText.setText("● 在线");
                statusText.setTextColor(Color.parseColor("#70D69B"));
            }
        });
    }

    private String formatReadableTime(long millis) {
        if (millis <= 0L) return "--";
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(millis));
    }

    // ==================== 语音输入（百度 ASR） ====================

    private void startVoiceRecording() {
        if (streaming) {
            Toast.makeText(requireContext(), "小农正在回答，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (BAIDU_ASR_API_KEY.startsWith("填写") || BAIDU_ASR_SECRET_KEY.startsWith("填写")) {
            Toast.makeText(requireContext(), "请先在 ChatFragment.java 中配置百度语音 API Key/Secret Key", Toast.LENGTH_LONG).show();
            return;
        }
        if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_VOICE_PERMISSION);
            return;
        }

        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    VOICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            voiceRecorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    VOICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, 6400)
            );
            if (voiceRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(requireContext(), "录音初始化失败", Toast.LENGTH_SHORT).show();
                voiceRecording = false;
                return;
            }
            voiceRecording = true;
            voicePcmSize = 0;
            voicePcmBuffer = new ByteArrayOutputStream();
            voiceRecorder.startRecording();
            // 读取实际采样率：部分设备(如RK3566)请求16000但实际输出44100/48000，上传前需重采样
            voiceActualRate = 16000;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try { voiceActualRate = voiceRecorder.getSampleRate(); } catch (Exception ignored) {}
            }
            if (voiceActualRate <= 0) voiceActualRate = 16000;
            voiceThread = new Thread(() -> {
                short[] buf = new short[640]; // 40ms @16k
                while (voiceRecording) {
                    int n = voiceRecorder.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    byte[] bytes = shortToBytes(buf, n);
                    synchronized (voicePcmBuffer) {
                        try { voicePcmBuffer.write(bytes); } catch (Exception ignored) {}
                        voicePcmSize = voicePcmBuffer.size();
                    }
                    // 55 秒上限，防止一直录
                    if (voicePcmSize > VOICE_SAMPLE_RATE * 2 * 55) {
                        mainHandler.post(ChatFragment.this::stopVoiceRecording);
                        break;
                    }
                }
            }, "voice-record");
            voiceThread.start();

            // 录音中样式
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor("#D32F2F"));
            micBtn.setBackground(bg);
            micBtn.setText("●");
            statusText.setText("● 聆听中");
            statusText.setTextColor(Color.parseColor("#FF8A80"));
            Toast.makeText(requireContext(), "开始聆听，再点一下结束", Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(voiceTimeout, 55000);
            // 唤醒模式下自动检测静音，说完自动停
            if (autoSendOnWake) {
                silenceLastSize = 0;
                silenceSameCount = 0;
                mainHandler.postDelayed(silenceCheck, 500);
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "录音启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            voiceRecording = false;
            if (voiceRecorder != null) {
                try { voiceRecorder.release(); } catch (Exception ignored) {}
                voiceRecorder = null;
            }
        }
    }

    private void stopVoiceRecording() {
        voiceRecording = false;
        mainHandler.removeCallbacks(voiceTimeout);
        mainHandler.removeCallbacks(silenceCheck);
        if (voiceRecorder != null) {
            try { voiceRecorder.stop(); } catch (Exception ignored) {}
            voiceRecorder.release();
            voiceRecorder = null;
        }
        if (voiceThread != null) {
            try { voiceThread.join(500); } catch (InterruptedException ignored) {}
            voiceThread = null;
        }
        // 恢复按钮样式
        micBtn.setBackgroundResource(R.drawable.bg_mic_btn);
        micBtn.setText("🎤");
        statusText.setText("● 识别中");
        statusText.setTextColor(Color.parseColor("#FFB74D"));

        byte[] pcm = voicePcmBuffer == null ? null : voicePcmBuffer.toByteArray();
        if (pcm != null && pcm.length > 0) {
            recognizeVoice(pcm);
        } else {
            statusText.setText("● 在线");
            statusText.setTextColor(Color.parseColor("#70D69B"));
            Toast.makeText(requireContext(), "没有录到声音，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    /** 后台线程：上传 16k PCM 到百度短语音识别，结果填入输入框 */
    private void recognizeVoice(byte[] pcm) {
        new Thread(() -> {
            try {
                // 按实际采样率重采样到16k，避免设备原生采样率(44100/48000)导致3302
                byte[] up = resample16k(pcm, voiceActualRate);
                String token = getBaiduToken();
                JSONObject json = requestAsr(up, token);
                // 换过 Secret Key 后旧 token 失效(110)，强制刷新 token 后重试一次
                if (isTokenError(json)) {
                    json = requestAsr(up, getBaiduToken(true));
                }
                int errNo = json.optInt("err_no", -1);
                if (errNo == 0) {
                        String text = json.getJSONArray("result").getString(0);
                        mainHandler.post(() -> {
                            statusText.setText("● 在线");
                            statusText.setTextColor(Color.parseColor("#70D69B"));
                            if (autoSendOnWake && !text.trim().isEmpty()) {
                            autoSendOnWake = false;
                            input.setText(text);
                            sendMessage(text);
                        } else {
                            input.setText(text);
                            input.setSelection(text.length());
                        }
                            Toast.makeText(requireContext(), "识别完成，确认后发送", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        final JSONObject errJson = json;
                        mainHandler.post(() -> {
                            statusText.setText("● 在线");
                            statusText.setTextColor(Color.parseColor("#70D69B"));
                            Toast.makeText(requireContext(),
                                    "识别失败(" + errNo + ")：" + errJson.optString("err_msg")
                                            + " 时长" + (up.length / 32000.0) + "s",
                                    Toast.LENGTH_LONG).show();
                            // 识别失败也恢复唤醒监听
                            if (wakeSessionActive) {
                                wakeSessionActive = false;
                                if (wakeEnabled) startWakeListening();
                            }
                        });
                    }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    statusText.setText("● 在线");
                    statusText.setTextColor(Color.parseColor("#70D69B"));
                    Toast.makeText(requireContext(), "识别异常：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "baidu-asr").start();
    }

    /** 获取/缓存百度 access_token（有效期30天，缓存25天） */
    private String getBaiduToken() throws Exception {
        return getBaiduToken(false);
    }

    /** force=true 时忽略缓存强制刷新（用于 Secret Key 轮换后旧 token 失效的场景） */
    private String getBaiduToken(boolean force) throws Exception {
        SharedPreferences prefs = requireContext().getSharedPreferences("baidu_asr", Context.MODE_PRIVATE);
        if (!force) {
            String cached = prefs.getString("token", "");
            long expires = prefs.getLong("expires", 0L);
            if (!cached.isEmpty() && expires > System.currentTimeMillis()) {
                return cached;
            }
        }
        String url = BAIDU_TOKEN_URL
                + "?grant_type=client_credentials"
                + "&client_id=" + BAIDU_ASR_API_KEY
                + "&client_secret=" + BAIDU_ASR_SECRET_KEY;
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .build();
        try (Response resp = speechClient.newCall(request).execute()) {
            JSONObject json = new JSONObject(resp.body().string());
            String token = json.getString("access_token");
            prefs.edit()
                    .putString("token", token)
                    .putLong("expires", System.currentTimeMillis() + 25L * 24L * 3600L * 1000L)
                    .apply();
            return token;
        }
    }

    /** 上传 16k PCM 到百度短语音识别，返回响应 JSON */
    private JSONObject requestAsr(byte[] up, String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("format", "pcm");
        body.put("rate", 16000);
        body.put("channel", 1);
        body.put("dev_pid", 1537);
        body.put("cuid", BAIDU_CUID);
        body.put("token", token);
        body.put("speech", Base64.encodeToString(up, Base64.NO_WRAP));
        body.put("len", up.length);
        Request request = new Request.Builder()
                .url(BAIDU_ASR_URL)
                .post(RequestBody.create(
                        body.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                ))
                .build();
        try (Response resp = speechClient.newCall(request).execute()) {
            return new JSONObject(resp.body().string());
        }
    }

    /** 百度返回的 token 失效类错误（110 或 err_msg 含 token） */
    private boolean isTokenError(JSONObject json) {
        if (json == null) return false;
        int errNo = json.optInt("err_no", -1);
        String msg = json.optString("err_msg", "").toLowerCase();
        return errNo == 110 || msg.contains("token");
    }

    private void speakText(String text) {
        speakText(text, null);
    }

    /** 播报文字，播完回调 onDone（可为 null）；任何失败都会回调 onDone，绝不卡死状态机。
     *  多段播报进入队列串行播放，前一段播完才播下一段，不再互相打断。 */
    private void speakText(String text, Runnable onDone) {
        if (!ttsEnabled || text == null || text.isEmpty()) {
            if (onDone != null) mainHandler.post(onDone);
            return;
        }
        String plain = stripEmoji(text).replaceAll("[*#`\\-]", "").trim();
        final String spoken;
        if (plain.length() > 200) {
            spoken = plain.substring(0, 200);
        } else {
            spoken = plain;
        }
        if (spoken.isEmpty()) {
            if (onDone != null) mainHandler.post(onDone);
            return;
        }
        ttsQueue.addLast(new TtsTask(spoken, onDone, ttsGeneration));
        playNextTts();
    }

    /** 取出队首任务开始下载播放；队列为空时静默结束（仅主线程调用） */
    private void playNextTts() {
        if (ttsPlaying) return;
        final TtsTask task = ttsQueue.pollFirst();
        if (task == null) return;
        ttsPlaying = true;
        byte[] cached = shortTtsCache.get(task.spoken);
        if (cached != null && cached.length > 0) {
            playTtsAudio(cached, () -> finishTtsTask(task, task.onDone));
            return;
        }
        new Thread(() -> {
            try {
                byte[] audioData = downloadTtsAudio(task.spoken);
                if (task.spoken.length() <= 8 && audioData.length > 0) {
                    shortTtsCache.put(task.spoken, audioData);
                }
                mainHandler.post(() -> {
                    if (task.generation != ttsGeneration) return;
                    playTtsAudio(audioData, () -> finishTtsTask(task, task.onDone));
                });
            } catch (Exception ignored) {
                if (task.generation == ttsGeneration) finishTtsTask(task, task.onDone);
            }
        }, "baidu-tts").start();
    }

    /** 开机后预取“我在”，首次唤醒也能立即播放，不再临时等待 TTS 网络。 */
    private void preloadWakeAckAudio() {
        new Thread(() -> {
            try {
                byte[] audio = downloadTtsAudio("我在");
                if (audio.length > 0) shortTtsCache.put("我在", audio);
            } catch (Exception ignored) {
            }
        }, "wake-tts-preload").start();
    }

    private byte[] downloadTtsAudio(String spoken) throws Exception {
        String token = getBaiduToken();
        String url = BAIDU_TTS_URL
                + "?tex=" + java.net.URLEncoder.encode(spoken, "UTF-8")
                + "&tok=" + token
                + "&cuid=" + BAIDU_CUID
                + "&ctp=1&lan=zh&spd=5&pit=5&vol=5&per=0";
        Request request = new Request.Builder().url(url).get().build();
        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("TTS HTTP " + resp.code());
            }
            try (InputStream is = resp.body().byteStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        }
    }

    /** 任务结束统一出口：回到主线程回调 onDone 后播下一个任务，保证状态机不卡死 */
    private void finishTtsTask(TtsTask task, Runnable onDone) {
        mainHandler.post(() -> {
            if (task.generation != ttsGeneration) return;
            ttsPlaying = false;
            if (onDone != null) onDone.run();
            playNextTts();
        });
    }

    /** 去除 TTS 无法合成的 emoji/表情符号（百度 TTS 遇到 emoji 会合成失败，导致播报中断） */
    private static String stripEmoji(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 丢弃代理对（绝大部分 emoji 位于增补平面）
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) continue;
            // 杂项符号/装饰符号区（BMP emoji，如 ☀ ☁ ♥ ★ ➤）
            if (c >= 0x2600 && c <= 0x27BF) continue;
            // 变体选择符（VS15/VS16 等，emoji 组合用）
            if (c >= 0xFE00 && c <= 0xFE0F) continue;
            // 零宽连接符（emoji 序列连接）
            if (c == 0x200D) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private void playTtsAudio(byte[] audioData) {
        playTtsAudio(audioData, null);
    }

    private void playTtsAudio(byte[] audioData, Runnable onDone) {
        try {
            if (ttsPlayer != null) {
                ttsPlayer.stop();
                ttsPlayer.release();
                ttsPlayer = null;
            }
            File tempFile = new File(requireContext().getCacheDir(), "tts_temp.mp3");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioData);
            }
            ttsPlayer = new MediaPlayer();
            ttsPlayer.setDataSource(tempFile.getAbsolutePath());
            ttsPlayer.setOnCompletionListener(mp -> {
                mp.release();
                ttsPlayer = null;
                if (onDone != null) onDone.run();
            });
            ttsPlayer.prepare();
            ttsPlayer.start();
        } catch (Exception ignored) {
            if (onDone != null) onDone.run();
        }
    }

    /** 立即停止语音播报（闭嘴按钮），并取消尚未播报的排队任务 */
    private void stopTts() {
        // 使正在下载但尚未播放的旧任务失效，保证语音打断后不会“又自己说起来”。
        ttsGeneration++;
        if (ttsPlayer != null) {
            try { ttsPlayer.stop(); } catch (Exception ignored) {}
            try { ttsPlayer.release(); } catch (Exception ignored) {}
            ttsPlayer = null;
        }
        // 清空排队任务，未播报任务全部取消。
        ttsQueue.clear();
        ttsPlaying = false;
        // 如果因为停播导致 onDone 不会触发，这里兜底恢复监听状态，防止卡死
        if (wakeEnabled && wakeState == WSTATE_WAIT_TTS) {
            wakeState = WSTATE_LISTEN;
        }
    }

    // ==================== 在线唤醒 ====================

    private void startWakeListening() {
        Log.d("WAKE", "startWakeListening retries=" + wakeInitRetries + " enabled=" + wakeEnabled);
        if (!isAdded()) return;
        if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            wakePendingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_VOICE_PERMISSION);
            return;
        }
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    WAKE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            wakeRecorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WAKE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, 3200)
            );
            if (wakeRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                try { wakeRecorder.release(); } catch (Exception ignored) {}
                wakeRecorder = null;
                // 页面切换后立即重开监听可能失败（麦克风资源释放延迟），延迟重试
                if (wakeInitRetries < 2) {
                    wakeInitRetries++;
                    mainHandler.postDelayed(() -> {
                        if (wakeEnabled) startWakeListening();
                    }, 800);
                } else {
                    wakeInitRetries = 0;
                    Toast.makeText(requireContext(), "唤醒监听初始化失败", Toast.LENGTH_SHORT).show();
                    wakeEnabled = false;
                }
                return;
            }
            wakeRecorder.startRecording();
            wakeInitRetries = 0;
            wakeActualRate = 16000;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try { wakeActualRate = wakeRecorder.getSampleRate(); } catch (Exception ignored) {}
            }
            if (wakeActualRate <= 0) wakeActualRate = 16000;
            wakeBuffer = new ByteArrayOutputStream();
            silentFrames = 0;
            wakeAboveFrames = 0;
            wakeVoicedFrames = 0;
            wakeSegmentFrames = 0;
            wakeNoiseFloor = 100.0;
            wakePreRoll.clear();
            wakeSpeaking = false;
            inDialogMode = false;

            wakeThread = new Thread(() -> {
                short[] buf = new short[640]; // 40ms @16k
                while (wakeEnabled) {
                    int n = wakeRecorder.read(buf, 0, buf.length);
                    if (n <= 0) continue;

                    double sum = 0;
                    for (int i = 0; i < n; i++) sum += buf[i] * buf[i];
                    double rms = Math.sqrt(sum / n);
                    byte[] bytes = shortToBytes(buf, n);

                    if (wakeState == WSTATE_LISTEN || wakeState == WSTATE_WAIT_TTS) {
                        // ===== 监听唤醒词；AI播报期间也监听，以支持“小农小农”打断 =====
                        double startThreshold = Math.min(
                                1800.0,
                                Math.max(WAKE_MIN_START_THRESHOLD, wakeNoiseFloor * 2.6)
                        );
                        double endThreshold = Math.min(
                                1100.0,
                                Math.max(WAKE_MIN_END_THRESHOLD, wakeNoiseFloor * 1.7)
                        );
                        if (!wakeSpeaking) {
                            wakePreRoll.addLast(bytes);
                            while (wakePreRoll.size() > WAKE_PRE_ROLL_FRAMES) wakePreRoll.removeFirst();
                            if (rms > startThreshold) {
                                wakeAboveFrames++;
                            } else {
                                wakeAboveFrames = 0;
                                // 只在未说话时缓慢学习环境底噪，避免把人声当成噪声抬高阈值。
                                wakeNoiseFloor = wakeNoiseFloor * 0.96 + Math.min(rms, 1200.0) * 0.04;
                            }
                            if (wakeAboveFrames >= WAKE_START_FRAMES) {
                                Log.d("WAKE", "LISTEN 有效人声 rms=" + (int) rms
                                        + " noise=" + (int) wakeNoiseFloor
                                        + " threshold=" + (int) startThreshold);
                                wakeSpeaking = true;
                                silentFrames = 0;
                                wakeVoicedFrames = wakeAboveFrames;
                                wakeSegmentFrames = 0;
                                wakeAboveFrames = 0;
                                wakeBuffer.reset();
                                for (byte[] preRollFrame : wakePreRoll) {
                                    try { wakeBuffer.write(preRollFrame); } catch (Exception ignored) {}
                                }
                                wakePreRoll.clear();
                            }
                        } else {
                            try { wakeBuffer.write(bytes); } catch (Exception ignored) {}
                            wakeSegmentFrames++;
                            if (rms < endThreshold) {
                                silentFrames++;
                            } else {
                                wakeVoicedFrames++;
                                silentFrames = 0;
                            }
                            boolean ended = silentFrames >= WAKE_SILENT_LIMIT;
                            boolean reachedMax = wakeSegmentFrames >= WAKE_MAX_SEGMENT_FRAMES;
                            if (ended || reachedMax) {
                                wakeSpeaking = false;
                                silentFrames = 0;
                                byte[] audioData = wakeBuffer.toByteArray();
                                wakeBuffer.reset();
                                if (wakeVoicedFrames >= WAKE_MIN_VOICED_FRAMES) {
                                    Log.d("WAKE", "唤醒录音结束 len=" + audioData.length
                                            + " voicedFrames=" + wakeVoicedFrames
                                            + " max=" + reachedMax);
                                    mainHandler.post(() -> handleWakeAudio(audioData));
                                } else {
                                    Log.d("WAKE", "忽略短噪声 voicedFrames=" + wakeVoicedFrames);
                                }
                                wakeVoicedFrames = 0;
                                wakeSegmentFrames = 0;
                            }
                        }
                    } else if (wakeState == WSTATE_RECORD) {
                        // ===== 录用户问题 =====
                        try { wakeBuffer.write(bytes); } catch (Exception ignored) {}
                        // "我在"播完的瞬间记录字节切点，用于精确裁剪提示音
                        if (wakeTtsDoneMs > 0 && wakeTrimPoint < 0) {
                            wakeTrimPoint = wakeBuffer.size();
                        }
                        long afterAckMs = wakeTtsDoneMs > 0
                                ? System.currentTimeMillis() - wakeTtsDoneMs : 0L;
                        double questionStartThreshold = Math.min(
                                1600.0,
                                Math.max(WAKE_MIN_START_THRESHOLD, wakeNoiseFloor * 2.2)
                        );
                        if (wakeTtsDoneMs > 0
                                && afterAckMs >= QUESTION_TTS_TAIL_MS
                                && rms >= questionStartThreshold) {
                            questionVoicedFrames++;
                        }
                        // 超过12秒强制发送，防止环境人声让录音无限增长
                        if (wakeBuffer.size() > QUESTION_MAX_BYTES) {
                            wakeState = WSTATE_LISTEN;
                            silentFrames = 0;
                            byte[] audioData = wakeBuffer.toByteArray();
                            wakeBuffer.reset();
                            int voicedFrames = questionVoicedFrames;
                            questionVoicedFrames = 0;
                            mainHandler.post(() -> {
                                if (voicedFrames >= QUESTION_MIN_VOICED_FRAMES) {
                                    handleQuestionAudio(audioData);
                                } else {
                                    handleWakeNoSpeech();
                                }
                            });
                            continue;
                        }
                        double questionEndThreshold = Math.min(
                                1100.0,
                                Math.max(WAKE_MIN_END_THRESHOLD, wakeNoiseFloor * 1.7)
                        );
                        boolean responseGraceFinished = afterAckMs >= QUESTION_RESPONSE_GRACE_MS;
                        if (rms < questionEndThreshold && wakeTtsDoneMs > 0
                                && (responseGraceFinished || questionVoicedFrames > 0)) {
                            silentFrames++;
                            if (silentFrames >= WAKE_SILENT_LIMIT) {
                                // 说完了：有人声才送识别；纯静音只累计次数，不制造问题文本
                                wakeState = WSTATE_LISTEN;
                                silentFrames = 0;
                                byte[] audioData = wakeBuffer.toByteArray();
                                wakeBuffer.reset();
                                int voicedFrames = questionVoicedFrames;
                                questionVoicedFrames = 0;
                                mainHandler.post(() -> {
                                    if (voicedFrames >= QUESTION_MIN_VOICED_FRAMES) {
                                        handleQuestionAudio(audioData);
                                    } else {
                                        handleWakeNoSpeech();
                                    }
                                });
                            }
                        } else if (rms >= questionEndThreshold) {
                            silentFrames = 0;
                        }
                    }
                }
            }, "wake-listener");
            wakeThread.start();
        } catch (Exception e) {
            try { if (wakeRecorder != null) wakeRecorder.release(); } catch (Exception ignored) {}
            wakeRecorder = null;
            if (wakeInitRetries < 2) {
                wakeInitRetries++;
                mainHandler.postDelayed(() -> {
                    if (wakeEnabled) startWakeListening();
                }, 800);
            } else {
                wakeInitRetries = 0;
                Toast.makeText(requireContext(), "唤醒启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                wakeEnabled = false;
            }
        }
    }

    private void stopWakeListening() {
        wakeEnabled = false;
        wakeSpeaking = false;
        inDialogMode = false;
        wakeInitRetries = 0;
        if (wakeRecorder != null) {
            try { wakeRecorder.stop(); } catch (Exception ignored) {}
            wakeRecorder.release();
            wakeRecorder = null;
        }
        if (wakeThread != null) {
            try { wakeThread.join(500); } catch (InterruptedException ignored) {}
            wakeThread = null;
        }
    }

    private byte[] shortToBytes(short[] s, int len) {
        byte[] b = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            b[i * 2] = (byte) (s[i] & 0xff);
            b[i * 2 + 1] = (byte) ((s[i] >> 8) & 0xff);
        }
        return b;
    }

    /**
     * 线性插值重采样到 16kHz 单声道 16bit PCM。
     * 部分设备(如RK3566)AudioRecord请求16000但实际输出44100/48000，上传百度前必须统一为16k。
     */
    private byte[] resample16k(byte[] pcm16, int fromRate) {
        if (pcm16 == null || pcm16.length < 4 || fromRate <= 0 || fromRate == 16000) return pcm16;
        int inLen = pcm16.length / 2;
        int outLen = (int) ((long) inLen * 16000 / fromRate);
        if (outLen < 1) outLen = 1;
        byte[] out = new byte[outLen * 2];
        for (int i = 0; i < outLen; i++) {
            double srcPos = (double) i * fromRate / 16000.0;
            int i0 = (int) srcPos;
            if (i0 >= inLen) i0 = inLen - 1;
            int i1 = Math.min(i0 + 1, inLen - 1);
            double frac = srcPos - i0;
            short s0 = (short) ((pcm16[i0 * 2] & 0xff) | (pcm16[i0 * 2 + 1] << 8));
            short s1 = (short) ((pcm16[i1 * 2] & 0xff) | (pcm16[i1 * 2 + 1] << 8));
            int v = (int) (s0 + (s1 - s0) * frac);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[i * 2] = (byte) (v & 0xff);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }

    // 处理唤醒音频：上传识别，检查有没有唤醒词
    private void handleWakeAudio(byte[] pcmData) {
        if (streaming || pcmData == null || pcmData.length < 3200) return;
        if (!wakeAsrInFlight.compareAndSet(false, true)) {
            Log.d("WAKE", "唤醒 ASR 忙，忽略重叠音频");
            return;
        }
        wakeAsrStartedMs = System.currentTimeMillis();

        new Thread(() -> {
            try {
                byte[] up = resample16k(pcmData, wakeActualRate);
                String token = getBaiduToken();
                JSONObject json = requestAsr(up, token);
                // 换过 Secret Key 后旧 token 失效(110)，强制刷新 token 后重试一次
                if (isTokenError(json)) {
                    json = requestAsr(up, getBaiduToken(true));
                }
                int errNo = json.optInt("err_no", -1);
                if (errNo == 0) {
                    String text = json.getJSONArray("result").getString(0);
                    Log.d("WAKE", "唤醒 ASR 返回 chars=" + (text == null ? 0 : text.length())
                            + " latencyMs=" + (System.currentTimeMillis() - wakeAsrStartedMs));
                    mainHandler.post(() -> onWakeRecognized(text));
                } else {
                    Log.d("WAKE", "唤醒识别失败 errNo=" + errNo + " msg=" + json.optString("err_msg"));
                }
            } catch (Exception error) {
                Log.d("WAKE", "唤醒 ASR 异常 " + error.getClass().getSimpleName());
            } finally {
                wakeAsrInFlight.set(false);
            }
        }, "wake-asr").start();
    }

    // 识别结果回调
    private void onWakeRecognized(String text) {
        if (!wakeEnabled || (wakeState != WSTATE_LISTEN && wakeState != WSTATE_WAIT_TTS)) return;
        if (text == null || text.trim().isEmpty()) return;
        text = text.trim();

        final boolean interruptingSpeech = wakeState == WSTATE_WAIT_TTS;
        // 播报内容可能偶然包含一次“小农”。打断时要求用户连续叫两次，避免扬声器自唤醒。
        if (interruptingSpeech && !containsRepeatedWakeWord(text)) {
            Log.d("WAKE", "播报期间忽略非重复唤醒词 chars=" + text.length());
            return;
        }

        // 百度短语音经常把“小农”识别成小能、小荣、晓龙等近音字。
        // 除明确别名外，再对较短识别结果做受限的二字近音匹配，避免听见了却完全不回应。
        String matched = findWakeWord(text);
        if (matched == null) {
            Log.d("WAKE", "识别到但无唤醒词 chars=" + text.length());
            return;
        }

        if (interruptingSpeech) {
            stopTts();
            Toast.makeText(requireContext(), "已停止播报，我在，请说", Toast.LENGTH_SHORT).show();
        }

        // 唤醒成功
        wakeSessionActive = true;
        Log.d("WAKE", "唤醒命中 matched=" + matched + " visible=" + isVisible() + " text=" + text);
        // 若当前不在助手页（如摄像头页），先切回助手页，保证后续交互可见；
        // 切回后 fragment 重建会自动重启唤醒监听
        if (!isVisible()) {
            Log.d("WAKE", "当前不在助手页，切回 openChatPage");
            android.app.Activity act = getActivity();
            if (act instanceof MainActivity) {
                ((MainActivity) act).openChatPage();
            }
        }
        // 明确反馈：状态栏"聆听中"+ 文字"我在，请说"+ 语音"我在"
        statusText.setText("● 聆听中，请说");
        statusText.setTextColor(Color.parseColor("#FF8A80"));
        Toast.makeText(requireContext(), "我在，请说", Toast.LENGTH_SHORT).show();

        // 提取唤醒词后面的内容（用户可能连着说了问题）
        String question = "";
        int idx = text.indexOf(matched);
        if (idx >= 0) {
            question = text.substring(idx + matched.length()).trim();
            question = question.replaceAll("^[，。！？,.!?\\s]+", "").trim();
        }

        // 过滤"只说唤醒词"的情况：唤醒词后面没有真正的实义内容
        //（例如连说两个唤醒词"小农小农"，剩余文本恰好也是唤醒词），不当作问题发送
        boolean questionIsOnlyWake = false;
        if (question.length() >= 2) {
            String q = question.replaceAll("[\\s，。！？,.!?]", "");
            for (String w : WAKE_WORD_ALIASES) {
                if (q.equals(w)) {
                    questionIsOnlyWake = true;
                    break;
                }
            }
            if (!questionIsOnlyWake && findWakeWord(q) != null && q.length() <= 4) {
                questionIsOnlyWake = true;
            }
        }

        if (question.length() >= 2 && !questionIsOnlyWake) {
            // 用户连问题一起说了，直接发送，不用再录一次音
            consecutiveSilentWakeCount = 0;
            if (!streaming) {
                input.setText(question);
                sendMessage(question);
            }
            // AI 回复期间丢弃音频，避免误触发唤醒；回复播完后自动恢复监听
            wakeState = WSTATE_WAIT_TTS;
            return;
        }

        // 只说唤醒词：立即进入录音（不等"我在"播完，避免用户说话被丢弃），
        // 同时播"我在"作为提示；录音循环在TTS播完瞬间记录字节切点，识别前精确裁剪
        wakeBuffer.reset();
        silentFrames = 0;
        questionVoicedFrames = 0;
        wakeState = WSTATE_RECORD;
        wakeTtsStartMs = System.currentTimeMillis();
        wakeTtsDoneMs = 0;
        wakeTrimPoint = -1;
        speakText("我在", () -> wakeTtsDoneMs = System.currentTimeMillis());
    }

    /** 返回ASR文本中实际命中的唤醒词片段；没有命中则返回null。 */
    private String findWakeWord(String text) {
        if (text == null) return null;
        String compact = text.replaceAll("[\\s，。！？、,.!?；;：:]", "");
        if (compact.isEmpty()) return null;

        for (String alias : WAKE_WORD_ALIASES) {
            if (compact.contains(alias)) return alias;
        }

        // 仅对短句启用字符组容错，防止普通长对话中的偶然二字造成误唤醒。
        if (compact.length() > 10) return null;
        for (int i = 0; i + 1 < compact.length(); i++) {
            char first = compact.charAt(i);
            char second = compact.charAt(i + 1);
            if (WAKE_FIRST_CHARS.indexOf(first) >= 0
                    && WAKE_SECOND_CHARS.indexOf(second) >= 0) {
                return compact.substring(i, i + 2);
            }
        }
        return null;
    }

    /** 播报打断只接受连续两次唤醒称呼，例如“小农小农”“小龙小龙”。 */
    private boolean containsRepeatedWakeWord(String text) {
        if (text == null) return false;
        String compact = text.replaceAll("[\\s，。！？、,.!?；;：:]", "");
        for (String alias : WAKE_WORD_ALIASES) {
            if (compact.contains(alias + alias)) return true;
        }
        for (int i = 0; i + 3 < compact.length(); i++) {
            String first = compact.substring(i, i + 2);
            String second = compact.substring(i + 2, i + 4);
            if (first.equals(second)
                    && WAKE_FIRST_CHARS.indexOf(first.charAt(0)) >= 0
                    && WAKE_SECOND_CHARS.indexOf(first.charAt(1)) >= 0) {
                return true;
            }
        }
        return false;
    }

    // 处理用户问题音频：上传识别后自动发送
    private void handleQuestionAudio(byte[] pcmData) {
        if (pcmData == null) {
            wakeState = WSTATE_LISTEN;
            return;
        }
        // 裁剪掉"我在"提示音段：录音循环在TTS播完瞬间记录字节切点（wakeTrimPoint），
        // 再留 200ms（6400字节）余量清掉播放器尾部，避免残留提示音干扰识别
        int start = (wakeTrimPoint > 0 && wakeTrimPoint < pcmData.length) ? wakeTrimPoint : 0;
        int extra = 6400;
        if (pcmData.length - (start + extra) < 4000) extra = 0;
        start += extra;
        if (start >= pcmData.length) start = 0;
        final byte[] finalData;
        if (start > 0) {
            byte[] trimmed = new byte[pcmData.length - start];
            System.arraycopy(pcmData, start, trimmed, 0, trimmed.length);
            finalData = trimmed;
        } else {
            finalData = pcmData;
        }
        if (finalData.length < 8000) {
            wakeState = WSTATE_LISTEN;
            return;
        }
        statusText.setText("● 识别中");
        statusText.setTextColor(Color.parseColor("#FFB74D"));

        new Thread(() -> {
            try {
                byte[] up = resample16k(finalData, wakeActualRate);
                String token = getBaiduToken();
                JSONObject json = requestAsr(up, token);
                // 换过 Secret Key 后旧 token 失效(110)，强制刷新 token 后重试一次
                if (isTokenError(json)) {
                    json = requestAsr(up, getBaiduToken(true));
                }
                int errNo = json.optInt("err_no", -1);
                if (errNo == 0) {
                        String text = json.getJSONArray("result").getString(0);
                        mainHandler.post(() -> {
                            statusText.setText("● 在线");
                            statusText.setTextColor(Color.parseColor("#70D69B"));
                            String recognized = text == null ? "" : text.trim();
                            if (isLikelyNoQuestion(recognized)) {
                                handleWakeNoSpeech();
                            } else {
                                consecutiveSilentWakeCount = 0;
                                input.setText(recognized);
                                // AI 回复期间丢弃音频，避免误触发唤醒；播完后自动恢复监听
                                wakeState = WSTATE_WAIT_TTS;
                                sendMessage(recognized);
                            }
                        });
                    } else {
                        final JSONObject errJson = json;
                        mainHandler.post(() -> {
                            statusText.setText("● 在线");
                            statusText.setTextColor(Color.parseColor("#70D69B"));
                            Toast.makeText(requireContext(),
                                    "识别失败(" + errNo + ")：" + errJson.optString("err_msg")
                                            + " 时长" + (up.length / 32000.0) + "s",
                                    Toast.LENGTH_LONG).show();
                            wakeState = WSTATE_LISTEN;
                        });
                    }
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    statusText.setText("● 在线");
                    statusText.setTextColor(Color.parseColor("#70D69B"));
                    wakeState = WSTATE_LISTEN;
                });
            }
        }, "question-asr").start();
    }

    /**
     * 唤醒后没有检测到有效问题：前两次静默返回监听；连续第三次才播报使用方法。
     * 这样既不会把扬声器尾音误识别成“我想听”，也不会每次沉默都打扰用户。
     */
    private void handleWakeNoSpeech() {
        consecutiveSilentWakeCount++;
        input.setText("");
        statusText.setText("● 在线");
        statusText.setTextColor(Color.parseColor("#70D69B"));
        wakeSessionActive = false;
        if (consecutiveSilentWakeCount < SILENT_WAKE_HELP_THRESHOLD) {
            wakeState = WSTATE_LISTEN;
            Log.d("WAKE", "唤醒后未说话 count=" + consecutiveSilentWakeCount);
            return;
        }

        consecutiveSilentWakeCount = 0;
        String help = "我是小农，这台多模态智能检测设备的语音助手。听到我说我在后，你可以让我打开可见光、热成像或X光页面，执行拍照和全检，查询雷达与设备状态，查看历史记录和分析报告，也可以咨询设备操作与影像分析问题。";
        addAiBubble(help);
        wakeState = WSTATE_WAIT_TTS;
        Log.d("WAKE", "连续三次唤醒后未说话，播报使用方法");
        speakText(help, () -> {
            wakeState = WSTATE_LISTEN;
            statusText.setText("● 在线");
            statusText.setTextColor(Color.parseColor("#70D69B"));
        });
    }

    /** 过滤静音/扬声器尾音常见误识别，避免把它们作为用户问题自动发送。 */
    private boolean isLikelyNoQuestion(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        String compact = text.replaceAll("[\\s，。！？,.!?]", "");
        return compact.equals("我想听")
                || compact.equals("想听")
                || compact.equals("我在")
                || compact.equals("小农")
                || compact.equals("小农小农");
    }

    private byte[] readAllBytes(File file) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int offset = 0;
        while (offset < data.length) {
            int n = fis.read(data, offset, data.length - offset);
            if (n < 0) break;
            offset += n;
        }
        fis.close();
        return data;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_VOICE_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && wakePendingPermission) {
                // 首次进入（或唤醒开关）自动请求的授权：恢复自动唤醒监听，而不是开始手动录音
                wakePendingPermission = false;
                if (wakeEnabled && wakeRecorder == null) {
                    startWakeListening();
                }
            } else if (granted) {
                startVoiceRecording();
            } else {
                wakePendingPermission = false;
                Toast.makeText(requireContext(), "需要麦克风权限才能语音输入", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onDestroyView() {
        voiceRecording = false;
        mainHandler.removeCallbacks(voiceTimeout);
        mainHandler.removeCallbacks(silenceCheck);
        if (voiceRecorder != null) {
            try { voiceRecorder.stop(); } catch (Exception ignored) {}
            voiceRecorder.release();
            voiceRecorder = null;
        }
        if (voiceThread != null) {
            try { voiceThread.join(500); } catch (InterruptedException ignored) {}
            voiceThread = null;
        }
        if (ttsPlayer != null) {
            try { ttsPlayer.stop(); } catch (Exception ignored) {}
            ttsPlayer.release();
            ttsPlayer = null;
        }
        // 完整停掉唤醒监听（含线程 join），防止残留线程与下次启动的新线程抢麦克风
        stopWakeListening();
        super.onDestroyView();
    }

    // ==================== 流式请求 ====================

    /** 裁剪历史上下文：保留首条 system 消息 + 最近 MAX_CONTEXT_MESSAGES 条，防止无限增长导致 token 超限 */
    private void trimMessages() {
        while (messages.length() > 1 + MAX_CONTEXT_MESSAGES) {
            messages.remove(1);
        }
    }

    private void startStream(String userText) {
        try {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);
            messages.put(userMsg);
            trimMessages();

            JSONObject reqBody = new JSONObject();
            reqBody.put("model", MODEL);
            reqBody.put("messages", messages);
            reqBody.put("max_tokens", 800);
            reqBody.put("stream", true);
            reqBody.put("temperature", 0.7);
            // 工具调用：让大模型把设备操作意图解析成结构化动作，App 直接执行，
            // 解决"说'热传感摄像头'它只给步骤却不打开页面"的问题。
            reqBody.put("tools", buildTools());

            streaming = true;
            streamStarted = false;
            setUiBusy(true);

            // 先放一个"思考中"气泡 + 动画
            mainHandler.post(() -> {
                streamingView = createAiBubble("");
                streamingView.setText("小农正在思考.");
                thinkingDots = 0;
                mainHandler.removeCallbacks(thinkingTick);
                mainHandler.postDelayed(thinkingTick, 380);
            });

            client.newCall(buildRequest(reqBody)).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    handleStreamFailure(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    StringBuilder full = new StringBuilder();
                    // 工具调用增量累积（DeepSeek 流式把 tool_calls 分成多段 delta）
                    String[] toolIds = new String[4];
                    String[] toolNames = new String[4];
                    StringBuilder[] toolArgs = new StringBuilder[4];
                    boolean[] toolIndexUsed = new boolean[4];
                    try {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)
                        );
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if (data.equals("[DONE]")) break;
                            try {
                                JSONObject json = new JSONObject(data);
                                JSONObject delta = json.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("delta");
                                String content = delta.optString("content", "");
                                if (!content.isEmpty()) {
                                    if (!streamStarted) {
                                        streamStarted = true;
                                        mainHandler.post(() -> stopThinking());
                                    }
                                    full.append(content);
                                    String snapshot = full.toString();
                                    mainHandler.post(() -> {
                                        if (streamingView != null) {
                                            streamingView.setText(render(snapshot + " ▌"));
                                            scrollToBottom();
                                        }
                                    });
                                }
                                JSONArray tcs = delta.optJSONArray("tool_calls");
                                if (tcs != null) {
                                    for (int i = 0; i < tcs.length(); i++) {
                                        JSONObject tc = tcs.getJSONObject(i);
                                        int idx = tc.optInt("index", 0);
                                        if (idx < 0 || idx >= 4) continue;
                                        toolIndexUsed[idx] = true;
                                        String id = tc.optString("id", "");
                                        if (!id.isEmpty()) toolIds[idx] = id;
                                        JSONObject fn = tc.optJSONObject("function");
                                        if (fn != null) {
                                            if (fn.has("name")) toolNames[idx] = fn.getString("name");
                                            String arg = fn.optString("arguments", "");
                                            if (!arg.isEmpty()) {
                                                if (toolArgs[idx] == null) toolArgs[idx] = new StringBuilder();
                                                toolArgs[idx].append(arg);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                                // 跳过无法解析的分片
                            }
                        }
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (streamingView != null) {
                                streamingView.setText("读取回复失败：" + e.getMessage());
                            }
                        });
                    }

                    // 取第一个工具调用（DeepSeek 单轮一般只返回一个）
                    String toolName = null;
                    String toolArgsStr = null;
                    String toolId = null;
                    for (int i = 0; i < 4; i++) {
                        if (toolIndexUsed[i] && toolNames[i] != null) {
                            toolName = toolNames[i];
                            toolArgsStr = toolArgs[i] == null ? "{}" : toolArgs[i].toString();
                            toolId = toolIds[i] == null ? "" : toolIds[i];
                            break;
                        }
                    }

                    if (toolName != null) {
                        final String fn = toolName, fa = toolArgsStr, fid = toolId;
                        // 有工具调用：执行动作，再把结果回给大模型生成最终回复
                        mainHandler.post(() -> handleToolCall(fn, fa, fid));
                    } else {
                        mainHandler.post(() -> finishStream(full.toString()));
                    }
                }
            });

        } catch (Exception e) {
            streaming = false;
            setUiBusy(false);
            addAiBubble("请求错误：" + e.getMessage());
        }
    }

    // ==================== 工具调用（function calling） ====================

    /** 设备操作工具定义（DeepSeek function calling） */
    private JSONArray buildTools() {
        JSONArray tools = new JSONArray();
        try {
            tools.put(buildTool("open_page",
                    "切换设备功能页面。用户说'打开/进入/切到/看下'某页面时使用（如打开热成像、打开摄像头、打开X光、打开AI分析、打开历史记录、回到首页）。",
                    new String[][]{
                            {"page", "string", "目标页面：home=设备首页，camera=摄像头，thermal=热成像（也叫热传感摄像头/红外/热像仪），xray=X-Ray影像，ai=AI分析，history=历史记录", "true"}
                    }));
            tools.put(buildTool("capture_photos",
                    "执行同时拍照：一次保存实时图（摄像头图）和热成像图。用户说'拍照/拍一张/同时拍照/拍摄'时使用。",
                    new String[][]{}));
            tools.put(buildTool("connect_radar",
                    "连接毫米波雷达设备。用户说'连接雷达/打开雷达/启动雷达'时使用。",
                    new String[][]{}));
            tools.put(buildTool("query_radar",
                    "查询毫米波雷达当前数据（物体状态/距离/呼吸/心率）。用户问'雷达测距/当前距离/雷达数据/呼吸多少'时使用。",
                    new String[][]{}));
            tools.put(buildTool("query_device_status",
                    "查询设备连接状态（摄像头/热成像/X-Ray/毫米波雷达是否已连接）。用户问'设备状态/连接状态/检查设备'时使用。",
                    new String[][]{}));
            tools.put(buildTool("query_history_stats",
                    "查询历史记录总条数、含AI分析报告的条数、含X光影像的条数。用户问'历史记录有几条/哪些有分析/多少条报告'时使用。",
                    new String[][]{}));
            tools.put(buildTool("open_case",
                    "打开历史记录中某条案例的影像与AI分析报告详情。用户说'打开/调取/查看历史记录第N条的分析报告'、'打开最新一条的AI分析'、'打开那条/这条/某条/上次那条历史记录的AI分析'时使用。注意：只要用户想让某条历史记录的分析报告显示出来，即使没有指明具体是第几条，也应使用本工具（默认打开最新一条），不要用open_page(history)只打开列表页。",
                    new String[][]{
                            {"latest", "boolean", "是否为最新一条（用户说'最新/最近/最后一条'时填true，否则false）", "true"},
                            {"ordinal", "integer", "要打开的条号（用户明确说'第N条'时填N，从1开始，1=最新一条）", "false"},
                            {"want_ai", "boolean", "用户是否希望直接查看该条目的AI分析报告（提到'分析/报告'时填true）", "true"}
                    }));
        } catch (Exception ignored) {
        }
        return tools;
    }

    private JSONObject buildTool(String name, String description, String[][] params) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (String[] p : params) {
            JSONObject prop = new JSONObject();
            prop.put("type", p[1]);
            prop.put("description", p[2]);
            properties.put(p[0], prop);
            if ("true".equals(p[3])) required.put(p[0]);
        }
        parameters.put("properties", properties);
        parameters.put("required", required);
        fn.put("parameters", parameters);
        JSONObject wrapper = new JSONObject();
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        return wrapper;
    }

    private Request buildRequest(JSONObject reqBody) {
        RequestBody body = RequestBody.create(
                reqBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );
        return new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    private void handleStreamFailure(IOException e) {
        streaming = false;
        mainHandler.post(() -> {
            stopThinking();
            setUiBusy(false);
            if (streamingView != null) {
                streamingView.setText("网络错误：" + e.getMessage() + "\n请检查设备网络后重试。");
            }
            statusText.setText("● 网络异常");
            statusText.setTextColor(Color.parseColor("#FF8A80"));
            Toast.makeText(requireContext(), "网络连接失败", Toast.LENGTH_SHORT).show();
            // AI 请求失败也要恢复唤醒监听，避免状态卡在 WAIT_TTS
            if (wakeSessionActive) {
                wakeSessionActive = false;
                wakeState = WSTATE_LISTEN;
                statusText.setText("● 在线");
                statusText.setTextColor(Color.parseColor("#70D69B"));
            }
        });
    }

    /**
     * 主线程执行工具动作；执行完成后把结果作为 tool 消息回给大模型，
     * 由大模型生成最终的自然语言回复（流式打字机 + 语音播报）。
     */
    private void handleToolCall(String name, String argsJson, String toolId) {
        // 先保存第一轮 assistant 的工具调用消息（上下文一致性）
        try {
            JSONObject asst = new JSONObject();
            asst.put("role", "assistant");
            asst.put("content", "");
            JSONArray tcs = new JSONArray();
            JSONObject tc = new JSONObject();
            tc.put("id", toolId.isEmpty() ? "call_" + System.currentTimeMillis() : toolId);
            tc.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", argsJson == null ? "{}" : argsJson);
            tc.put("function", fn);
            tcs.put(tc);
            asst.put("tool_calls", tcs);
            messages.put(asst);
        } catch (Exception ignored) {
        }

        String result = "好的";
        MainActivity ma = getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
        try {
            JSONObject args = (argsJson == null || argsJson.trim().isEmpty())
                    ? new JSONObject() : new JSONObject(argsJson);
            switch (name) {
                case "open_page": {
                    String page = args.optString("page", "");
                    String pageName;
                    switch (page) {
                        case "camera":
                            if (ma != null) ma.openCameraPageByCommand();
                            pageName = "摄像头页面";
                            break;
                        case "thermal":
                            if (ma != null) ma.openThermalPageByCommand();
                            pageName = "热成像页面";
                            break;
                        case "xray":
                            if (ma != null) ma.openXrayPageByCommand();
                            pageName = "X-Ray影像页面";
                            break;
                        case "ai":
                            if (ma != null) ma.openAiAnalysisPageByCommand();
                            pageName = "AI分析页面";
                            break;
                        case "history":
                            if (ma != null) ma.openHistoryPageByCommand();
                            pageName = "历史记录页面";
                            break;
                        default:
                            if (ma != null) ma.openHomePageByCommand();
                            pageName = "设备首页";
                            break;
                    }
                    result = "已打开" + pageName;
                    break;
                }
                case "capture_photos":
                    if (ma != null) ma.capturePairedPhotoByCommand();
                    result = "已执行同时拍照，正在保存实时图和热成像图";
                    break;
                case "connect_radar":
                    if (ma != null) ma.connectRadarByCommand();
                    result = "已发起连接毫米波雷达";
                    break;
                case "query_radar": {
                    com.weiguang.drcontroller.hardware.radar.RadarReading r =
                            ma == null ? com.weiguang.drcontroller.hardware.radar.RadarReading.empty()
                                    : ma.getLatestRadarReading();
                    result = "当前毫米波雷达数据：目标" + (r.isConnected() ? r.getTargetStatus() : "未检测到")
                            + "；距离" + r.getDistance() + "；呼吸" + r.getBreathRate() + "；心率" + r.getHeartRate();
                    break;
                }
                case "query_device_status": {
                    if (ma == null) {
                        result = "设备状态暂时无法获取";
                        break;
                    }
                    result = "摄像头" + (ma.isCameraConnected() ? "已连接" : "待连接")
                            + "；热成像" + (ma.isThermalConnected() ? "已连接" : "待连接")
                            + "；X光" + (ma.isXrayConnected() ? "已连接" : "待连接")
                            + "；毫米波雷达" + (ma.getLatestRadarReading().isConnected() ? "已连接" : "待连接");
                    break;
                }
                case "query_history_stats": {
                    final MainActivity fma = ma;
                    new Thread(() -> {
                        try {
                            List<CaseRepository.CaseRecord> records =
                                    CaseRepository.queryCases(requireContext().getApplicationContext(), 0);
                            int total = records.size();
                            int withAi = 0;
                            int withXray = 0;
                            for (CaseRepository.CaseRecord r : records) {
                                if (r.hasAiReport()) withAi++;
                                if (r.hasXrayImage()) withXray++;
                            }
                            final String res = "历史记录共有" + total + "条案例，其中" + withAi
                                    + "条有AI分析报告，" + withXray + "条包含X光影像";
                            mainHandler.post(() -> sendFinalStream(buildToolResultMessage(toolId, res)));
                        } catch (Exception ex) {
                            mainHandler.post(() ->
                                    sendFinalStream(buildToolResultMessage(toolId, "查询历史记录数量失败")));
                        }
                    }, "tool-history").start();
                    return;
                }
                case "open_case": {
                    final MainActivity fma = ma;
                    boolean latest = args.optBoolean("latest", true);
                    int ordinal = args.optInt("ordinal", latest ? 1 : -1);
                    final boolean wantAi = args.optBoolean("want_ai", false);
                    final int target = ordinal > 0 ? ordinal : 1;
                    new Thread(() -> {
                        try {
                            List<CaseRepository.CaseRecord> records =
                                    CaseRepository.queryCases(requireContext().getApplicationContext(), 0);
                            if (records.isEmpty()) {
                                mainHandler.post(() -> sendFinalStream(
                                        buildToolResultMessage(toolId, "历史记录目前为空，没有可打开的案例")));
                                return;
                            }
                            int safe = Math.min(target, records.size());
                            CaseRepository.CaseRecord rec = records.get(safe - 1);
                            final String caseId = rec.getCaseId();
                            final String label = (rec.getDisplayName() == null || rec.getDisplayName().trim().isEmpty())
                                    ? formatReadableTime(rec.getTimeMillis()) : rec.getDisplayName();
                            final String res = "已打开第" + safe + "条" + (wantAi ? "的分析报告" : "历史记录")
                                    + "（" + label + "）" + (rec.hasAiReport() ? "，该案例已有AI分析报告" : "");
                            mainHandler.post(() -> {
                                if (fma != null) fma.openCaseDetail(caseId);
                                sendFinalStream(buildToolResultMessage(toolId, res));
                            });
                        } catch (Exception ex) {
                            mainHandler.post(() ->
                                    sendFinalStream(buildToolResultMessage(toolId, "打开历史记录失败")));
                        }
                    }, "tool-open-case").start();
                    return;
                }
                default:
                    result = "我暂时不能执行这个操作";
                    break;
            }
        } catch (Exception ex) {
            result = "执行指令时出错：" + ex.getMessage();
        }
        sendFinalStream(buildToolResultMessage(toolId, result));
    }

    private JSONObject buildToolResultMessage(String toolId, String content) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "tool");
            msg.put("tool_call_id", toolId.isEmpty() ? "call_" + System.currentTimeMillis() : toolId);
            msg.put("content", content == null ? "" : content);
            return msg;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 携带工具结果发起最终流式请求（无 tools），完成后正常 finishStream 展示并朗读 */
    private void sendFinalStream(JSONObject toolResultMsg) {
        if (toolResultMsg != null) messages.put(toolResultMsg);
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("model", MODEL);
            reqBody.put("messages", messages);
            reqBody.put("max_tokens", 800);
            reqBody.put("stream", true);
            reqBody.put("temperature", 0.7);
            client.newCall(buildRequest(reqBody)).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    handleStreamFailure(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    StringBuilder full = new StringBuilder();
                    try {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)
                        );
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if (data.equals("[DONE]")) break;
                            try {
                                JSONObject json = new JSONObject(data);
                                String delta = json.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("delta")
                                        .optString("content", "");
                                if (delta.isEmpty()) continue;
                                if (!streamStarted) {
                                    streamStarted = true;
                                    mainHandler.post(() -> stopThinking());
                                }
                                full.append(delta);
                                String snapshot = full.toString();
                                mainHandler.post(() -> {
                                    if (streamingView != null) {
                                        streamingView.setText(render(snapshot + " ▌"));
                                        scrollToBottom();
                                    }
                                });
                            } catch (Exception ignored) {
                                // 跳过无法解析的分片
                            }
                        }
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (streamingView != null) {
                                streamingView.setText("读取回复失败：" + e.getMessage());
                            }
                        });
                    }
                    mainHandler.post(() -> finishStream(full.toString()));
                }
            });
        } catch (Exception e) {
            streaming = false;
            setUiBusy(false);
            addAiBubble("请求错误：" + e.getMessage());
        }
    }

    private void finishStream(String reply) {
        streaming = false;
        stopThinking();
        setUiBusy(false);
        if (streamingView == null) {
            // 极端时序下思考气泡尚未创建，直接补一条完整回复
            if (reply.isEmpty()) {
                addAiBubble("抱歉，小农没有获取到回复，请重试。");
            } else {
                addAiBubble(reply);
            }
        } else if (reply.isEmpty()) {
            streamingView.setText("抱歉，小农没有获取到回复，请重试。");
        } else {
            streamingView.setText(render(reply));
        }
        // 保存上下文
        try {
            JSONObject aiMsg = new JSONObject();
            aiMsg.put("role", "assistant");
            aiMsg.put("content", reply);
            messages.put(aiMsg);
        } catch (Exception ignored) {}
        scrollToBottom();

        // 所有回答（语音提问或手动输入）统一支持“小农小农”打断。
        wakeSessionActive = false;
        speakInterruptibleText(reply);
    }

    private void stopThinking() {
        mainHandler.removeCallbacks(thinkingTick);
    }

    private void setUiBusy(boolean busy) {
        if (busy) {
            sendBtn.setAlpha(0.5f);
            statusText.setText("● 思考中");
            statusText.setTextColor(Color.parseColor("#FFB74D"));
        } else {
            sendBtn.setAlpha(1f);
            statusText.setText("● 在线");
            statusText.setTextColor(Color.parseColor("#70D69B"));
        }
    }

    private void hideWelcome() {
        if (welcomePanel != null) welcomePanel.setVisibility(View.GONE);
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    // ==================== 气泡 ====================

    // ==================== 一键全检：AI 分析结果播报（MainActivity 回调） ====================

    /** 一键全检：拍摄完成，AI 分析开始 */
    public void notifyInspectionStarted(String caseId) {
        if (!isAdded()) return;
        addAiBubble("**一键全检**：自动拍摄完成，正在调用 AI 多模态分析（可见光+热成像融合识别），请稍候…");
        speakText("自动拍摄完成，正在分析，请稍候");
    }

    /** 一键全检：分析完成，展示报告并语音播报"对象速览"结论 */
    public void notifyInspectionDone(String caseId, String report) {
        if (!isAdded()) return;
        if (report == null || report.trim().isEmpty()) {
            addAiBubble("**一键全检**：AI 分析未返回有效内容。");
            speakText("AI分析没有返回内容，请重试");
            return;
        }
        addAiBubble("**一键全检完成**，AI 分析报告已生成并保存到历史记录：\n\n" + report);
        String summary = buildInspectionSpokenSummary(report);
        speakInterruptibleText(summary + "完整分析报告已经保存。你可以点击分析报告，或者到历史记录中打开本次记录查看。");
    }

    /** 一键全检：失败。autoOpenAi 为 true 表示已自动跳到 AI 分析页供手动重试 */
    public void notifyInspectionError(String error, boolean autoOpenAi) {
        if (!isAdded()) return;
        String detail = error == null ? "未知错误" : error;
        if (autoOpenAi) {
            addAiBubble("**一键全检失败**：" + detail + "\n\n已为你打开**AI分析页**并选中该案例，可以手动重新分析。");
            speakText("一键全检失败，已为你打开AI分析页，可以手动重新分析");
        } else {
            addAiBubble("**一键全检失败**：" + detail);
            speakText("一键全检失败，" + detail);
        }
    }

    /** 从报告 Markdown 中提取"对象速览：xxx"一行用于语音播报 */
    private String extractObjectSummary(String report) {
        if (report == null) return null;
        int idx = report.indexOf("对象速览");
        if (idx < 0) return null;
        String tail = report.substring(idx);
        int end = tail.indexOf('\n');
        String line = (end > 0 ? tail.substring(0, end) : tail).trim();
        line = line.replace("**", "").replace("#", "").replace("-", "").trim();
        line = line.replaceFirst("^对象速览[:：]\\s*", "");
        if (line.trim().isEmpty()) return null;
        return "分析完成，" + line;
    }

    /** 优先读“对象速览”；缺失时退回综合结论中的第一条有效内容。 */
    private String buildInspectionSpokenSummary(String report) {
        String summary = extractObjectSummary(report);
        if (summary != null) return ensureSentence(summary);

        String[] lines = report == null ? new String[0] : report.split("\\r?\\n");
        boolean inConclusion = false;
        for (String raw : lines) {
            String line = raw.replace("**", "").replace("#", "")
                    .replaceFirst("^[\\-*>\\d.、\\s]+", "").trim();
            if (line.contains("综合结论")) {
                inConclusion = true;
                continue;
            }
            if (inConclusion && !line.isEmpty()) {
                if (line.length() > 120) line = line.substring(0, 120);
                return ensureSentence("分析完成，" + line);
            }
        }
        return "分析完成，简要结果已经生成。";
    }

    private String ensureSentence(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String value = text.trim();
        char last = value.charAt(value.length() - 1);
        if (last != '。' && last != '！' && last != '？') value += "。";
        return value;
    }

    private void addUserBubble(String text) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(dp(2), 0, dp(2), 0);

        TextView bubble = new TextView(requireContext());
        bubble.setText(render(text));
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14f);
        bubble.setLineSpacing(0f, 1.15f);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setMaxWidth(dp(400));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[]{dp(14), dp(14), dp(4), dp(14), dp(14), dp(14), dp(14), dp(14)});
        bg.setColor(Color.parseColor("#1976D2"));
        bubble.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(10);
        row.addView(bubble, lp);
        chatList.addView(row);
        scrollToBottom();
    }

    /**
     * 创建带头像的 AI 消息行，返回气泡 TextView 供流式追加
     */
    private TextView createAiBubble(String text) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.setPadding(dp(2), 0, dp(2), 0);

        // 头像
        FrameLayout avatar = new FrameLayout(requireContext());
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                dp(30), dp(30)
        );
        avatarParams.topMargin = dp(2);
        avatarParams.rightMargin = dp(8);
        avatar.setLayoutParams(avatarParams);
        avatar.setBackgroundResource(R.drawable.bg_chat_avatar);

        TextView avatarText = new TextView(requireContext());
        avatarText.setText("农");
        avatarText.setTextColor(Color.WHITE);
        avatarText.setTextSize(13f);
        avatarText.setGravity(Gravity.CENTER);
        avatar.addView(avatarText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 气泡
        TextView bubble = new TextView(requireContext());
        bubble.setText(render(text));
        bubble.setTextColor(Color.parseColor("#E6EEF4"));
        bubble.setTextSize(14f);
        bubble.setLineSpacing(0f, 1.2f);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setMaxWidth(dp(360));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[]{dp(4), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14)});
        bg.setColor(Color.parseColor("#1A2B38"));
        bubble.setBackground(bg);

        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bubbleParams.bottomMargin = dp(10);

        LinearLayout bubbleWrap = new LinearLayout(requireContext());
        bubbleWrap.setOrientation(LinearLayout.VERTICAL);
        bubbleWrap.addView(bubble, bubbleParams);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        row.addView(avatar);
        row.addView(bubbleWrap);
        chatList.addView(row, rowLp);
        return bubble;
    }

    private void addAiBubble(String text) {
        createAiBubble(text);
        scrollToBottom();
    }

    // ==================== 轻量 Markdown 渲染 ====================

    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:\\w+)?\\s*\\n?([\\s\\S]*?)```");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern HEADING = Pattern.compile("^#{1,3}\\s*(.*)$");
    private static final Pattern HRULE = Pattern.compile("^\\s*-{3,}\\s*$");

    private CharSequence render(String text) {
        if (text == null) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher m = CODE_BLOCK.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(renderBlocks(text.substring(last, m.start())));
            sb.append('\n');
            int start = sb.length();
            sb.append(m.group(1));
            sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#9FE0FF")), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new BackgroundColorSpan(0xFF14232E), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
            last = m.end();
        }
        sb.append(renderBlocks(text.substring(last)));
        return sb;
    }

    /**
     * 逐行处理：Markdown 标题加粗变色、分隔线转为空行，其余走行内样式。
     */
    private CharSequence renderBlocks(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String content = heading.group(1);
                int start = sb.length();
                sb.append(content);
                if (!content.isEmpty()) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(1.12f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Color.parseColor("#8FD0F5")), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (HRULE.matcher(line).matches()) {
                // 分隔线：留空行即可
            } else {
                sb.append(renderInline(line));
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb;
    }

    private CharSequence renderInline(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        applyInline(sb, text);
        return sb;
    }

    private void applyInline(SpannableStringBuilder sb, String text) {
        // 先处理行内代码，避免与加粗冲突
        Matcher codeMatcher = INLINE_CODE.matcher(text);
        int last = 0;
        while (codeMatcher.find()) {
            appendBold(sb, text.substring(last, codeMatcher.start()));
            int start = sb.length();
            sb.append(codeMatcher.group(1));
            sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#7EC8F2")), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = codeMatcher.end();
        }
        appendBold(sb, text.substring(last));
    }

    private void appendBold(SpannableStringBuilder sb, String text) {
        Matcher m = BOLD.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            int start = sb.length();
            sb.append(m.group(1));
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        sb.append(text, last, text.length());
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
