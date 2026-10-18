package com.shilapi.blackbar;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 常驻底部黑条：盖住 T3 触摸死区（底部约 88-100px）。
 * TYPE_PHONE + NOT_FOCUSABLE：普通 SYSTEM_ALERT_WINDOW 权限即可（KK 无运行时授权），
 * NOT_TOUCHABLE 让事件穿透（死区硬件本来无事件，双保险不挡误触）。
 * 屏幕逻辑高 500（overscan 裁剪后），黑条贴逻辑底边=盖住 launcher 空壁纸带。
 */
public class BarService extends Service {
    private View bar;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "hide".equals(intent.getAction())) {
            if (bar != null) {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                try {
                    wm.removeView(bar);
                } catch (RuntimeException ignored) {
                }
                bar = null;
            }
            return START_NOT_STICKY;
        }
        applyBootFix();
        showBar();
        return START_STICKY;
    }

    /**
     * 三件套后台重放（幂等）：overscan 裁底部 100 / density 等比补偿 / 触摸芯片 Y=500。
     * 开机后一次 su 授权（勾选记住）后永久免提示。失败静默——脚本/手动兜底仍在。
     */
    private void applyBootFix() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[][] cmds = {
                        {"wm", "overscan", "0,0,0,100"},
                        {"wm", "density", "133"},
                        {"/data/local/tmp/tpi2c", "write", "/data/local/tmp/y500.bin"},
                };
                for (String[] argv : cmds) {
                    try {
                        Process p = new ProcessBuilder("su", "-c", join(argv)).start();
                        p.waitFor();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "blackbar-bootfix").start();
    }

    private static String join(String[] argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
        }
        return sb.toString();
    }

    private synchronized void showBar() {
        if (bar != null) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 100,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // overscan 裁掉的 y500-600 区域会露出壁纸越界（KK 壁纸窗口不裁），
        // 黑条贴逻辑底边再下探 100px，正好盖住物理屏幕最底部
        lp.gravity = Gravity.BOTTOM | Gravity.FILL_HORIZONTAL;
        lp.y = -100;
        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        try {
            wm.addView(v, lp);
            bar = v;
        } catch (RuntimeException ignored) {
            // 无 SYSTEM_ALERT_WINDOW（异常 ROM）时静默放弃：overscan 黑边仍在，仅少一层遮蔽
        }
    }

    @Override
    public void onDestroy() {
        if (bar != null) {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            try {
                wm.removeView(bar);
            } catch (RuntimeException ignored) {
            }
            bar = null;
        }
        super.onDestroy();
    }
}
