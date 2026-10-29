package com.weiguang.drcontroller.hardware.xray;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.TimeUnit;

/**
 * X-Ray 433 MHz 双通道安全控制器。
 *
 * <p>K11C 使用 30Pin 扩展口的 GPIO3_D2/D3 驱动 TXS01 的 D2/D3。
 * TXS01 为低电平触发，因此空闲态必须保持高电平。</p>
 */
public final class Xray433Controller {
    public enum State { MASTER_OFF, MASTER_ON, EXPOSING, FAULT }
    public enum Channel { MASTER_POWER, TUBE_VOLTAGE_MOMENTARY }

    public interface Transport {
        boolean isHardwareReady();
        SendResult send(Channel channel, boolean turnOn);
    }

    public static final class SendResult {
        public final boolean success;
        public final boolean simulated;
        public final String message;

        public SendResult(boolean success, boolean simulated, String message) {
            this.success = success;
            this.simulated = simulated;
            this.message = message;
        }
    }

    private static volatile Xray433Controller instance;
    private final Transport transport;
    private final Handler safetyHandler = new Handler(Looper.getMainLooper());
    private final Runnable timedRelease = this::releaseTubeVoltage;
    private State state = State.MASTER_OFF;
    private String lastMessage = "433 MHz 硬件待接入，当前为安全演练模式";

    private Xray433Controller(Context context) {
        GpioTransport gpio = new GpioTransport();
        transport = gpio.isHardwareReady() ? gpio : new PreviewTransport(gpio.getFailureReason());
        if (transport.isHardwareReady()) {
            lastMessage = "433 MHz GPIO 已就绪；D2/D3 当前保持高电平";
        }
    }

    public static Xray433Controller getInstance(Context context) {
        if (instance == null) {
            synchronized (Xray433Controller.class) {
                if (instance == null) instance = new Xray433Controller(context.getApplicationContext());
            }
        }
        return instance;
    }

    public synchronized State getState() { return state; }
    public synchronized String getLastMessage() { return lastMessage; }
    public boolean isHardwareReady() { return transport.isHardwareReady(); }

    /** 保持型总开关：开启后，射线源本机自动建立管电流。 */
    public synchronized SendResult turnMasterOn() {
        if (state == State.MASTER_ON || state == State.EXPOSING) {
            return result(true, "总开关已接通，管电流由设备自动建立");
        }
        SendResult sent = transport.send(Channel.MASTER_POWER, true);
        if (!sent.success) return fault("总开关接通失败：" + sent.message);
        state = State.MASTER_ON;
        return result(true, sent.simulated ? "演练：总开关已开，管电流自动建立" : "总开关已开，管电流已自动建立");
    }

    /** 按下点动管电压：只允许在总开关已开时执行。 */
    public synchronized SendResult pressTubeVoltage() {
        if (state == State.EXPOSING) return result(true, "管电压点动已按下，正在曝光");
        if (state != State.MASTER_ON) {
            return result(false, "已拦截：请先开启总开关，等待管电流建立后再按住管电压");
        }
        SendResult sent = transport.send(Channel.TUBE_VOLTAGE_MOMENTARY, true);
        if (!sent.success) return fault("管电压点动按下失败：" + sent.message);
        state = State.EXPOSING;
        return result(true, sent.simulated ? "演练：管电压已按下" : "管电压已按下，正在曝光");
    }

    /** 松开点动管电压：立即结束曝光，总开关保持开启。 */
    public synchronized SendResult releaseTubeVoltage() {
        safetyHandler.removeCallbacks(timedRelease);
        if (state != State.EXPOSING) return result(true, "管电压已松开");
        SendResult sent = transport.send(Channel.TUBE_VOLTAGE_MOMENTARY, false);
        if (!sent.success) return fault("管电压松开命令失败，请立即手动断电");
        state = State.MASTER_ON;
        return result(true, sent.simulated ? "演练：管电压已松开，曝光结束" : "管电压已松开，曝光结束");
    }

    /** 语音远程模式：点动指定时长并由独立安全计时器自动松开。 */
    public synchronized SendResult startTimedExposure(int seconds) {
        if (seconds < 1 || seconds > 10) return result(false, "曝光时长必须在 1 到 10 秒之间");
        SendResult master = turnMasterOn();
        if (!master.success) return master;
        SendResult press = pressTubeVoltage();
        if (!press.success) return press;
        safetyHandler.removeCallbacks(timedRelease);
        safetyHandler.postDelayed(timedRelease, seconds * 1000L);
        return result(true, (transport.isHardwareReady() ? "" : "演练：") + "已开启 " + seconds + " 秒定时曝光，到时自动松开管电压");
    }

