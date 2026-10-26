package com.weiguang.drcontroller.hardware.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.weiguang.drcontroller.hardware.radar.RadarReading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 以“案例/事件”为单位组织摄像头、热成像、X-Ray、毫米波快照和AI报告。
 * 同一次拍摄使用同一个 captureId，因此历史记录不再按影像类型拆散显示。
 */
public final class CaseRepository {

    public interface DeleteCallback {
        void onDeleted();
        void onError(String message);
    }

    public static final class RadarSnapshot {
        private final boolean connected;
        private final String targetStatus;
        private final String distance;
        private final String breathRate;
        private final String heartRate;
        private final long timeMillis;

        public RadarSnapshot(
                boolean connected,
                String targetStatus,
                String distance,
                String breathRate,
                String heartRate,
                long timeMillis
        ) {
            this.connected = connected;
            this.targetStatus = safe(targetStatus, "未检测到物体");
            this.distance = safe(distance, "-- cm");
            this.breathRate = safe(breathRate, "-- bpm");
            this.heartRate = safe(heartRate, "-- bpm");
            this.timeMillis = timeMillis;
        }

        public boolean isConnected() { return connected; }
        public String getTargetStatus() { return targetStatus; }
        public String getDistance() { return distance; }
        public String getBreathRate() { return breathRate; }
        public String getHeartRate() { return heartRate; }
        public long getTimeMillis() { return timeMillis; }
    }

    public static final class CaseRecord {
        private final String caseId;
        private long timeMillis;
        private ImageRepository.ImageRecord cameraImage;
        private ImageRepository.ImageRecord thermalImage;
        private ImageRepository.ImageRecord xrayImage;
        private final List<ImageRepository.ImageRecord> allImages = new ArrayList<>();
        private RadarSnapshot radarSnapshot;
        private String aiReport;
        private String displayName;

        private CaseRecord(String caseId, long timeMillis) {
            this.caseId = caseId;
            this.timeMillis = timeMillis;
        }

        private void addImage(ImageRepository.ImageRecord record) {
            if (record == null) return;
            allImages.add(record);
            if (timeMillis <= 0L || record.getTimeMillis() < timeMillis) {
                timeMillis = record.getTimeMillis();
            }
            if (record.getImageType() == ImageRepository.ImageType.CAMERA) {
                cameraImage = newer(cameraImage, record);
            } else if (record.getImageType() == ImageRepository.ImageType.THERMAL) {
                thermalImage = newer(thermalImage, record);
            } else if (record.getImageType() == ImageRepository.ImageType.XRAY) {
                xrayImage = newer(xrayImage, record);
            }
        }

        private ImageRepository.ImageRecord newer(
                ImageRepository.ImageRecord oldRecord,
                ImageRepository.ImageRecord newRecord
        ) {
            if (oldRecord == null) return newRecord;
            return newRecord.getTimeMillis() >= oldRecord.getTimeMillis()
                    ? newRecord : oldRecord;
        }

        public String getCaseId() { return caseId; }
        public long getTimeMillis() { return timeMillis; }
        public ImageRepository.ImageRecord getCameraImage() { return cameraImage; }
        public ImageRepository.ImageRecord getThermalImage() { return thermalImage; }
        public ImageRepository.ImageRecord getXrayImage() { return xrayImage; }
        public RadarSnapshot getRadarSnapshot() { return radarSnapshot; }
        public String getAiReport() { return aiReport; }
        /** 用户自定义名称；未命名时返回 null（界面回退显示拍摄时间） */
        public String getDisplayName() { return displayName; }
        public boolean hasAiReport() { return aiReport != null && !aiReport.trim().isEmpty(); }
        public boolean hasCameraImage() { return cameraImage != null; }
        public boolean hasThermalImage() { return thermalImage != null; }
        public boolean hasXrayImage() { return xrayImage != null; }
        private void setRadarSnapshot(RadarSnapshot snapshot) { radarSnapshot = snapshot; }
        private void setAiReport(String report) { aiReport = report; }
        private void setDisplayName(String name) { displayName = name; }
        private void setTimeMillis(long timeMillis) {
            if (timeMillis > 0L) this.timeMillis = timeMillis;
        }
    }

