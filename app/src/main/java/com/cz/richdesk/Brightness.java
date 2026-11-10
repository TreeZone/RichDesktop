package com.cz.richdesk;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 按「日出日落 + 天气」自动调屏幕亮度。
 *
 * 取值顺序（越靠前越优先）：
 *   1) 日落后（勾了「跟随日出日落」）→ 夜间亮度
 *   2) 阴/雾/雨雪（勾了「跟随天气」）→ 阴天亮度
 *   3) 其余                          → 白天亮度
 *
 * 夜间为什么排天气前面：晚上不管阴不阴都得暗，否则一阴天反而更亮，很刺眼。
 *
 * 两个子开关互相独立（日出日落 / 天气），总开关关掉时这里什么都不做——
 * 有些车自带自动背光，用户不希望被抢。
 *
 * 【车机兼容】很多车机（比亚迪 / 吉利 / 大众系）根本不认 Settings.System 的
 * SCREEN_BRIGHTNESS：背光在 MCU 或厂商服务里，framework 写进去只是改了个数字，
 * 屏幕一点不变。所以这里一次写三条通道，谁认谁生效：
 *   1) 系统设置 SCREEN_BRIGHTNESS —— 标准 Android 路径，按 ROM 的上限换算
 *      （上限可能是 255 / 1023 / 100，硬写 0~255 会被截成最亮或最暗）
 *   2) SurfaceControl.setDisplayBrightness —— 直接压到显示层（HWC），
 *      绕过 framework 的亮度策略，很多车机这条管用
 *   3) /sys/class/backlight/xxx/brightness —— 直接写背光节点，需要 root；
 *      车机若是原生 Linux 背光驱动，只有这条管用
 * 三条互不冲突（都是"把亮度设成同一个百分比"），失败只记日志不抛异常。
 */
public class Brightness {

    private static final String TAG = "PipDesk";

    private Brightness() { }

    /** 当前应该用的亮度（0~255） */
    public static int target(Context c) {
        if (Prefs.brightUseSun(c) && SunTimes.isDarkNow(c, true)) {
            return Prefs.getBrightNight(c);
        }
        if (Prefs.brightUseWeather(c) && Weather.isCloudy(Prefs.getWeatherCode(c))) {
            return Prefs.getBrightCloudy(c);
        }
        return Prefs.getBrightDay(c);
    }

    /** 当前命中的是哪一档（日志/设置页文案用） */
    public static String reason(Context c) {
        if (Prefs.brightUseSun(c) && SunTimes.isDarkNow(c, true)) return "夜间";
        if (Prefs.brightUseWeather(c) && Weather.isCloudy(Prefs.getWeatherCode(c))) return "阴天";
        return "白天";
    }

    /** 亮度档位文案（设置页/日志用） */
    public static String describe(Context c) {
        if (!Prefs.brightAuto(c)) return "已关闭（不接管亮度）";
        return "现在按「" + reason(c) + "」用 " + target(c) + " / 255"
                + "；白天 " + Prefs.getBrightDay(c)
                + " / 夜间 " + Prefs.getBrightNight(c)
                + " / 阴天 " + Prefs.getBrightCloudy(c)
                + "\n上次写入：" + lastReport;
    }