    /** 关闭总开关前始终先松开管电压。 */
    public synchronized SendResult turnMasterOff() {
        safetyHandler.removeCallbacks(timedRelease);
        if (state == State.EXPOSING) {
            SendResult release = releaseTubeVoltage();
            if (!release.success) return release;
        }
        if (state == State.MASTER_OFF) return result(true, "总开关已关闭");
        SendResult sent = transport.send(Channel.MASTER_POWER, false);
        state = sent.success ? State.MASTER_OFF : State.FAULT;
        return result(sent.success, sent.success
                ? (sent.simulated ? "演练：总开关已关闭" : "总开关已关闭")
                : "总开关关闭命令失败，请立即手动断电");
    }

    private SendResult fault(String message) {
        state = State.FAULT;
        return result(false, message);
    }

    private SendResult result(boolean success, String message) {
        lastMessage = message;
        return new SendResult(success, !transport.isHardwareReady(), message);
    }

    /**
     * KICKPI K11C 板载 GPIO LED class 控制节点。
     * GPIO3_D2 = 30Pin 物理 5 号脚，GPIO3_D3 = 物理 7 号脚。
     */
    private static final class GpioTransport implements Transport {
        private static final String MASTER = "/sys/class/leds/gpio3d2/brightness";
        private static final String VOLTAGE = "/sys/class/leds/gpio3d3/brightness";
        private static final String HIGH = "255";
        private static final String LOW = "0";
        private static final long COMMAND_TIMEOUT_MS = 2500L;
        private boolean ready;
        private String failureReason = "GPIO 初始化失败";

        GpioTransport() {
            // 模块未连接时也先完成这一步：低电平触发设备的安全空闲态是高电平。
            ready = runRoot("echo " + HIGH + " > " + MASTER + "; echo " + HIGH + " > " + VOLTAGE)
                    && HIGH.equals(readValue(MASTER)) && HIGH.equals(readValue(VOLTAGE));
            if (!ready) failureReason = "无法访问 K11C GPIO3_D2/D3，已回退到安全演练模式";
        }

        String getFailureReason() { return failureReason; }

        @Override public boolean isHardwareReady() { return ready; }

        @Override public synchronized SendResult send(Channel channel, boolean turnOn) {
            if (!ready) return new SendResult(false, true, failureReason);
            if (channel == Channel.MASTER_POWER) {
                // 总开关接收器为自锁模式，开和关都是一次短按脉冲。
                boolean ok = runRoot("echo " + LOW + " > " + MASTER
                        + "; sleep 0.2; echo " + HIGH + " > " + MASTER);
                if (!ok) return failSafe("总开关 GPIO 脉冲失败");
                return new SendResult(true, false, turnOn ? "master-on-pulse" : "master-off-pulse");
            }

            if (turnOn) {
                // 先安排独立的 10 秒硬件节点复位，再拉低。即使 App 异常退出也会自动松开。
                boolean ok = runRoot("(sleep 10; echo " + HIGH + " > " + VOLTAGE
                        + ") >/dev/null 2>&1 & echo " + LOW + " > " + VOLTAGE);
                if (!ok) return failSafe("管电压 GPIO 按下失败");
                return new SendResult(true, false, "voltage-pressed");
            }
            boolean ok = runRoot("echo " + HIGH + " > " + VOLTAGE);
            if (!ok) return failSafe("管电压 GPIO 松开失败");
            return new SendResult(true, false, "voltage-released");
        }

        private SendResult failSafe(String message) {
            runRoot("echo " + HIGH + " > " + VOLTAGE + "; echo " + HIGH + " > " + MASTER);
            ready = false;
            failureReason = message;
            return new SendResult(false, false, message);
        }

        private static boolean runRoot(String command) {
            Process process = null;
            try {
                process = new ProcessBuilder("/system/xbin/su", "0", "sh", "-c", command)
                        .redirectErrorStream(true).start();
                boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (Exception ignored) {
                if (process != null) process.destroyForcibly();
                return false;
            }
        }

        private static String readValue(String path) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                return reader.readLine().trim();
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    /** 无法取得 GPIO 权限时永不发射，只用于联调 UI、语音和顺序保护。 */
    private static final class PreviewTransport implements Transport {
        private final String reason;
        PreviewTransport(String reason) { this.reason = reason; }
        @Override public boolean isHardwareReady() { return false; }
        @Override public SendResult send(Channel channel, boolean turnOn) {
            return new SendResult(true, true, reason);
        }
    }
}
