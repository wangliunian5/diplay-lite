package com.shilapi.blackbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机拉起黑条服务。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startService(new Intent(context, BarService.class));
        }
    }
}