    private static final String PREFS = "weiguang_case_records";
    private static final String KEY_ACTIVE_ID = "active_case_id";
    private static final String KEY_ACTIVE_TIME = "active_case_time";
    private static final String PREFIX_CASE_TIME = "case_time_";
    private static final String PREFIX_CASE_NAME = "case_name_";
    private static final String PREFIX_AI_REPORT = "ai_report_";
    private static final String PREFIX_AI_READ = "ai_read_";
    private static final String PREFIX_RADAR_CONNECTED = "radar_connected_";
    private static final String PREFIX_RADAR_TARGET = "radar_target_";
    private static final String PREFIX_RADAR_DISTANCE = "radar_distance_";
    private static final String PREFIX_RADAR_BREATH = "radar_breath_";
    private static final String PREFIX_RADAR_HEART = "radar_heart_";
    private static final String PREFIX_RADAR_TIME = "radar_time_";

    private static final Pattern FILE_PATTERN = Pattern.compile(
            "^WG_(\\d{8}_\\d{6}_\\d{3})_(Camera|Thermal|XRay)(?: \\(\\d+\\))?\\..+$",
            Pattern.CASE_INSENSITIVE
    );

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private CaseRepository() { }

    public static void activateCase(Context context, String caseId, long timeMillis) {
        if (context == null || isBlank(caseId)) return;
        long validTime = timeMillis > 0L ? timeMillis : System.currentTimeMillis();
        prefs(context).edit()
                .putString(KEY_ACTIVE_ID, caseId)
                .putLong(KEY_ACTIVE_TIME, validTime)
                .putLong(PREFIX_CASE_TIME + caseId, validTime)
                .apply();
    }

    /**
     * 返回最近正在使用的案例。超过 maxAgeMillis 后不再自动把新X-Ray并入旧案例。
     */
    public static String getActiveCaseId(
            Context context,
            long referenceTimeMillis,
            long maxAgeMillis
    ) {
        SharedPreferences preferences = prefs(context);
        String caseId = preferences.getString(KEY_ACTIVE_ID, null);
        long caseTime = preferences.getLong(KEY_ACTIVE_TIME, 0L);
        if (isBlank(caseId) || caseTime <= 0L) return null;
        long now = referenceTimeMillis > 0L
                ? referenceTimeMillis : System.currentTimeMillis();
        if (maxAgeMillis > 0L && Math.abs(now - caseTime) > maxAgeMillis) {
            return null;
        }
        return caseId;
    }

    public static void ensureCaseTime(Context context, String caseId, long timeMillis) {
        if (context == null || isBlank(caseId)) return;
        SharedPreferences preferences = prefs(context);
        String key = PREFIX_CASE_TIME + caseId;
        if (!preferences.contains(key)) {
            preferences.edit().putLong(
                    key,
                    timeMillis > 0L ? timeMillis : System.currentTimeMillis()
            ).apply();
        }
    }

    /** 仅更新案例展示时间，不改变当前活动案例。 */
    public static void setCaseTime(Context context, String caseId, long timeMillis) {
        if (context == null || isBlank(caseId)) return;
        prefs(context).edit().putLong(
                PREFIX_CASE_TIME + caseId,
                timeMillis > 0L ? timeMillis : System.currentTimeMillis()
        ).apply();
    }

    /** 设置案例自定义名称（≤20字）；传空值则清除名称，回退显示拍摄时间。 */
    public static void setCaseName(Context context, String caseId, String name) {
        if (context == null || isBlank(caseId)) return;
        String clean = name == null ? "" : name.trim();
        if (clean.length() > 20) clean = clean.substring(0, 20);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (clean.isEmpty()) {
            editor.remove(PREFIX_CASE_NAME + caseId);
        } else {
            editor.putString(PREFIX_CASE_NAME + caseId, clean);
        }
        editor.apply();
    }

    /** 读取案例自定义名称；未命名返回 null。 */
    public static String getCaseName(Context context, String caseId) {
        if (context == null || isBlank(caseId)) return null;
        String name = prefs(context).getString(PREFIX_CASE_NAME + caseId, null);
        return isBlank(name) ? null : name.trim();
    }

