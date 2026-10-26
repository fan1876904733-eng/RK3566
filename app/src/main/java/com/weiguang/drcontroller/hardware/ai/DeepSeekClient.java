package com.weiguang.drcontroller.hardware.ai;

import android.os.Handler;
import android.os.Looper;

import com.weiguang.drcontroller.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * DeepSeek 通用对话客户端（非流式）。
 *
 * - chat()：纯文本对话，使用 deepseek-chat
 * - chatWithImages()：图文混合（多模态），使用 deepseek-flash，
 *   图片以 base64 data URL 内联传入（格式见官方"图像理解"文档）
 * Key 统一取自 BuildConfig，维护位置：app/build.gradle.kts buildConfigField。
 */
public final class DeepSeekClient {

    /** 带明确设备类型的图像，避免在某张图缺失时按位置误判。 */
    public static final class ImageInput {
        private final String label;
        private final String dataUrl;

        public ImageInput(String label, String dataUrl) {
            this.label = label == null ? "未知影像" : label;
            this.dataUrl = dataUrl;
        }
    }

    public interface Callback {
        void onSuccess(String reply);
        void onError(String error);
    }

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String MODEL_TEXT = "deepseek-chat";
    private static final String MODEL_VISION = "deepseek-flash";

    private final OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public DeepSeekClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build();
    }

    /** 纯文本对话（deepseek-chat），结果在主线程回调。 */
    public void chat(String systemPrompt, String userText, Callback callback) {
        post(systemPrompt, userText, null, MODEL_TEXT, callback);
    }

    /**
     * 图文混合对话（deepseek-flash 多模态）。
     *
     * @param systemPrompt 系统提示（仅文本，图片只允许出现在 user 消息）
     * @param userText     用户文本
     * @param imageDataUrls 图片 base64 data URL 列表（如 data:image/jpeg;base64,...），可为空
     */
    public void chatWithImages(
            String systemPrompt,
            String userText,
            List<ImageInput> imageInputs,
            Callback callback
    ) {
        post(systemPrompt, userText, imageInputs, MODEL_VISION, callback);
    }

    private void post(
            String systemPrompt,
            String userText,
            List<ImageInput> imageInputs,
            String model,
            Callback callback
    ) {
        try {
            JSONArray messages = new JSONArray();

            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", systemPrompt == null ? "" : systemPrompt);
            messages.put(system);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            if (imageInputs != null && !imageInputs.isEmpty()) {
                JSONArray content = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("type", "text");
                textPart.put("text", userText == null ? "" : userText);
                content.put(textPart);
                for (ImageInput input : imageInputs) {
                    if (input == null || input.dataUrl == null || input.dataUrl.isEmpty()) continue;
                    JSONObject labelPart = new JSONObject();
                    labelPart.put("type", "text");
                    labelPart.put("text", "下一张图像类型：" + input.label);
                    content.put(labelPart);
                    JSONObject imagePart = new JSONObject();
                    imagePart.put("type", "image_url");
                    JSONObject imageUrl = new JSONObject();
                    imageUrl.put("url", input.dataUrl);
                    imageUrl.put("detail", "high");
                    imagePart.put("image_url", imageUrl);
                    content.put(imagePart);
                }
                user.put("content", content);
            } else {
                user.put("content", userText == null ? "" : userText);
            }
            messages.put(user);

            JSONObject reqBody = new JSONObject();
            reqBody.put("model", model);
            reqBody.put("messages", messages);
            reqBody.put("max_tokens", 1200);
            reqBody.put("stream", false);
            reqBody.put("temperature", 0.3);

            Request request = new Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer " + BuildConfig.DEEPSEEK_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(
                            reqBody.toString(),
                            MediaType.parse("application/json; charset=utf-8")
                    ))
                    .build();

            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "网络错误：" + safeMessage(e));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response r = response) {
                        String bodyStr = r.body() == null ? "" : r.body().string();
                        if (!r.isSuccessful()) {
                            postError(callback, "服务返回 " + r.code() + "：" + bodyStr);
                            return;
                        }
                        JSONObject json = new JSONObject(bodyStr);
                        String reply = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                        String finalReply = reply == null ? "" : reply.trim();
                        mainHandler.post(() -> callback.onSuccess(finalReply));
                    } catch (Exception e) {
                        postError(callback, "解析错误：" + safeMessage(e));
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, "请求错误：" + safeMessage(e));
        }
    }

    private void postError(Callback callback, String error) {
        mainHandler.post(() -> callback.onError(error));
    }

    private String safeMessage(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }
}
