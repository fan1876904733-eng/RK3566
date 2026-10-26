package com.weiguang.drcontroller.hardware.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import com.weiguang.drcontroller.hardware.storage.CaseRepository;
import com.weiguang.drcontroller.hardware.storage.ImageRepository;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 多模态案例分析公共入口。
 *
 * 负责：把一次检测案例的影像（可见光/热成像/X-Ray，按实际存在顺序传入）与雷达数据
 * 整理成提示词，压缩图片为 base64 data URL，调用 DeepSeek 多模态模型（deepseek-flash）
 * 生成 Markdown 检测报告，并保存到历史记录。
 *
 * 供两处复用：
 * 1. AI分析页（AiFragment）手动分析历史案例；
 * 2. 小农助手"一键全检"：自动拍摄后立即分析并语音播报（可携带用户描述的目标 hint）。
 */
public final class AiAnalyzer {

    public interface Callback {
        void onSuccess(String report);
        void onError(String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private AiAnalyzer() {
    }

    /**
     * 分析一个已保存的检测案例。
     *
     * @param context  上下文（建议传 application context）
     * @param record   案例记录（含影像引用与雷达快照）
     * @param userHint 用户对拍摄目标的口语描述，可为 null（如"我要拍一只白色的小兔子"）
     * @param callback 主线程回调：onSuccess(report) / onError(error)
     */
    public static void analyzeCase(
            Context context,
            CaseRepository.CaseRecord record,
            String userHint,
            Callback callback
    ) {
        if (context == null || record == null || callback == null) return;
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                List<DeepSeekClient.ImageInput> imageInputs = buildImageInputs(appContext, record);
                if (imageInputs.isEmpty()) {
                    callback.onError("案例中没有可读取的影像，请重新拍摄后再分析");
                    return;
                }
                String systemPrompt = buildSystemPrompt();
                String userText = buildAnalysisPrompt(record, userHint);
                new DeepSeekClient().chatWithImages(
                        systemPrompt,
                        userText,
                        imageInputs,
                        new DeepSeekClient.Callback() {
                            @Override
                            public void onSuccess(String reply) {
                                try {
                                    CaseRepository.saveAiReport(appContext, record.getCaseId(), reply);
                                } catch (Throwable saveError) {
                                    callback.onError("分析已完成，但报告保存失败："
                                            + (saveError.getMessage() == null
                                            ? saveError.getClass().getSimpleName()
                                            : saveError.getMessage()));
                                    return;
                                }
                                callback.onSuccess(reply);
                            }

                            @Override
                            public void onError(String error) {
                                callback.onError(error);
                            }
                        }
                );
            } catch (Throwable t) {
                callback.onError(t.getMessage() == null || t.getMessage().trim().isEmpty()
                        ? t.getClass().getSimpleName() : t.getMessage().trim());
            }
        });
    }

    private static String buildSystemPrompt() {
        return "你是资深影像检测分析专家，服务于微光智造手持智能DR设备（集成可见光相机、热成像、X-Ray、毫米波雷达）的检测报告系统。\n"
                + "用户会提供一次检测案例的影像及毫米波雷达数据，每张图前都有明确的设备类型标签（可见光/热成像/X-Ray），检测对象可能是人体、动植物或工业部件。\n"
                + "请以专业检测报告的口吻，严格按以下结构输出 Markdown 报告：\n"
                + "# 检测分析报告\n"
                + "## 一、基本信息\n"
                + "- 检测时间、检测对象（根据影像判断：人体/动植物/工业件，并简述判断依据）、检测方式（可见光/热成像/X-Ray，按实际收到的影像列明）\n"
                + "## 二、检测主体判定\n"
                + "- 识别画面中的检测主体（主要拍摄对象）：主体类型（人体部位/动植物/工业部件或构件）、主体在画面中的位置与大致占比、与背景的区分依据\n"
                + "- 后续所有分析仅围绕该主体进行，背景、陪衬与无关区域不纳入分析\n"
                + "## 三、影像质量评估\n"
                + "- 逐张评估：曝光/亮度、对比度、清晰度、伪影或欠曝光；是否满足判读要求；不满足时说明原因并建议复测\n"
                + "## 四、影像所见\n"
                + "- 可见光图：主体的形态、结构、表面特征\n"
                + "- 热成像图：主体的温度分布特征（相对冷/热区、分布均匀性）、异常热点/冷点\n"
                + "- X-Ray图：主体的密度与灰度差异、结构轮廓、疑似缺陷\n"
                + "- 只写影像中真实可见的内容；看不到的明确写\"影像中未见/无法判断\"\n"
                + "## 五、异常发现与评估\n"
                + "- 描述位置、形态、大小（可用影像中的相对比例）、初步严重程度\n"
                + "- 人体/医疗：解剖结构、软组织与骨骼密度对比、疑似异常区，提示需专业医师复核，不下诊断结论\n"
                + "- 动植物：形态、疑似病变/损伤/异物，提示需兽医或专业人员确认\n"
                + "- 工业无损检测：按NDT术语描述裂纹/未焊透/夹渣/气孔/夹杂/装配间隙/密度差异等；不确定缺陷性质时写\"疑似缺陷，建议按相关标准复检\"；如引用标准只写\"相关无损检测标准（如GB/T、ISO系列）\"字样，禁止编造具体标准编号\n"
                + "## 六、综合结论\n"
                + "- 设备工作状态、影像是否满足判读要求、是否需重点关注、是否需复测\n"
                + "## 七、建议\n"
                + "- 分场景给出后续操作建议（复测角度、参数调整、人员复核等）\n"
                + "硬性要求：所有分析必须聚焦画面中的检测主体（主要拍摄对象），背景与无关区域不纳入分析；只描述影像中实际可见的内容，禁止编造细节；不确定处标注\"待确认/无法判断\"；术语专业准确但不堆砌无依据结论；不使用医疗诊断用语替代专业复核；结尾固定一行：该内容仅供初步判断参考，最终以相关领域专业人员判断为准。\n"
                + "报告的最后必须单独一行、以\"对象速览：\"开头，用一句口语概括画面主体与整体状态，供语音播报直接朗读（例如：对象速览：画面主体是一只白色的小兔子，整体状态健康，未见明显异常。）。禁止在对象速览之外重复播报式长句。";
    }

    private static String buildAnalysisPrompt(CaseRepository.CaseRecord record, String userHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是该设备一次检测案例的影像，请严格依据每张图前的设备类型标签区分可见光、热成像和X-Ray，不要根据图片顺序猜测类型。请先判断检测场景，再按对应场景逐一分析。\n");
        sb.append("案例拍摄时间：").append(formatTime(record.getTimeMillis())).append("\n");
        if (userHint != null && !userHint.trim().isEmpty()) {
            sb.append("用户对本次拍摄目标的描述（仅供参考，请以影像实际内容为准，两者冲突时以影像为准并指出差异）：").append(userHint.trim()).append("\n");
        }
        CaseRepository.RadarSnapshot radar = record.getRadarSnapshot();
        if (radar == null) {
            sb.append("毫米波雷达数据：暂无\n");
        } else {
            sb.append("毫米波雷达数据：\n");
            sb.append("  连接状态：").append(radar.isConnected() ? "连接成功" : "拍摄时待连接").append("\n");
            sb.append("  目标状态：").append(radar.getTargetStatus()).append("\n");
            sb.append("  距离：").append(radar.getDistance()).append("\n");
            sb.append("  呼吸：").append(radar.getBreathRate()).append("\n");
            sb.append("  心率：").append(radar.getHeartRate()).append("\n");
        }
        sb.append("\n请严格按专业检测报告模板输出上述内容，并记得在报告最后输出\"对象速览：\"一行。");
        return sb.toString();
    }

    /** 读取案例的可见光/热成像/X-Ray 图片，压缩为 base64 data URL 列表（可空）。 */
    private static List<DeepSeekClient.ImageInput> buildImageInputs(
            Context context,
            CaseRepository.CaseRecord record
    ) {
        List<DeepSeekClient.ImageInput> inputs = new ArrayList<>();
        addImageInput(inputs, context, record.getCameraImage(), "可见光实时图");
        addImageInput(inputs, context, record.getThermalImage(), "热成像图");
        addImageInput(inputs, context, record.getXrayImage(), "X-Ray影像图");
        return inputs;
    }

    private static void addImageInput(
            List<DeepSeekClient.ImageInput> inputs,
            Context context,
            ImageRepository.ImageRecord image,
            String label
    ) {
        if (image == null || image.getUri() == null) return;
        String dataUrl = compressToDataUrl(context, image.getUri(), 1024, 85);
        if (dataUrl != null) inputs.add(new DeepSeekClient.ImageInput(label, dataUrl));
    }

    /**
     * 从 MediaStore 读取图片 → 采样解码 → 限制最长边 → JPEG 压缩 → base64 data URL。
     * 任一步失败返回 null（调用方跳过该图），不抛出。
     */
    private static String compressToDataUrl(Context context, Uri uri, int maxEdge, int quality) {
        try {
            ContentResolver resolver = context.getContentResolver();

            // 先读尺寸，确定采样率，避免大图 OOM
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (width / (sample * 2) >= maxEdge
                    && height / (sample * 2) >= maxEdge) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bitmap;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return null;
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bitmap == null) return null;

            Bitmap finalBitmap = bitmap;
            int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (maxSide > maxEdge) {
                float scale = (float) maxEdge / maxSide;
                int newWidth = Math.max(1, (int) (bitmap.getWidth() * scale));
                int newHeight = Math.max(1, (int) (bitmap.getHeight() * scale));
                finalBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
                if (finalBitmap != bitmap) bitmap.recycle();
            }

            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                boolean ok = finalBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        quality,
                        bos
                );
                if (!ok) return null;
                byte[] jpeg = bos.toByteArray();
                return "data:image/jpeg;base64,"
                        + Base64.encodeToString(jpeg, Base64.NO_WRAP);
            } finally {
                finalBitmap.recycle();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatTime(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(timeMillis > 0L ? timeMillis : System.currentTimeMillis()));
    }
}