    public static void saveRadarSnapshot(
            Context context,
            String caseId,
            RadarReading reading,
            long captureTimeMillis
    ) {
        if (context == null || isBlank(caseId)) return;
        RadarReading safeReading = reading == null ? RadarReading.empty() : reading;
        long validTime = captureTimeMillis > 0L
                ? captureTimeMillis
                : (safeReading.getDataTimeMillis() > 0L
                ? safeReading.getDataTimeMillis()
                : System.currentTimeMillis());

        prefs(context).edit()
                .putBoolean(PREFIX_RADAR_CONNECTED + caseId, safeReading.isConnected())
                .putString(PREFIX_RADAR_TARGET + caseId, safeReading.getTargetStatus())
                .putString(PREFIX_RADAR_DISTANCE + caseId, safeReading.getDistance())
                .putString(PREFIX_RADAR_BREATH + caseId, safeReading.getBreathRate())
                .putString(PREFIX_RADAR_HEART + caseId, safeReading.getHeartRate())
                .putLong(PREFIX_RADAR_TIME + caseId, validTime)
                .apply();
        ensureCaseTime(context, caseId, validTime);
    }

    public static void saveAiReport(Context context, String caseId, String report) {
        if (context == null || isBlank(caseId)) return;
        prefs(context).edit()
                .putString(PREFIX_AI_REPORT + caseId, sanitizeAiReport(report))
                .putBoolean(PREFIX_AI_READ + caseId, false)
                .apply();
    }

    /** 标记该案例的AI报告已读（查看后历史蓝点消失）。 */
    public static void markAiReportRead(Context context, String caseId) {
        if (context == null || isBlank(caseId)) return;
        prefs(context).edit().putBoolean(PREFIX_AI_READ + caseId, true).apply();
    }

    /** 该案例的AI报告是否已读。无报告视为已读；有报告但从未查看过视为未读。 */
    public static boolean isAiReportRead(Context context, String caseId) {
        if (context == null || isBlank(caseId)) return true;
        return prefs(context).getBoolean(PREFIX_AI_READ + caseId, false);
    }

    public static String getAiReport(Context context, String caseId) {
        if (context == null || isBlank(caseId)) return "";
        String stored = prefs(context).getString(PREFIX_AI_REPORT + caseId, "");
        String clean = sanitizeAiReport(stored);
        if (!clean.equals(stored)) {
            prefs(context).edit().putString(PREFIX_AI_REPORT + caseId, clean).apply();
        }
        return clean;
    }

    public static List<CaseRecord> queryCases(Context context, int maxCases) {
        if (context == null) return Collections.emptyList();
        int imageLimit = maxCases <= 0 ? 0 : Math.max(maxCases * 4, 120);
        List<ImageRepository.ImageRecord> images = ImageRepository.queryHistory(context, imageLimit);
        Map<String, CaseRecord> grouped = new LinkedHashMap<>();

        for (ImageRepository.ImageRecord image : images) {
            String caseId = extractCaseId(image);
            CaseRecord record = grouped.get(caseId);
            if (record == null) {
                record = new CaseRecord(caseId, image.getTimeMillis());
                grouped.put(caseId, record);
            }
            record.addImage(image);
        }

        SharedPreferences preferences = prefs(context);
        for (CaseRecord record : grouped.values()) {
            long savedTime = preferences.getLong(PREFIX_CASE_TIME + record.caseId, 0L);
            if (savedTime > 0L) record.setTimeMillis(savedTime);
            String storedReport = preferences.getString(PREFIX_AI_REPORT + record.caseId, "");
            String cleanReport = sanitizeAiReport(storedReport);
            record.setAiReport(cleanReport);
            record.setDisplayName(preferences.getString(PREFIX_CASE_NAME + record.caseId, null));
            if (!cleanReport.equals(storedReport)) {
                preferences.edit().putString(PREFIX_AI_REPORT + record.caseId, cleanReport).apply();
            }

            boolean hasRadar = preferences.contains(PREFIX_RADAR_TIME + record.caseId)
                    || preferences.contains(PREFIX_RADAR_CONNECTED + record.caseId);
            if (hasRadar) {
                record.setRadarSnapshot(new RadarSnapshot(
                        preferences.getBoolean(PREFIX_RADAR_CONNECTED + record.caseId, false),
                        preferences.getString(PREFIX_RADAR_TARGET + record.caseId, "未检测到物体"),
                        preferences.getString(PREFIX_RADAR_DISTANCE + record.caseId, "-- cm"),
                        preferences.getString(PREFIX_RADAR_BREATH + record.caseId, "-- bpm"),
                        preferences.getString(PREFIX_RADAR_HEART + record.caseId, "-- bpm"),
                        preferences.getLong(PREFIX_RADAR_TIME + record.caseId, 0L)
                ));
            }
        }

        List<CaseRecord> result = new ArrayList<>(grouped.values());
        result.sort((left, right) -> Long.compare(right.getTimeMillis(), left.getTimeMillis()));
        if (maxCases > 0 && result.size() > maxCases) {
            return new ArrayList<>(result.subList(0, maxCases));
        }
        return result;
    }

