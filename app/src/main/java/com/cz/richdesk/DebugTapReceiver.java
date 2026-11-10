package com.cz.richdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 调试用注入入口（不参与正常交互）：
 *
 *   adb shell am broadcast -a com.cz.richdesk.DEBUG_TAP \
 *       --ei d 63 --ei x 588 --ei y 400 [--ei x2 588 --ei y2 200 --ei steps 12]
 *
 * 作用：在没有手指、且这台机器禁止 adb shell 注入的情况下，
 * 也能把触摸事件直接送进某个 display（0 = 主屏，虚拟屏 id 看日志），
 * 用来验证「触摸转发链路」到底通不通。
 */
public class DebugTapReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.cz.richdesk.DEBUG_TAP";
    /** 直接驱动界面（翻页 / 调换 / 关闭 / 全屏），用于没有手指时的自测 */
    public static final String ACTION_UI = "com.cz.richdesk.DEBUG_UI";

    @Override
    public void onReceive(Context context, Intent i) {
        if (i == null) return;
        boolean tap = ACTION.equals(i.getAction());
        boolean ui = ACTION_UI.equals(i.getAction());
        if (!tap && !ui) return;
        // 只接受 shell/adb / 系统 / 自己人发来的：否则任何应用都能借本进程的
        // system uid 往任意屏幕注入点击，是实打实的提权漏洞。
        int uid = android.os.Binder.getCallingUid();
        if (uid != 0 && uid != android.os.Process.SYSTEM_UID
                && uid != 2000 && uid != context.getApplicationInfo().uid) {
            android.util.Log.w("PipDesk", "DEBUG 事件拒绝来自 uid=" + uid);
            return;
        }
        if (ui) {
            MainActivity a = MainActivity.instance;
            if (a == null) return;
            int page = i.getIntExtra("page", -1);
            if (page >= 0) a.debugPage(page);
            int swap = i.getIntExtra("swap", -1);
            if (swap >= 0) a.debugSwap(swap, i.getIntExtra("dir", -1));
            int close = i.getIntExtra("close", -1);
            if (close >= 0) a.debugClose(close);
            String full = i.getStringExtra("full");
            if (full != null) a.debugFullScreen(full);
            int menu = i.getIntExtra("menu", -1);
            if (menu >= 0) a.debugShowMenu(menu);
            String openPkg = i.getStringExtra("open");
            if (openPkg != null) a.debugOpen(openPkg, i.getIntExtra("pane", 0));
            int restart = i.getIntExtra("restart", -1);
            if (restart >= 0) a.debugRestart(restart);
            // 分屏比例：--ei preset 0(等宽)/1(1:2)/2(2:1)/3(恢复全部)
            int preset = i.getIntExtra("preset", -1);
            if (preset >= 0) a.debugPreset(preset);
            // 模拟单击空白画中画：--ei emptytap <paneIndex>
            int emptyTap = i.getIntExtra("emptytap", -1);
            if (emptyTap >= 0) a.debugTapEmpty(emptyTap);
            if (i.getIntExtra("ratios", 0) == 1) a.debugRatios();
            // 画中画屏数：--ei panes 2
            int panes = i.getIntExtra("panes", -1);
            if (panes > 0) a.debugPaneCount(panes);
            // 模拟拖动玻璃分隔条：--ei drag <第几条> --ei dx <位移像素>
            int drag = i.getIntExtra("drag", -1);
            if (drag >= 0) a.debugDrag(drag, i.getIntExtra("dx", 0));
            // 模拟轻点玻璃分隔条（交换两侧画中画）：--ei tap <第几条>
            int tapHandle = i.getIntExtra("tap", -1);
            if (tapHandle >= 0) a.debugTapHandle(tapHandle);
            // 执行内置快捷方式：--es builtin pipdesk://nav-back
            String builtin = i.getStringExtra("builtin");
            if (builtin != null) a.debugBuiltin(builtin);
            // 注入一个按键：--ei key 26（26=电源/224=唤醒/4=返回/3=主页）
            int key = i.getIntExtra("key", -1);
            if (key >= 0) a.debugKey(key);
            // 息屏参数（无手指自测用）：--ei sleepmode 0|1  --ei sleepguard <秒>
            if (i.hasExtra("sleepmode")) {
                Prefs.setSleepMode(context, i.getIntExtra("sleepmode", 0));
                android.util.Log.i("PipDesk", "DEBUG 息屏方式=" + Prefs.getSleepMode(context));
            }
            if (i.hasExtra("sleepguard")) {
                Prefs.setSleepGuardSec(context, i.getIntExtra("sleepguard", 180));
                android.util.Log.i("PipDesk", "DEBUG 兜底唤醒=" + Prefs.getSleepGuardSec(context) + "s");
            }
            // 亮度自测：--ei brightauto 1 --ei brightsun 1 --ei brightweather 0
            //          --ei brightday 200 --ei brightnight 60 --ei brightcloudy 140
            if (i.hasExtra("brightauto")) {
                boolean on = i.getIntExtra("brightauto", 0) == 1;
                Prefs.setBrightAuto(context, on);
                if (on) Brightness.applyIfAuto(context); else Brightness.restore(context);
            }
            if (i.hasExtra("brightsun")) {
                Prefs.setBrightUseSun(context, i.getIntExtra("brightsun", 1) == 1);
            }
            if (i.hasExtra("brightweather")) {
                Prefs.setBrightUseWeather(context, i.getIntExtra("brightweather", 1) == 1);
            }
            if (i.hasExtra("brightday")) Prefs.setBrightDay(context, i.getIntExtra("brightday", 200));
            if (i.hasExtra("brightnight")) Prefs.setBrightNight(context, i.getIntExtra("brightnight", 60));
            if (i.hasExtra("brightcloudy")) Prefs.setBrightCloudy(context, i.getIntExtra("brightcloudy", 140));
            if (i.getIntExtra("brightapply", 0) == 1) {
                Brightness.applyIfAuto(context);
                android.util.Log.i("PipDesk", "DEBUG " + Brightness.describe(context)
                        + "；天气 " + Weather.dockLabel(context) + "（" + Weather.ageLabel(context) + "）");
            }
            if (i.getIntExtra("brightrestore", 0) == 1) {
                Brightness.restore(context);
                android.util.Log.i("PipDesk", "DEBUG 亮度控制权已还原");
            }
            if (i.getIntExtra("weather", 0) == 1) {
                Weather.forceRefresh(context, null);
                android.util.Log.i("PipDesk", "DEBUG 手动刷新天气");
            }
            // 模拟长按 Dock 第 pos 格：--ei docklong <pos>（触发 onDockLongClick）
            int dockLong = i.getIntExtra("docklong", -1);
            if (dockLong >= 0) a.debugDockLongClick(dockLong);
            if (i.getIntExtra("pick", 0) == 1) a.debugShowPicker();
            if (i.getIntExtra("wpdebug", 0) == 1) a.debugWallTint();
            return;
        }
        int d = i.getIntExtra("d", 0);
        int x = i.getIntExtra("x", -1);
        int y = i.getIntExtra("y", -1);
        if (x < 0 || y < 0) return;
        int x2 = i.getIntExtra("x2", x);
        int y2 = i.getIntExtra("y2", y);
        int steps = i.getIntExtra("steps", 1);
        TouchBridge.get(context).debugTap(d, x, y, x2, y2, steps,
                i.getIntExtra("hold", 0));
    }
}
