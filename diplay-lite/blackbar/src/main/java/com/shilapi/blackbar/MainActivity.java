package com.shilapi.blackbar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** 空入口：仅用于首次拉起解除 stopped-state + 手动测试开关。 */
public class MainActivity extends Activity {
    private static boolean toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 单数启动=显示黑条，双数启动=移除（手动调试用）
        Intent i = new Intent(this, BarService.class);
        if (toggle) i.setAction("hide");
        toggle = !toggle;
        startService(i);
        finish();
    }
}
