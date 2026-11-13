package com.cz.richdesk;

import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.List;

/**
 * 媒体控制：播放 / 暂停 / 上一首 / 下一首 / 静音。给语音指令用。
 *
 * 两条路，按可靠性排：
 *   1) **MediaSession 直控** —— 拿到当前活跃的 {@link MediaController}，直接调
 *      `TransportControls`。好处是能读懂播放状态（"播放"找暂停的那个、"暂停"找在播的那个），
 *      也不会像媒体键那样被别的接收方吃掉。`getActiveSessions()` 需要
 *      `MEDIA_CONTENT_CONTROL`，我们只在 sysuid 变体（sharedUserId=android.uid.system）下才拿得到，
 *      拿不到会抛 SecurityException，直接走第 2 条。
 *   2) **媒体键** —— `AudioManager.dispatchMediaKeyEvent`，和方向盘媒体键同一条链路，
 *      车机系统会转发给当前持有音频焦点的那个 session。
 *
 * 静音为什么不用媒体键：它压根不是媒体键，是音量通道。这里直接改 STREAM_MUSIC，
 * 静音前的值备份进 Prefs，取消静音时恢复（车机上见过 `ADJUST_UNMUTE` 把音量恢复成 0 的 ROM）。
 *
 * 【车机实测注意】这台车机的方向盘音量走 Vehicle HAL / 功放 DSP 的独立通道，
 * 我们只能动 Android 的 STREAM_MUSIC，两边数值不联动（见项目日志 11-11，
 * 音量 ± 快捷方式就是因此被隐藏的）。所以**静音可能只静掉 Android 这一路**，
 * 功放那一路要不要跟着静由 ROM 决定 —— 播放/暂停/切歌不受影响，它们走 MediaSession。
 */
final class Media {

    private Media() { }

    /** 播 / 继续播 */
    static boolean play(Context c) {
        int[] prefer = {PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED,
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING};
        MediaController mc = pick(c, prefer);
        if (mc != null) {
            try {
                mc.getTransportControls().play();
                FileLog.append("MEDIA 播放 → " + pkgOf(mc));
                return true;
            } catch (Throwable t) {
                FileLog.append("MEDIA 播放走 MediaSession 失败：" + t);
            }
        }
        return key(c, KeyEvent.KEYCODE_MEDIA_PLAY, "播放");
    }

    /** 暂停 */
    static boolean pause(Context c) {
        int[] prefer = {PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING};
        MediaController mc = pick(c, prefer);
        if (mc != null) {
            try {
                mc.getTransportControls().pause();
                FileLog.append("MEDIA 暂停 → " + pkgOf(mc));
                return true;
            } catch (Throwable t) {
                FileLog.append("MEDIA 暂停走 MediaSession 失败：" + t);
            }
        }
        return key(c, KeyEvent.KEYCODE_MEDIA_PAUSE, "暂停");
    }

    /** 下一首 */
    static boolean next(Context c) {
        MediaController mc = pick(c, new int[0]);
        if (mc != null) {
            try {
                mc.getTransportControls().skipToNext();
                FileLog.append("MEDIA 下一首 → " + pkgOf(mc));
                return true;
            } catch (Throwable t) {
                FileLog.append("MEDIA 下一首走 MediaSession 失败：" + t);
            }
        }
        return key(c, KeyEvent.KEYCODE_MEDIA_NEXT, "下一首");
    }

    /** 上一首 */
    static boolean prev(Context c) {
        MediaController mc = pick(c, new int[0]);
        if (mc != null) {
            try {
                mc.getTransportControls().skipToPrevious();
                FileLog.append("MEDIA 上一首 → " + pkgOf(mc));
                return true;
            } catch (Throwable t) {
                FileLog.append("MEDIA 上一首走 MediaSession 失败：" + t);
            }
        }
        return key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一首");
    }

    /**
     * 静音切换，返回切换后**是否静音**；null = 音频服务拿不到，没动。
     *
     * 判据用「音量是否已经为最小值」而不是 `isStreamMute()`：车机上部分 ROM
     * 的 isStreamMute 恒为 false（静音是由功放做的），拿它判断会来回假切换。
     */
    static Boolean toggleMute(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        try {
            int min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur > min) {
                Prefs.setMediaVolumeBackup(c, cur);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0);
                FileLog.append("MEDIA 静音（原音量 " + cur + "）");
                return true;
            }
            int back = Prefs.mediaVolumeBackup(c);
            if (back <= min) back = Math.max(min + 1, Math.round(
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.5f));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, back, 0);
            FileLog.append("MEDIA 取消静音（恢复到 " + back + "）");
            return false;
        } catch (Throwable t) {
            FileLog.append("MEDIA 静音失败：" + t);
            return null;
        }
    }

    // ------------------------------------------------------------ 内部

    /** 当前媒体音量 {当前, 最大}（给音量提示条用；拿不到返回 null） */
    static int[] musicVolume(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        try {
            return new int[]{am.getStreamVolume(AudioManager.STREAM_MUSIC),
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 挑一个控制器：按 `prefer` 里的状态优先级找，都不匹配就用第一个"不是 STOPPED"的，
     * 实在没有就第一个 —— 有些播放器不上报 PlaybackState，但 TransportControls 照样能用。
     */
    private static MediaController pick(Context c, int[] prefer) {
        List<MediaController> list = sessions(c);
        if (list == null || list.isEmpty()) return null;
        for (int want : prefer) {
            for (MediaController mc : list) {
                if (state(mc) == want) return mc;
            }
        }
        for (MediaController mc : list) {
            if (state(mc) != PlaybackState.STATE_STOPPED) return mc;
        }
        return list.get(0);
    }

    private static int state(MediaController mc) {
        try {
            PlaybackState ps = mc.getPlaybackState();
            return ps == null ? PlaybackState.STATE_NONE : ps.getState();
        } catch (Throwable t) {
            return PlaybackState.STATE_NONE;
        }
    }

    private static List<MediaController> sessions(Context c) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            // 传 null：只要调用方自己持有 MEDIA_CONTENT_CONTROL（sysuid 下成立）就直接给全部
            return msm.getActiveSessions(null);
        } catch (Throwable t) {
            // SecurityException 是常态（非 sysuid 变体）—— 静默退回媒体键，不必刷日志
            return null;
        }
    }

    private static boolean key(Context c, int code, String what) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        try {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
            FileLog.append("MEDIA " + what + " → 媒体键 " + code);
            return true;
        } catch (Throwable t) {
            FileLog.append("MEDIA " + what + " 媒体键失败：" + t);
            return false;
        }
    }

    private static String pkgOf(MediaController mc) {
        try {
            return mc.getPackageName();
        } catch (Throwable t) {
            return "?";
        }
    }
}
