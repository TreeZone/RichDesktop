package com.cz.richdesk;

import android.os.Process;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;

/**
 * 常驻触摸注入服务。
 *
 * 由 app 以独立进程拉起：
 *   CLASSPATH=<apk 路径> app_process /system/bin com.cz.richdesk.InputServer
 *
 * app_process 不是 app 进程，不受 hidden API 过滤，
 * MotionEvent.setDisplayId / InputManager.injectInputEvent 都能正常反射到。
 *
 * 协议 v2（大端，帧式，带包头根治粘包/半包）：
 *   int  MAGIC = 0x5044504B ("PDPK")
 *   int  payloadLen
 *   payload:
 *     int  displayId
 *     int  action          （完整 action，含 pointer index 位）
 *     int  pointerCount
 *     int  histCount       （历史采样数，只有 MOVE 可能 >0）
 *     long downTime
 *     long eventTime       （宿主已锚定到注入时刻；这里只做钳制）
 *     int  metaState
 *     int  buttonState
 *     pointerCount × { int id, float x, float y, float pressure, float size }
 *     histCount × { long time; pointerCount × { float x, y, pressure, size } }
 *
 * 启动后先向 stdout 输出一行 "READY 1"（可用）或 "READY 0"（不可用）。
 * stdin 关闭即退出。
 */
public class InputServer {

    private static final int MAGIC = 0x5044504B;
    private static final int MAX_PAYLOAD = 64 * 1024;

    private static Object inputManager;
    private static java.lang.reflect.Method injectMethod;
    private static java.lang.reflect.Method setDisplayIdMethod;
    private static java.lang.reflect.Field displayIdField;

    public static void main(String[] args) {
        try {
            Process.setThreadPriority(-8); // URGENT_DISPLAY，尽量低延迟
        } catch (Throwable ignored) { }

        boolean ok = initInjector();
        System.out.println("READY " + (ok ? 1 : 0));
        System.out.flush();
        if (!ok) return;

        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 16384));
        try {
            while (true) {
                int magic = in.readInt();
                if (magic != MAGIC) {
                    // 流错位（理论上不会发生）：直接退出，宿主会重启并重新握手
                    System.out.println("ERR bad magic 0x" + Integer.toHexString(magic));
                    System.out.flush();
                    return;
                }
                int len = in.readInt();
                if (len <= 0 || len > MAX_PAYLOAD) {
                    System.out.println("ERR bad payload len " + len);
                    System.out.flush();
                    return;
                }
                byte[] payload = new byte[len];
                in.readFully(payload);      // 包长定界：粘包/半包都不会错位
                handle(payload);
            }
        } catch (EOFException eof) {
            // 宿主退出，正常收工
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void handle(byte[] payload) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
            int displayId = d.readInt();
            int action = d.readInt();
            int count = d.readInt();
            int histCount = d.readInt();
            long downTime = d.readLong();
            long eventTime = d.readLong();
            int metaState = d.readInt();
            int buttonState = d.readInt();
            if (count <= 0 || count > 10) return;

            // 时间轴兜底钳制：宿主已锚定到注入时刻，这里只保证不出界
            long now = android.os.SystemClock.uptimeMillis();
            if (eventTime > now) eventTime = now;
            if (downTime > eventTime) downTime = eventTime;

            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
                p.id = d.readInt();
                p.toolType = MotionEvent.TOOL_TYPE_FINGER;
                props[i] = p;

                MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
                c.x = d.readFloat();
                c.y = d.readFloat();
                c.pressure = d.readFloat();
                c.size = d.readFloat();
                coords[i] = c;
            }
            MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, count,
                    props, coords, metaState, buttonState, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            // 历史采样回放（快速滑动轨迹不跳格）
            for (int h = 0; h < histCount; h++) {
                long ht = d.readLong();
                MotionEvent.PointerCoords[] hc = new MotionEvent.PointerCoords[count];
                for (int i = 0; i < count; i++) {
                    MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
                    c.x = d.readFloat();
                    c.y = d.readFloat();
                    c.pressure = d.readFloat();
                    c.size = d.readFloat();
                    hc[i] = c;
                }
                long t = Math.min(Math.max(ht, downTime), eventTime);
                ev.addBatch(t, hc, 0);
            }
            inject(displayId, ev);
            ev.recycle();
        } catch (Throwable t) {
            System.out.println("ERR parse " + t);
            System.out.flush();
        }
    }

    private static boolean initInjector() {
        try {
            Class<?> imc = Class.forName("android.hardware.input.InputManager");
            java.lang.reflect.Method get = imc.getDeclaredMethod("getInstance");
            get.setAccessible(true);
            inputManager = get.invoke(null);

            injectMethod = imc.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            injectMethod.setAccessible(true);

            try {
                setDisplayIdMethod = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                setDisplayIdMethod.setAccessible(true);
            } catch (Throwable t) {
                // 个别 ROM 把方法裁了，退回改字段（字段名 mDisplayId）
                try {
                    displayIdField = MotionEvent.class.getDeclaredField("mDisplayId");
                    displayIdField.setAccessible(true);
                } catch (Throwable ignored) { }
            }
            return setDisplayIdMethod != null || displayIdField != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void inject(int displayId, MotionEvent ev) {
        try {
            if (setDisplayIdMethod != null) {
                setDisplayIdMethod.invoke(ev, displayId);
            } else {
                displayIdField.set(ev, displayId);
            }
            // 一律 ASYNC（scrcpy 同款）：虚拟屏上 WAIT_FOR_RESULT/WAIT_FOR_FINISH
            // 等不到完成，反而丢帧。
            int masked = ev.getActionMasked();
            boolean edge = masked == MotionEvent.ACTION_DOWN
                    || masked == MotionEvent.ACTION_UP
                    || masked == MotionEvent.ACTION_CANCEL
                    || masked == MotionEvent.ACTION_POINTER_DOWN
                    || masked == MotionEvent.ACTION_POINTER_UP;
            Object ret = injectMethod.invoke(inputManager, ev, 0);
            if (Boolean.FALSE.equals(ret) && edge) {
                // 关键帧失败：同步重试一次，还不行就打到 stdout（宿主会记进 logcat）
                ret = injectMethod.invoke(inputManager, ev, 0);
                if (Boolean.FALSE.equals(ret)) {
                    System.out.println("ERR inject dropped action=" + masked
                            + " display=" + displayId);
                    System.out.flush();
                }
            }
        } catch (Throwable ignored) {
            // 单帧失败不能拖垮整条通道
        }
    }
}