    /**
     * 自动模式开着才生效。调用方：桌面 onResume、15s 定时器、设置页改完立即预览。
     *
     * 主线程只做一次配置判断就返回：目标值计算 + Settings.System 读写 +
     * SurfaceControl 反射调用全部丢到单线程后台。这几步都是同步 binder，
     * 弱 CPU 车机上一次能占掉十几毫秒，正好落在 15s 定时器的那一帧上。
     * 写入时序与通道行为完全不变（仍是每 15s 重压一次三条通道）。
     */
    public static void applyIfAuto(Context c) {
        if (c == null || !Prefs.brightAuto(c)) return;
        final Context app = c.getApplicationContext();
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            bg().post(() -> apply(app, target(app)));
        } else {
            apply(app, target(app));
        }
    }

    public static void apply(Context c, int value) {
        if (c == null) return;
        int v = Math.max(Prefs.BRIGHT_MIN, Math.min(Prefs.BRIGHT_MAX, value));

        // 最低亮度下限：TS10 一类车机会把过低的亮度直接当「关背光」，一下子全黑。
        // 所有通道在写入前都抬到这个百分比之上（0 = 不设下限）。
        float p = Math.max(Prefs.getBrightFloorPct(c) / 100f, v / 255f);

        int max = settingMax();
        int scaled = Math.max(1, Math.round(p * max));

        // 1) 系统设置通道（可在设置里单独关闭，做排除测试）
        boolean setOk = false;
        if (Prefs.brightChSetting(c)) setOk = writeSetting(c, scaled);

        // 2) 显示层通道（HWC），每档都压一次：系统亮度策略随时会覆盖回来
        boolean surfOk = false;
        if (Prefs.brightChSurface(c)) surfOk = setDisplayBrightness(p);

        // 3) 背光节点通道（只有数值变化时才动，避免每 15 秒灌一次 shell）
        boolean sysfsTried = false;
        if (Prefs.brightChSysfs(c) && v != lastSysfsValue) {
            lastSysfsValue = v;
            sysfsTried = true;
            writeBacklightDebounced(p);
        }

        String key = reason(c) + "/" + v;
        if (!key.equals(lastLogged)) {
            lastLogged = key;
            lastReport = "设置=" + (Prefs.brightChSetting(c) ? (setOk ? "ok" : "失败") : "已关")
                    + " 显示层=" + (Prefs.brightChSurface(c) ? (surfOk ? "ok" : "-") : "已关")
                    + " 背光节点=" + (Prefs.brightChSysfs(c) ? (sysfsTried ? "已下发" : "未变") : "已关")
                    + "（上限 " + max + "，写 " + scaled + "，下限 "
                    + Prefs.getBrightFloorPct(c) + "%）";
            Log.i(TAG, "亮度 → " + v + "/255（" + reason(c) + "，max=" + max
                    + "，设置=" + setOk + "，显示层=" + surfOk
                    + "，背光=" + (sysfsTried ? "下发" : "值未变")
                    + "，日落后=" + SunTimes.isDarkNow(c, Prefs.brightUseSun(c))
                    + "，天气=" + Weather.text(Prefs.getWeatherCode(c)) + "）");
        }
    }

    // ------------------------------------------------- 通道单测（设置页「通道测试」用）

    /** 只走系统设置通道写一个百分比（不受通道开关影响） */
    public static void testSetting(Context c, float p) {
        int scaled = Math.max(1, Math.round(p * settingMax()));
        boolean ok = writeSetting(c, scaled);
        Log.i(TAG, "通道测试①系统设置：" + Math.round(p * 100) + "% -> " + scaled + " ok=" + ok);
    }

    /** 只走显示层通道（HWC）写一个百分比 */
    public static void testSurface(Context c, float p) {
        boolean ok = setDisplayBrightness(p);
        Log.i(TAG, "通道测试②显示层：" + Math.round(p * 100) + "% ok=" + ok);
    }

    /** 只走背光节点通道写一个百分比 */
    public static void testSysfs(Context c, int pct) {
        lastSysfsValue = -1;    // 强制下次 apply 真正下发
        writeBacklightPct(pct);
        Log.i(TAG, "通道测试③背光节点：" + pct + "%");
    }

    // ------------------------------------------------------------ 通道 1：系统设置

    /** 系统亮度的真正上限：有的 ROM 是 1023 / 4095，硬按 255 写会永远最亮 */
    private static int settingMax() {
        if (maxCache > 0) return maxCache;
        int m = 0;
        try {
            Resources r = Resources.getSystem();
            int id = r.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
            if (id != 0) m = r.getInteger(id);
        } catch (Throwable ignored) { }
        if (m <= 0) m = 255;
        maxCache = m;
        return m;
    }

    private static boolean writeSetting(Context c, int scaled) {
        try {
            ContentResolver cr = c.getContentResolver();
            // 第一次接管前记下原本的状态，关掉总开关时好还原
            if (Prefs.getBrightSavedMode(c) < 0) {
                int mode = Settings.System.getInt(cr,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
                Prefs.setBrightSaved(c, mode, cur);
                Log.i(TAG, "接管亮度前记录：mode=" + mode + " value=" + cur);
            }
            if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            }
            int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
            if (cur == scaled) return true;
            return Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, scaled);
        } catch (Throwable t) {
            Log.w(TAG, "亮度写入失败（缺 WRITE_SETTINGS？）：" + t.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------ 通道 2：显示层

    private static Method mSetBrightness;
    private static Object displayToken;

    /** SurfaceControl.setDisplayBrightness(token, 0~1)：绕过 framework 的亮度策略
     *  synchronized：反射结果由后台线程和「通道测试」主线程共用，避免并发初始化 */
    private static synchronized boolean setDisplayBrightness(float p) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            if (displayToken == null) {
                Method g = sc.getDeclaredMethod("getInternalDisplayToken");
                g.setAccessible(true);
                displayToken = g.invoke(null);
            }
            if (displayToken == null) return false;
            if (mSetBrightness == null) {
                // Android 9~13：setDisplayBrightness(IBinder, float)
                Method m;
                try {
                    m = sc.getDeclaredMethod("setDisplayBrightness", IBinder.class, float.class);
                } catch (NoSuchMethodException e) {
                    // Android 14+：setDisplayBrightness(IBinder, float, float, String)
                    m = sc.getDeclaredMethod("setDisplayBrightness", IBinder.class,
                            float.class, float.class, String.class);
                }
                m.setAccessible(true);
                mSetBrightness = m;
            }
            if (mSetBrightness.getParameterCount() == 2) {
                mSetBrightness.invoke(null, displayToken, p);
            } else {
                mSetBrightness.invoke(null, displayToken, p, -1f, "richdesk");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 通道 3：背光节点

    /** 直写背光节点：没有 root 时写不进去 → 脚本 exit 7，Shell 会自动改用 su 重试 */
    private static void writeBacklightPct(int pct) {
        pct = Math.max(1, Math.min(100, pct));
        // 每个节点按自己的 max_brightness 换算；写成功一个就算成功
        String cmd = "ok=0; "
                + "for f in /sys/class/backlight/*/brightness "
                + "/sys/class/leds/lcd-backlight/brightness "
                + "/sys/class/leds/lcd_backlight/brightness; do "
                + "[ -e \"$f\" ] || continue; d=${f%/*}; "
                + "mx=$(cat $d/max_brightness 2>/dev/null); "
                + "[ -n \"$mx\" ] || mx=255; "
                + "if echo $(( mx * " + pct + " / 100 )) > \"$f\" 2>/dev/null; then ok=1; fi; "
                + "done; [ $ok -eq 1 ] || exit 7";
        Shell.run(cmd);
    }

    /** 拖滑条时会连着调 apply：背光那条（shell + 可能的 su）做 350ms 防抖，只写最后一帧 */
    private static void writeBacklightDebounced(float p) {
        final int pct = Math.max(1, Math.min(100, Math.round(p * 100f)));
        if (pendingBacklight != null) H.removeCallbacks(pendingBacklight);
        pendingBacklight = () -> {
            pendingBacklight = null;
            writeBacklightPct(pct);
        };
        H.postDelayed(pendingBacklight, 350);
    }

    /** 车机亮度到底走没走通：设置页「现在为什么是这个亮度？」里展示 */
    public static String diagnose(Context c) {
        String s;
        try {
            s = "系统亮度=" + Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1)
                    + " / 上限 " + settingMax()
                    + "，模式=" + Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
        } catch (Throwable t) {
            s = "读不到系统亮度（" + t.getClass().getSimpleName() + "）";
        }
        return s + "\n" + (lastReport.length() == 0 ? "还没写过亮度" : "上次：" + lastReport);
    }

    /** 车机亮度不生效时用：把需要的信息一次列出来（设置页弹窗 + 落盘日志） */
    public static String diagnoseReport(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            ContentResolver cr = c.getContentResolver();
            b.append("设置值=").append(Settings.System.getInt(cr,
                            Settings.System.SCREEN_BRIGHTNESS, -1))
                    .append(" / 上限=").append(settingMax())
                    .append(" / 模式=").append(Settings.System.getInt(cr,
                            Settings.System.SCREEN_BRIGHTNESS_MODE, -1))
                    .append('\n');
        } catch (Throwable t) {
            b.append("读设置失败：").append(t.getClass().getSimpleName()).append('\n');
        }
        b.append("上次写入：").append(lastReport.length() == 0 ? "（还没写过）" : lastReport)
                .append('\n');
        b.append("---- 设备 ----\n");
        b.append(Shell.capture(
                "echo brand=$(getprop ro.product.brand) model=$(getprop ro.product.model)"
                        + " sdk=$(getprop ro.build.version.sdk);"
                        + "echo \"size: $(wm size | tr '\\n' ' ')\";"
                        + "echo \"density: $(wm density | tr '\\n' ' ')\";"
                        + "for f in /sys/class/backlight/*/brightness"
                        + " /sys/class/leds/*backlight*/brightness; do "
                        + "[ -e \"$f\" ] || continue; d=${f%/*}; "
                        + "echo \"node $f max=$(cat $d/max_brightness 2>/dev/null)"
                        + " cur=$(cat $f 2>/dev/null)\"; done;"
                        + "echo \"su=$(command -v su || echo none)\""));
        return b.toString();
    }

    private static final android.os.Handler H =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable pendingBacklight;

    /** 亮度写入专用后台线程：懒启动，只有开了自动亮度才会创建 */
    private static volatile android.os.Handler bgHandler;

    private static android.os.Handler bg() {
        android.os.Handler h = bgHandler;
        if (h == null) {
            synchronized (Brightness.class) {
                h = bgHandler;
                if (h == null) {
                    android.os.HandlerThread t =
                            new android.os.HandlerThread("pipdesk-brightness");
                    t.start();
                    h = new android.os.Handler(t.getLooper());
                    bgHandler = h;
                }
            }
        }
        return h;
    }

    // 现在由后台线程写、设置页主线程读（describe / diagnose），volatile 保证可见
    private static volatile String lastLogged = "";
    private static volatile String lastReport = "";
    private static volatile int lastSysfsValue = -1;
    private static volatile int maxCache = 0;

    /**
     * 关掉总开关时把亮度控制权还给系统：原来自动就恢复自动，原来手动就恢复原数值。
     */
    public static void restore(Context c) {
        int mode = Prefs.getBrightSavedMode(c);
        if (mode < 0) return;                    // 从没接管过，不用还原
        int v = Prefs.getBrightSavedValue(c);
        try {
            ContentResolver cr = c.getContentResolver();
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                Log.i(TAG, "已交还亮度控制权：恢复系统自动亮度");
            } else {
                if (v >= 0) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Log.i(TAG, "已交还亮度控制权：恢复原数值 " + v);
            }
            // 显示层 / 背光节点也还回原百分比
            if (v >= 0) {
                setDisplayBrightness(v / 255f);
                writeBacklightDebounced(v / 255f);
            }
        } catch (Throwable t) {
            Log.w(TAG, "还原亮度失败：" + t.getClass().getSimpleName());
        }
        Prefs.setBrightSaved(c, -1, -1);
        lastLogged = "";
        lastReport = "";
        lastSysfsValue = -1;
    }
}
