package com.weiguang.drcontroller.navigationcheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Framework-only device check. Uses saved cases; never analyzes, captures or exposes. */
public final class VoiceReportDeviceCheck extends Instrumentation {
    private int checked;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            assertIdle();
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.weiguang.drcontroller.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            Activity activity = startActivitySync(intent);
            waitForIdleSync();
            checkCommands(activity);
            assertIdle();
            result.putString("status", "PASS");
            result.putInt("checks", checked);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("status", "FAIL");
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkCommands(Activity activity) throws Exception {
        Object chat = field(activity, "chatFragment").get(activity);
        Method command = chat.getClass().getDeclaredMethod("tryHandleDeviceCommand", String.class);
        command.setAccessible(true);
        Method stopWake = chat.getClass().getDeclaredMethod("stopWakeListening");
        stopWake.setAccessible(true);
        Method stopTts = chat.getClass().getDeclaredMethod("stopTts");
        stopTts.setAccessible(true);
        onUi(() -> {
            stopWake.invoke(chat);
            stopTts.invoke(chat);
            field(chat, "ttsEnabled").setBoolean(chat, false);
        });

        Class<?> repository = activity.getClassLoader().loadClass(
                "com.weiguang.drcontroller.hardware.storage.CaseRepository");
        List<?> records = (List<?>) repository.getMethod("queryCases", Context.class, int.class)
                .invoke(null, activity, 0);
        int withReport = -1, withoutReport = -1;
        for (int i = 0; i < records.size(); i++) {
            if ((boolean) call(records.get(i), "hasAiReport")) {
                if (withReport < 0) withReport = i;
            } else if (withoutReport < 0) withoutReport = i;
        }
        if (withReport < 0 || withoutReport < 0) {
            throw new AssertionError("Need existing cases both with and without saved reports");
        }
        SharedPreferences prefs = activity.getSharedPreferences("weiguang_case_records", Context.MODE_PRIVATE);
        Map<String, Boolean> readFlags = new HashMap<>();
        Map<String, ?> snapshot = prefs.getAll();
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            if (entry.getKey().startsWith("ai_read_")) {
                readFlags.put(entry.getKey(), (Boolean) entry.getValue());
            }
        }
        try {
            String caseId = (String) call(records.get(withReport), "getCaseId");
            String prefix = "第" + (withReport + 1) + "条历史记录";
            // Begin on a different case to verify that the ordinal wins over the visible selection.
            final String otherId = (String) call(records.get(withoutReport), "getCaseId");
            onUi(() -> activity.getClass().getMethod("openAiForCase", String.class).invoke(activity, otherId));
            expect(activity, chat, command, "打开" + prefix + "的AI分析", "AiReportFragment", caseId);
            expect(activity, chat, command, "帮我查看" + prefix + "的智能分析", "AiReportFragment", caseId);
            expect(activity, chat, command, "调取" + prefix + "的分析报告", "AiReportFragment", caseId);
            expect(activity, chat, command, "打开当前案例的AI分析", "AiReportFragment", caseId);
            expect(activity, chat, command, "打开" + prefix + "的分析页面", "AiFragment", caseId);
            expect(activity, chat, command, "打开这条历史记录的AI分析", "AiReportFragment", caseId);
            if ((boolean) call(records.get(0), "hasAiReport")) {
                expect(activity, chat, command, "打开最新一条的AI分析", "AiReportFragment",
                        (String) call(records.get(0), "getCaseId"));
            }
            String missingId = (String) call(records.get(withoutReport), "getCaseId");
            expect(activity, chat, command, "打开第" + (withoutReport + 1) + "条历史记录的AI分析",
                    "AiFragment", missingId);
            if (!missingId.equals(field(chat, "pendingAnalysisCaseId").get(chat))) {
                throw new AssertionError("Missing report did not set confirmation for the requested case");
            }
            checked++;
            field(chat, "pendingAnalysisCaseId").set(chat, null);
            expect(activity, chat, command, "打开第" + (records.size() + 1) + "条历史记录的AI分析",
                    "AiFragment", missingId);
            if (field(chat, "analysisRunning").getBoolean(chat)) {
                throw new AssertionError("Opening reports unexpectedly started a cloud analysis");
            }
            checkReading(activity, chat, command, prefix, caseId);
            onUi(() -> activity.getClass().getMethod("openChatPage").invoke(activity));
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("ai_read_")) editor.remove(key);
            }
            for (Map.Entry<String, Boolean> entry : readFlags.entrySet()) {
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
            editor.commit();
        }
    }

    private void expect(Activity activity, Object chat, Method command, String text,
                        String fragmentName, String caseId) throws Exception {
        onUi(() -> {
            if (!Boolean.TRUE.equals(command.invoke(chat, text))) {
                throw new AssertionError("Command was not handled locally: " + text);
            }
            Object fragment = field(activity, "currentPageFragment").get(activity);
            if (!fragment.getClass().getSimpleName().equals(fragmentName)) {
                throw new AssertionError(text + " opened " + fragment.getClass().getSimpleName()
                        + "; expected " + fragmentName);
            }
            if (!caseId.equals(call(activity, "getCurrentCaseId"))) {
                throw new AssertionError("Command opened a different case: " + text);
            }
        });
        checked++;
    }

    private interface UiStep { void run() throws Exception; }

    private void checkReading(Activity activity, Object chat, Method command,
                              String prefix, String caseId) throws Exception {
        // Hold the audio queue to inspect real speech text without playing or uploading it.
        onUi(() -> {
            field(chat, "pendingAnalysisCaseId").set(chat, null);
            field(chat, "ttsEnabled").setBoolean(chat, true);
            field(chat, "ttsPlaying").setBoolean(chat, true);
            command.invoke(chat, "帮我打开" + prefix + "的AI分析报告，并帮我读出来");
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (!caseId.equals(field(chat, "pendingFullReportCaseId").get(chat))
                && System.currentTimeMillis() < deadline) Thread.sleep(50);
        onUi(() -> {
            if (!caseId.equals(field(chat, "pendingFullReportCaseId").get(chat)))
                throw new AssertionError("Read request did not prepare spoken summary and follow-up");
            java.util.ArrayDeque<?> queue = (java.util.ArrayDeque<?>) field(chat, "ttsQueue").get(chat);
            if (queue.isEmpty()) throw new AssertionError("Report summary was never queued for speech");
            String summary = (String) field(queue.peekLast(), "spoken").get(queue.peekLast());
            if (summary.contains("简要结果已经生成")) throw new AssertionError("Summary has no report content");
            field(chat, "lastSpokenReply").set(chat, summary);
            field(chat, "lastSpokenReplyEndMs").setLong(chat, System.currentTimeMillis());
            Method send = chat.getClass().getDeclaredMethod("sendMessage", String.class);
            send.setAccessible(true);
            int before = queue.size();
            send.invoke(chat, "需要。");
            if (field(chat, "pendingFullReportCaseId").get(chat) != null || queue.size() <= before)
                throw new AssertionError("Affirmative reply was discarded instead of queuing full report");
            Method stop = chat.getClass().getDeclaredMethod("stopTts");
            stop.setAccessible(true);
            stop.invoke(chat);
            field(chat, "ttsEnabled").setBoolean(chat, false);
        });
        checked += 3;
        Method download = chat.getClass().getDeclaredMethod("downloadTtsAudio", String.class);
        download.setAccessible(true);
        // Only this fixed, non-sensitive sentence is sent to the speech provider.
        byte[] audio = (byte[]) download.invoke(chat, "语音播放测试。");
        if (audio.length < 1000) throw new AssertionError("TTS did not return report audio");
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        onUi(() -> {
            Method play = chat.getClass().getDeclaredMethod("playTtsAudio", byte[].class, Runnable.class);
            play.setAccessible(true);
            play.invoke(chat, audio, (Runnable) finished::countDown);
            android.media.MediaPlayer player = (android.media.MediaPlayer) field(chat, "ttsPlayer").get(chat);
            if (player == null || !player.isPlaying()) throw new AssertionError("Report audio did not start playing");
        });
        if (!finished.await(40, java.util.concurrent.TimeUnit.SECONDS))
            throw new AssertionError("Report audio playback did not complete");
        checked++;
    }

    private void onUi(UiStep step) {
        Throwable[] failure = {null};
        runOnMainSync(() -> {
            try { step.run(); }
            catch (Throwable error) { failure[0] = error; }
        });
        waitForIdleSync();
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void assertIdle() throws Exception {
        for (String pin : new String[]{"gpio3d2", "gpio3d3"}) {
            String value = new String(Files.readAllBytes(Paths.get("/sys/class/leds/", pin, "brightness"))).trim();
            if (!"255".equals(value)) throw new AssertionError("RF control is not idle; check stopped");
        }
    }
}
