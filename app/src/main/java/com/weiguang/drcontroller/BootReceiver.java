package com.weiguang.drcontroller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * RK3566开机完成后的备用启动入口。
 *
 * 生产设备建议同时把MainActivity设置为默认HOME应用；这样系统启动桌面时会
 * 直接进入本软件。该Receiver用于固件仍保留原Launcher时的备用自动启动。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        context.startActivity(launchIntent);
    }
}
