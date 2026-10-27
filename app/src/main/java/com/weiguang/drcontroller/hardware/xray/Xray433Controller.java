package com.weiguang.drcontroller.hardware.xray;

import android.content.Context;

/**
 * X-Ray 433 MHz 双通道安全控制器。
 *
 * <p>页面和语音只依赖本类；实物到位后，只需把 {@link Transport}
 * 替换为 GPIO/433 MHz 发送实现。未接入硬件时运行在演练模式，
 * 会完整校验顺序，但不对外发射任何无线命令。</p>
 */
public final class Xray433Controller {
    public enum State { IDLE, CURRENT_ON, VOLTAGE_ON, FAULT }
    public enum Channel { TUBE_CURRENT, TUBE_VOLTAGE }

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
    private State state = State.IDLE;
    private String lastMessage = "433 MHz 硬件待接入，当前为安全演练模式";

    private Xray433Controller(Context context) {
        transport = new PreviewTransport();
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

    /** 第一步：只允许先接通管电流通道。 */
    public synchronized SendResult enableTubeCurrent() {
        if (state == State.CURRENT_ON || state == State.VOLTAGE_ON) {
            return result(true, "管电流已接通");
        }
        SendResult sent = transport.send(Channel.TUBE_CURRENT, true);
        if (!sent.success) return fault("管电流接通失败：" + sent.message);
        state = State.CURRENT_ON;
        return result(true, sent.simulated ? "演练：管电流已接通" : "管电流已接通");
    }

    /** 第二步：只有管电流已成功时才允许接通管电压。 */
    public synchronized SendResult enableTubeVoltage() {
        if (state == State.VOLTAGE_ON) return result(true, "管电压已接通");
        if (state != State.CURRENT_ON) {
            return result(false, "已拦截：必须先成功接通管电流，才能接通管电压");
        }
        SendResult sent = transport.send(Channel.TUBE_VOLTAGE, true);
        if (!sent.success) {
            transport.send(Channel.TUBE_CURRENT, false);
            state = State.FAULT;
            return fault("管电压接通失败，已尝试断开管电流：" + sent.message);
        }
        state = State.VOLTAGE_ON;
        return result(true, sent.simulated ? "演练：管电压已接通" : "管电压已接通");
    }

    /** 一键流程：严格执行“管电流 -> 管电压”。 */
    public synchronized SendResult startExposure() {
        SendResult current = enableTubeCurrent();
        if (!current.success) return current;
        SendResult voltage = enableTubeVoltage();
        if (!voltage.success) return voltage;
        return result(true, transport.isHardwareReady()
                ? "已按顺序接通管电流和管电压，射线源已开启"
                : "演练完成：已按顺序执行管电流、管电压；未发射 433 MHz 命令");
    }

    /** 停止时反向断开：先管电压，后管电流。 */
    public synchronized SendResult stopAll() {
        boolean ok = true;
        if (state == State.VOLTAGE_ON || state == State.FAULT) {
            ok = transport.send(Channel.TUBE_VOLTAGE, false).success;
        }
        if (state != State.IDLE) {
            ok = transport.send(Channel.TUBE_CURRENT, false).success && ok;
        }
        state = ok ? State.IDLE : State.FAULT;
        return result(ok, ok
                ? (transport.isHardwareReady() ? "已先断开管电压，再断开管电流" : "演练：已按安全顺序停止")
                : "停止命令未全部成功，请立即检查设备");
    }

    private SendResult fault(String message) {
        state = State.FAULT;
        return result(false, message);
    }

    private SendResult result(boolean success, String message) {
        lastMessage = message;
        return new SendResult(success, !transport.isHardwareReady(), message);
    }

    /** 预接入实现：永不发射，只用于联调 UI、语音和顺序保护。 */
    private static final class PreviewTransport implements Transport {
        @Override public boolean isHardwareReady() { return false; }
        @Override public SendResult send(Channel channel, boolean turnOn) {
            return new SendResult(true, true, "preview-only");
        }
    }
}
