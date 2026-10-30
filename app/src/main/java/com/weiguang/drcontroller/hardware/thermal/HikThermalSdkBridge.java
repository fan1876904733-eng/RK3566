package com.weiguang.drcontroller.hardware.thermal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/** Runtime bridge for the vendor HCUSBSDK used by DS-2TM01-3XF/TB. */
public final class HikThermalSdkBridge {
    private static final String TAG = "WG-HIK-SDK";
    private static final int VID_HIK = 0x2bdf;
    private static final int PID_4117 = 0x0101;
    private static final int VIDEO_FORMAT_MJPEG = 101;
    private static final Object LOADER_LOCK = new Object();
    private static volatile ClassLoader sharedVendorClassLoader;

    /**
     * 全局单例：整个进程只允许一个 SDK 实例连接热成像 USB 设备。
     * 之前多个 ThermalFragment（主页面 + 摄像头页后台采集）各自 new SDK，
     * 多个实例同时 USB_Init/USB_Login 抢占同一设备，导致全部连接失败。
     */
    private static volatile HikThermalSdkBridge sharedInstance;
    private static final Object INSTANCE_LOCK = new Object();

    public static HikThermalSdkBridge getInstance(Context context) {
        HikThermalSdkBridge instance = sharedInstance;
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                instance = sharedInstance;
                if (instance == null) {
                    instance = new HikThermalSdkBridge(context.getApplicationContext());
                    sharedInstance = instance;
                }
            }
        }
        return instance;
    }

    public interface Listener {
        void onStatus(String status);
        void onFrame(Bitmap bitmap);
        void onError(String error);
    }

    private final Context context;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private HandlerThread thread;
    private Handler worker;
    private Object api;
    // Native SDK does not retain a Java strong reference for the JNA callback.
    // Keep both objects alive for the entire stream lifetime or the stream can
    // start successfully while delivering no frames after local GC.
    private Object streamCallback;
    private Object streamCallbackParam;
    private volatile boolean firstFrameLogged;
    private int userId = -1;
    private int streamHandle = -1;
    private volatile boolean running;
    private String vendorNativeDir;

    private HikThermalSdkBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void addListener(Listener listener) {
        // 去重：同一监听器重复注册会导致同一帧被回调多次，
        // 其中一个节流路径曾回收 Bitmap 造成画面黑屏。
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return running;
    }

    private void notifyStatus(String status) {
        for (Listener listener : listeners) {
            try { listener.onStatus(status); } catch (Throwable ignored) { }
        }
    }

    private void notifyFrame(Bitmap bitmap) {
        for (Listener listener : listeners) {
            try { listener.onFrame(bitmap); } catch (Throwable ignored) { }
        }
    }

    private void notifyError(String error) {
        for (Listener listener : listeners) {
            try { listener.onError(error); } catch (Throwable ignored) { }
        }
    }

    /**
     * 幂等启动：SDK 已在运行（其他页面已启动后台采集）时直接忽略。
     * 整个进程只有这一个 SDK 实例，多个页面共用，互不抢占 USB 设备。
     */
    public synchronized void start() {
        if (running || thread != null) return;
        thread = new HandlerThread("HikThermalSdk");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::startInternal);
    }

    public void stop() {
        Handler h;
        synchronized (this) {
            h = worker;
        }
        if (h == null) return;
        CountDownLatch stopped = new CountDownLatch(1);
        h.post(() -> {
            try {
                stopInternal();
            } finally {
                stopped.countDown();
            }
        });
        try {
            if (!stopped.await(2500, TimeUnit.MILLISECONDS))
                Log.w(TAG, "SDK stop timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startInternal() {
        try {
            status("正在加载热成像SDK...");
            loadNativeLibraries();
            ClassLoader loader = createVendorClassLoader();
            Class<?> apiClass = loader.loadClass("com.hcusbsdk.Interface.JavaInterface");
            api = apiClass.getMethod("getInstance").invoke(null);
            if (!boolCall(api, "USB_Init")) throw sdkError("USB_Init失败");

            int count = (Integer) apiClass.getMethod("USB_GetDeviceCount", Context.class)
                    .invoke(api, context);
            if (count <= 0) throw sdkError("没有发现热成像USB设备");

            Class<?> deviceClass = loader.loadClass("com.hcusbsdk.Interface.USB_DEVICE_INFO");
            Object devices = Array.newInstance(deviceClass, Math.max(128, count));
            for (int i = 0; i < Array.getLength(devices); i++) {
                Array.set(devices, i, deviceClass.getConstructor().newInstance());
            }
            Method enumMethod = apiClass.getMethod("USB_EnumDevices", int.class, devices.getClass());
            if (!(Boolean) enumMethod.invoke(api, count, devices)) throw sdkError("USB_EnumDevices失败");

            Object selected = null;
            for (int i = 0; i < count; i++) {
                Object d = Array.get(devices, i);
                if (getInt(d, "dwVID") == VID_HIK && getInt(d, "dwPID") == PID_4117) {
                    selected = d;
                    break;
                }
            }
            if (selected == null) throw new IllegalStateException("未找到2bdf:0101设备");

            Object login = loader.loadClass("com.hcusbsdk.Interface.USB_USER_LOGIN_INFO")
                    .getConstructor().newInstance();
            set(login, "dwTimeout", 5000);
            set(login, "dwDevIndex", getInt(selected, "dwIndex"));
            set(login, "dwVID", getInt(selected, "dwVID"));
            set(login, "dwPID", getInt(selected, "dwPID"));
            set(login, "dwFd", getInt(selected, "dwFd"));
            set(login, "byLoginMode", (byte) 1);
            set(login, "szUserName", "admin");
            set(login, "szPassword", "12345");
            Object reg = loader.loadClass("com.hcusbsdk.Interface.USB_DEVICE_REG_RES")
                    .getConstructor().newInstance();
            userId = (Integer) apiClass.getMethod("USB_Login", login.getClass(), reg.getClass())
                    .invoke(api, login, reg);
            if (userId < 0) throw sdkError("USB_Login失败");

            Object video = loader.loadClass("com.hcusbsdk.Interface.USB_VIDEO_PARAM")
                    .getConstructor().newInstance();
            set(video, "dwVideoFormat", VIDEO_FORMAT_MJPEG);
            // 海康厂商 SDK 对该模组使用竖向参数 240×320 MJPEG。
            // Android Camera HAL 中显示的 320×240 不能直接套用于 USB SDK，
            // 否则 USB_StartStreamCallback 会以错误码 84 拒绝启动。
            set(video, "dwWidth", 240);
            set(video, "dwHeight", 320);
            set(video, "dwFramerate", 30);
            set(video, "dwBitrate", 0);
            set(video, "dwParamType", 0);
            set(video, "dwValue", 0);
            if (!(Boolean) apiClass.getMethod("USB_SetVideoParam", int.class, video.getClass())
                    .invoke(api, userId, video)) throw sdkError("设置MJPEG 240×320失败");

            Class<?> callbackClass = loader.loadClass("com.hcusbsdk.Interface.FStreamCallBack");
            streamCallback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName()) && args != null && args.length == 2) {
                            Object frame = args[1];
                            byte[] bytes = (byte[]) field(frame, "pBuf").get(frame);
                            int length = getInt(frame, "dwBufSize");
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0,
                                    Math.min(length, bytes.length));
                            if (bitmap != null) {
                                if (!firstFrameLogged) {
                                    firstFrameLogged = true;
                                    Log.i(TAG, "received first thermal frame, bytes=" + length
                                            + ", bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                                }
                                try {
                                    // 全局单例自行把最新帧写入帧管理器：即使没有页面在显示，
                                    // 摄像头页"同时拍照"/语音拍照也能随时取到热成像帧。
                                    ThermalFrameManager.getInstance().updateLatestFrame(bitmap);
                                } catch (Throwable ignored) { }
                                notifyFrame(bitmap);
                            }
                            return null;
                        }
                        if ("toString".equals(method.getName())) return "HikStreamCallback";
                        return null;
                    });
            streamCallbackParam = loader.loadClass("com.hcusbsdk.Interface.USB_STREAM_CALLBACK_PARAM")
                    .getConstructor().newInstance();
            set(streamCallbackParam, "dwStreamType", VIDEO_FORMAT_MJPEG);
            set(streamCallbackParam, "fnStreamCallBack", streamCallback);
            streamHandle = (Integer) apiClass.getMethod("USB_StartStreamCallback", int.class,
                    streamCallbackParam.getClass()).invoke(api, userId, streamCallbackParam);
            if (streamHandle < 0) throw sdkError("USB_StartStreamCallback失败");
            running = true;
            status("热成像正常 · MJPEG 240×320");
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            notifyError(rootMessage(t));
            stopInternal();
        }
    }

    private ClassLoader createVendorClassLoader() throws Exception {
        ClassLoader existing = sharedVendorClassLoader;
        if (existing != null) {
            Log.i(TAG, "reusing shared vendor ClassLoader");
            return existing;
        }
        synchronized (LOADER_LOCK) {
            existing = sharedVendorClassLoader;
            if (existing != null) return existing;

            File dex = new File(context.getCodeCacheDir(), "hik_thermal_runtime.jar");
            try (InputStream in = context.getAssets().open("hik_thermal_runtime.jar");
                 FileOutputStream out = new FileOutputStream(dex, false)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            String nativeDir = vendorNativeDir != null ? vendorNativeDir
                    : context.getApplicationInfo().nativeLibraryDir;
            existing = new DexClassLoader(dex.getAbsolutePath(),
                    context.getCodeCacheDir().getAbsolutePath(), nativeDir,
                    context.getClassLoader());
            sharedVendorClassLoader = existing;
            Log.i(TAG, "created shared vendor ClassLoader");
            return existing;
        }
    }

    private void loadNativeLibraries() {
        // JavaInterface is loaded by a DexClassLoader. Give JNA an explicit path because
        // its class loader cannot reliably resolve libraries packaged by the host APK.
        String nativeDir = resolveNativeDirectory();
        vendorNativeDir = nativeDir;
        System.setProperty("jna.library.path", nativeDir);
        System.setProperty("jna.boot.library.path", nativeDir);
        System.setProperty("jna.nounpack", "true");

        // Do not call System.load here. This bridge belongs to the app PathClassLoader,
        // while JavaInterface/JNA belongs to the vendor DexClassLoader. Android forbids
        // one .so from being associated with both class loaders. With legacy JNI
        // packaging enabled, the vendor class loader can load every library itself.
        verifyNative(nativeDir, "iconv");
        verifyNative(nativeDir, "usb1.0");
        verifyNative(nativeDir, "uvc");
        verifyNative(nativeDir, "cryptoPrivate");
        verifyNative(nativeDir, "sslPrivate");
        verifyNative(nativeDir, "jnidispatch");
        verifyNative(nativeDir, "HCUSBSDK");
        Log.i(TAG, "native libraries verified; vendor ClassLoader will load them");
    }

    /**
     * Prefer Android's extracted native directory. As a safety net, extract the libraries
     * from our own APK when a device/package installer keeps JNI libraries inside the APK.
     */
    private String resolveNativeDirectory() {
        File installed = new File(context.getApplicationInfo().nativeLibraryDir,
                "libHCUSBSDK.so");
        if (installed.isFile()) return installed.getParentFile().getAbsolutePath();

        File output = new File(context.getCodeCacheDir(), "hik_native");
        if (!output.exists() && !output.mkdirs()) {
            throw new IllegalStateException("无法创建热成像原生库目录：" + output);
        }
        String apk = context.getApplicationInfo().sourceDir;
        try (ZipFile zip = new ZipFile(apk)) {
            String selectedAbi = null;
            for (String abi : Build.SUPPORTED_ABIS) {
                if (zip.getEntry("lib/" + abi + "/libHCUSBSDK.so") != null) {
                    selectedAbi = abi;
                    break;
                }
            }
            if (selectedAbi == null) {
                throw new UnsatisfiedLinkError("APK中没有适合本机ABI的HCUSBSDK，本机ABI="
                        + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
            }
            String prefix = "lib/" + selectedAbi + "/";
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(prefix)
                        && entry.getName().endsWith(".so")) {
                    File target = new File(output, new File(entry.getName()).getName());
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(target, false)) {
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    }
                    target.setReadable(true, true);
                    target.setExecutable(true, true);
                }
            }
            Log.i(TAG, "native libraries ready, abi=" + selectedAbi + ", dir=" + output);
            return output.getAbsolutePath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("从APK释放热成像原生库失败", e);
        }
    }

    private static void verifyNative(String nativeDir, String name) {
        File library = new File(nativeDir, "lib" + name + ".so");
        if (!library.isFile()) {
            throw new UnsatisfiedLinkError("APK中缺少原生库：" + library.getAbsolutePath());
        }
        Log.i(TAG, "verified " + library.getName());
    }

    private void stopInternal() {
        try {
            if (api != null && userId >= 0 && streamHandle >= 0)
                api.getClass().getMethod("USB_StopChannel", int.class, int.class)
                        .invoke(api, userId, streamHandle);
        } catch (Throwable ignored) { }
        try {
            if (api != null && userId >= 0)
                api.getClass().getMethod("USB_Logout", int.class).invoke(api, userId);
        } catch (Throwable ignored) { }
        try {
            if (api != null) api.getClass().getMethod("USB_Cleanup").invoke(api);
        } catch (Throwable ignored) { }
        running = false;
        userId = -1;
        streamHandle = -1;
        firstFrameLogged = false;
        streamCallbackParam = null;
        streamCallback = null;
        api = null;
        synchronized (this) {
            if (thread != null) thread.quitSafely();
            thread = null;
            worker = null;
        }
    }

    private boolean boolCall(Object target, String name) throws Exception {
        return (Boolean) target.getClass().getMethod(name).invoke(target);
    }

    private IllegalStateException sdkError(String prefix) {
        try {
            int code = (Integer) api.getClass().getMethod("USB_GetLastError").invoke(api);
            return new IllegalStateException(prefix + "，错误码=" + code);
        } catch (Throwable ignored) {
            return new IllegalStateException(prefix);
        }
    }

    private void status(String message) {
        Log.i(TAG, message);
        notifyStatus(message);
    }

    private static Field field(Object object, String name) throws Exception {
        Field f = object.getClass().getField(name);
        f.setAccessible(true);
        return f;
    }

    private static int getInt(Object object, String name) throws Exception {
        return field(object, name).getInt(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        field(object, name).set(object, value);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