    public static CaseRecord findCase(Context context, String caseId) {
        if (context == null || isBlank(caseId)) return null;
        List<CaseRecord> records = queryCases(context, 0);
        for (CaseRecord record : records) {
            if (caseId.equals(record.getCaseId())) return record;
        }
        return null;
    }

    public static void deleteCase(
            Context context,
            CaseRecord record,
            DeleteCallback callback
    ) {
        if (context == null || record == null) {
            postDeleteError(callback, "案例不存在");
            return;
        }
        Context appContext = context.getApplicationContext();
        IO_EXECUTOR.execute(() -> {
            try {
                CaseRecord completeRecord = findCase(appContext, record.getCaseId());
                if (completeRecord == null) completeRecord = record;

                ContentResolver resolver = appContext.getContentResolver();
                for (ImageRepository.ImageRecord image : completeRecord.allImages) {
                    deleteImage(resolver, image);
                }
                clearMetadata(appContext, record.getCaseId());
                if (callback != null) MAIN_HANDLER.post(callback::onDeleted);
            } catch (Exception exception) {
                postDeleteError(
                        callback,
                        "删除失败：" + safe(exception.getMessage(), exception.getClass().getSimpleName())
                );
            }
        });
    }

    private static void deleteImage(
            ContentResolver resolver,
            ImageRepository.ImageRecord image
    ) {
        if (resolver == null || image == null || image.getUri() == null) return;
        resolver.delete(image.getUri(), null, null);
    }

    private static void clearMetadata(Context context, String caseId) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit()
                .remove(PREFIX_CASE_TIME + caseId)
                .remove(PREFIX_CASE_NAME + caseId)
                .remove(PREFIX_AI_REPORT + caseId)
                .remove(PREFIX_AI_READ + caseId)
                .remove(PREFIX_RADAR_CONNECTED + caseId)
                .remove(PREFIX_RADAR_TARGET + caseId)
                .remove(PREFIX_RADAR_DISTANCE + caseId)
                .remove(PREFIX_RADAR_BREATH + caseId)
                .remove(PREFIX_RADAR_HEART + caseId)
                .remove(PREFIX_RADAR_TIME + caseId);
        String activeId = preferences.getString(KEY_ACTIVE_ID, null);
        if (caseId.equals(activeId)) {
            editor.remove(KEY_ACTIVE_ID).remove(KEY_ACTIVE_TIME);
        }
        editor.apply();
    }

    private static String extractCaseId(ImageRepository.ImageRecord image) {
        String name = image == null ? null : image.getDisplayName();
        if (name != null) {
            Matcher matcher = FILE_PATTERN.matcher(name);
            if (matcher.matches()) return matcher.group(1);
        }
        // 非标准文件名：同一拍摄时间的各类型图归入同一案例，
        // 避免 Camera/Thermal/XRay 被拆成多个孤立案例、AI报告关联不上
        long time = image == null ? System.currentTimeMillis() : image.getTimeMillis();
        return ImageRepository.buildCaptureId(time);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void postDeleteError(DeleteCallback callback, String message) {
        if (callback != null) MAIN_HANDLER.post(() -> callback.onError(message));
    }

    /**
     * 对报告做基础格式整理（去空行、合并重复的结尾声明）。
     * 不删除报告中的演示/临床声明，保持内容如实可见。
     */
    private static String sanitizeAiReport(String report) {
        if (report == null || report.trim().isEmpty()) return "";
        String[] lines = report.replace("\r", "").split("\n");
        StringBuilder clean = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                if (clean.length() > 0 && clean.charAt(clean.length() - 1) != '\n') clean.append('\n');
                continue;
            }
            if (line.equals("该内容仅供初步判断参考。")) continue;
            if (clean.length() > 0 && clean.charAt(clean.length() - 1) != '\n') clean.append('\n');
            clean.append(line);
        }
        String result = clean.toString().trim();
        if (result.isEmpty()) return "该内容仅供初步判断参考。";
        return result + "\n\n该内容仅供初步判断参考。";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
