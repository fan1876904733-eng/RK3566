package com.weiguang.drcontroller.hardware.storage;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一影像仓库。
 *
 * 摄像头、热成像和X-Ray都写入MediaStore的Pictures/WeiguangDR目录，
 * 历史记录页面只依赖本类，不依赖具体摄像头或厂商SDK。
 */
public final class ImageRepository {

    public enum ImageType {
        CAMERA("Camera", "摄像头"),
        THERMAL("Thermal", "热成像"),
        XRAY("XRay", "X-Ray");

        private final String folderName;
        private final String displayName;

        ImageType(String folderName, String displayName) {
            this.folderName = folderName;
            this.displayName = displayName;
        }

        public String getFolderName() {
            return folderName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public interface SaveCallback {
        void onSaved(Uri uri);

        void onError(String message);
    }

    public static final class ImageRecord {
        private final Uri uri;
        private final String displayName;
        private final String mimeType;
        private final ImageType imageType;
        private final long timeMillis;

        public ImageRecord(
                Uri uri,
                String displayName,
                String mimeType,
                ImageType imageType,
                long timeMillis
        ) {
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.imageType = imageType;
            this.timeMillis = timeMillis;
        }

        public Uri getUri() {
            return uri;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public ImageType getImageType() {
            return imageType;
        }

        public long getTimeMillis() {
            return timeMillis;
        }
    }

    private static final String ROOT_RELATIVE_PATH = "Pictures/WeiguangDR/";
    private static final ExecutorService IO_EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private ImageRepository() {
    }

    public static String buildCaptureId(long timeMillis) {
        long validTime = timeMillis > 0L
                ? timeMillis
                : System.currentTimeMillis();
        return new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.CHINA
        ).format(new Date(validTime));
    }

    public static ContentValues createContentValues(
            ImageType type,
            String captureId,
            String mimeType
    ) {
        String extension = "image/png".equals(mimeType) ? ".png" : ".jpg";
        String safeId = captureId == null || captureId.trim().isEmpty()
                ? buildCaptureId(System.currentTimeMillis())
                : captureId.trim();

        ContentValues values = new ContentValues();
        values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "WG_" + safeId + "_" + type.getFolderName() + extension
        );
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    ROOT_RELATIVE_PATH + type.getFolderName()
            );
        }
        return values;
    }

    public static void saveBitmap(
            Context context,
            Bitmap bitmap,
            ImageType type,
            String captureId,
            SaveCallback callback
    ) {
        if (bitmap == null) {
            postError(callback, "没有可保存的图像帧");
            return;
        }

        Bitmap safeBitmap = bitmap.copy(
                bitmap.getConfig() == null
                        ? Bitmap.Config.ARGB_8888
                        : bitmap.getConfig(),
                false
        );
        if (safeBitmap == null) {
            postError(callback, "无法复制待保存图像");
            return;
        }

        IO_EXECUTOR.execute(() -> {
            Uri uri = null;
            try {
                ContentResolver resolver = context
                        .getApplicationContext()
                        .getContentResolver();
                ContentValues values = createContentValues(
                        type,
                        captureId,
                        "image/png"
                );
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }

                uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                );
                if (uri == null) {
                    throw new IOException("MediaStore未创建文件");
                }

                try (OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null || !safeBitmap.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            output
                    )) {
                        throw new IOException("图像编码失败");
                    }
                }

                finishPending(resolver, uri);
                postSaved(callback, uri);
            } catch (Exception exception) {
                deleteQuietly(context, uri);
                postError(callback, "保存失败：" + safeMessage(exception));
            } finally {
                safeBitmap.recycle();
            }
        });
    }


    /** 保存Legacy Camera返回的JPEG字节，避免先解码成Bitmap造成额外内存占用。 */
    public static void saveJpegBytes(
            Context context,
            byte[] jpegBytes,
            ImageType type,
            String captureId,
            SaveCallback callback
    ) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            postError(callback, "JPEG图像数据为空");
            return;
        }
        byte[] safeBytes = jpegBytes.clone();
        IO_EXECUTOR.execute(() -> {
            Uri uri = null;
            try {
                ContentResolver resolver = context.getApplicationContext().getContentResolver();
                ContentValues values = createContentValues(type, captureId, "image/jpeg");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("MediaStore未创建文件");
                try (OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null) throw new IOException("无法打开图像输出流");
                    output.write(safeBytes);
                    output.flush();
                }
                finishPending(resolver, uri);
                postSaved(callback, uri);
            } catch (Exception exception) {
                deleteQuietly(context, uri);
                postError(callback, "保存失败：" + safeMessage(exception));
            }
        });
    }

    public static void saveXrayBytes(
            Context context,
            byte[] imageBytes,
            long captureTimeMillis,
            SaveCallback callback
    ) {
        saveXrayBytes(
                context,
                imageBytes,
                captureTimeMillis,
                buildCaptureId(captureTimeMillis),
                callback
        );
    }

    public static void saveXrayBytes(
            Context context,
            byte[] imageBytes,
            long captureTimeMillis,
            String captureId,
            SaveCallback callback
    ) {
        if (imageBytes == null || imageBytes.length == 0) {
            postError(callback, "X-Ray图像数据为空");
            return;
        }

        byte[] safeBytes = imageBytes.clone();
        String safeCaptureId = captureId == null || captureId.trim().isEmpty()
                ? buildCaptureId(captureTimeMillis)
                : captureId.trim();

        IO_EXECUTOR.execute(() -> {
            Uri uri = null;
            try {
                // 迷你主机可能下发 BMP 位图（任务5）：归档前转成 JPEG，
                // 避免以 .jpg 后缀保存 BMP 内容导致历史页/详情页缩略图异常；
                // 解码失败则按原样保存兜底，不丢数据。
                byte[] finalBytes = safeBytes;
                String finalMime = isPng(safeBytes) ? "image/png" : "image/jpeg";
                if (isBmp(safeBytes)) {
                    byte[] jpeg = convertBmpToJpeg(safeBytes);
                    if (jpeg != null) {
                        finalBytes = jpeg;
                        finalMime = "image/jpeg";
                    }
                }

                ContentResolver resolver = context
                        .getApplicationContext()
                        .getContentResolver();
                ContentValues values = createContentValues(
                        ImageType.XRAY,
                        safeCaptureId,
                        finalMime
                );
                values.put(
                        MediaStore.Images.Media.DATE_TAKEN,
                        captureTimeMillis > 0L
                                ? captureTimeMillis
                                : System.currentTimeMillis()
                );
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }

                uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                );
                if (uri == null) {
                    throw new IOException("MediaStore未创建X-Ray文件");
                }

                try (OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null) {
                        throw new IOException("无法打开X-Ray输出流");
                    }
                    output.write(finalBytes);
                    output.flush();
                }

                finishPending(resolver, uri);
                postSaved(callback, uri);
            } catch (Exception exception) {
                deleteQuietly(context, uri);
                postError(callback, "X-Ray归档失败：" + safeMessage(exception));
            }
        });
    }

    public static List<ImageRecord> queryHistory(
            Context context,
            int maxCount
    ) {
        List<ImageRecord> records = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();

        List<String> projectionList = new ArrayList<>();
        projectionList.add(MediaStore.Images.Media._ID);
        projectionList.add(MediaStore.Images.Media.DISPLAY_NAME);
        projectionList.add(MediaStore.Images.Media.MIME_TYPE);
        projectionList.add(MediaStore.Images.Media.DATE_ADDED);
        projectionList.add(MediaStore.Images.Media.DATE_TAKEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Images.Media.RELATIVE_PATH);
        } else {
            projectionList.add(MediaStore.Images.Media.DATA);
        }

        String selection;
        String[] selectionArgs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            selectionArgs = new String[]{ROOT_RELATIVE_PATH + "%"};
        } else {
            selection = MediaStore.Images.Media.DATA + " LIKE ?";
            selectionArgs = new String[]{"%/Pictures/WeiguangDR/%"};
        }

        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projectionList.toArray(new String[0]),
                selection,
                selectionArgs,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor == null) {
                return records;
            }

            int idIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media._ID
            );
            int nameIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DISPLAY_NAME
            );
            int mimeIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.MIME_TYPE
            );
            int addedIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DATE_ADDED
            );
            int takenIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DATE_TAKEN
            );
            int pathIndex = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.RELATIVE_PATH
            )
                    : cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DATA
            );

            while (cursor.moveToNext()
                    && (maxCount <= 0 || records.size() < maxCount)) {
                long id = cursor.getLong(idIndex);
                String name = cursor.getString(nameIndex);
                String mime = cursor.getString(mimeIndex);
                String path = cursor.getString(pathIndex);
                long taken = cursor.getLong(takenIndex);
                long added = cursor.getLong(addedIndex) * 1000L;

                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                );
                records.add(new ImageRecord(
                        uri,
                        name == null ? "未命名影像" : name,
                        mime == null ? "image/*" : mime,
                        classify(path, name),
                        taken > 0L ? taken : added
                ));
            }
        } catch (SecurityException ignored) {
            // 权限不足时由历史页面提示，避免应用崩溃。
        }
        return records;
    }

    public static Bitmap loadThumbnail(
            Context context,
            Uri uri,
            int width,
            int height
    ) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return resolver.loadThumbnail(
                    uri,
                    new Size(width, height),
                    null
            );
        }

        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("无法读取影像");
            }
            return BitmapFactory.decodeStream(input);
        }
    }

    private static ImageType classify(String path, String name) {
        String source = ((path == null ? "" : path)
                + "/"
                + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
        if (source.contains("thermal")) {
            return ImageType.THERMAL;
        }
        if (source.contains("xray") || source.contains("x-ray")) {
            return ImageType.XRAY;
        }
        return ImageType.CAMERA;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47;
    }

    private static boolean isBmp(byte[] bytes) {
        return bytes.length >= 2
                && bytes[0] == 'B'
                && bytes[1] == 'M';
    }

    /** 将 BMP 位图转成 JPEG 字节；解码失败返回 null，由调用方按原样保存兜底。 */
    private static byte[] convertBmpToJpeg(byte[] bmpBytes) {
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(
                    bmpBytes,
                    0,
                    bmpBytes.length
            );
            if (bitmap == null) {
                return null;
            }
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                boolean ok = bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        90,
                        bos
                );
                return ok ? bos.toByteArray() : null;
            } finally {
                bitmap.recycle();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void finishPending(ContentResolver resolver, Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
    }

    private static void deleteQuietly(Context context, Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
            // 清理失败不覆盖原错误。
        }
    }

    private static void postSaved(SaveCallback callback, Uri uri) {
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onSaved(uri));
        }
    }

    private static void postError(SaveCallback callback, String message) {
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onError(message));
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

}
