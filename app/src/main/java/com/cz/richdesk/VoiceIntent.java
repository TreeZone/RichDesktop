package com.cz.richdesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「说的一句话」→「要做什么」。纯字符串逻辑，没有 Android 依赖，方便单独调。
 *
 * 为什么不用本地大模型做这一步：
 *   命令空间是封闭的（本机几十个应用 + 十来个内置指令），关键词匹配在这个任务上
 *   精度不比 LLM 低、延迟是 0、内存是 0，而且错了能一眼看出为什么错。
 *   真正影响体验的是识别错字，那个由这里的模糊匹配（包含 / 前缀 / 编辑距离）兜住，
 *   再兜不住就弹候选让用户点一下 —— 点完那次说法会记成别名，下次直接命中。
 */
public final class VoiceIntent {

    public static final int NONE = 0;
    public static final int CMD = 1;
    public static final int APP = 2;
    public static final int AMBIGUOUS = 3;

    /** 命中下限：低于这个分数宁可不猜 */
    private static final int MIN_SCORE = 60;
    /** 第一名和第二名差多少算"听不出是哪个"，走候选 */
    private static final int AMBIGUOUS_GAP = 7;

    public static final class Result {
        public int kind = NONE;
        public String pkg;
        public String label;
        /** 归一化之后实际用于匹配的说法（写日志 / 教别名时用） */
        public String text = "";
        public final List<AppInfo> candidates = new ArrayList<>();
    }

    private VoiceIntent() { }

    /** 内置指令：说法 → 伪包名。全部走已有的 runBuiltin()，不需要新实现 */
    private static final String[][] COMMANDS = {
            {"回主页", AppInfo.HOME_PKG}, {"回到主页", AppInfo.HOME_PKG},
            {"回家", AppInfo.HOME_PKG}, {"主页", AppInfo.HOME_PKG},
            {"主屏", AppInfo.HOME_PKG}, {"主界面", AppInfo.HOME_PKG},
            {"返回", AppInfo.BACK_PKG}, {"后退", AppInfo.BACK_PKG},
            {"退回去", AppInfo.BACK_PKG}, {"返回上一级", AppInfo.BACK_PKG},
            {"应用列表", AppInfo.APP_LIST_PKG}, {"所有应用", AppInfo.APP_LIST_PKG},
            {"全部应用", AppInfo.APP_LIST_PKG}, {"应用页", AppInfo.APP_LIST_PKG},
            {"应用列表页", AppInfo.APP_LIST_PKG},
            {"息屏", AppInfo.SLEEP_PKG}, {"关屏", AppInfo.SLEEP_PKG},
            {"黑屏", AppInfo.SLEEP_PKG}, {"关闭屏幕", AppInfo.SLEEP_PKG},
            {"最近任务", AppInfo.RECENTS_PKG}, {"任务列表", AppInfo.RECENTS_PKG},
            {"多任务", AppInfo.RECENTS_PKG},
            {"深色模式", AppInfo.THEME_PKG}, {"夜间模式", AppInfo.THEME_PKG},
            {"暗色模式", AppInfo.THEME_PKG}, {"浅色模式", AppInfo.THEME_PKG},
            {"白天模式", AppInfo.THEME_PKG}, {"亮色模式", AppInfo.THEME_PKG},
            {"桌面设置", AppInfo.SETTINGS_PKG}, {"桌面设置页", AppInfo.SETTINGS_PKG},
            {"重启桌面", AppInfo.RESTART_PKG}, {"重启启动器", AppInfo.RESTART_PKG},
    };

    /** 说「打开 X」时前面那串废话，长的排前面（先剥长的，避免只剥掉「打开」留下「一下XX」） */
    private static final String[] PREFIXES = {
            "请帮我打开", "帮我打开一下", "帮我打开", "给我打开", "麻烦打开",
            "我想要打开", "我要打开", "我想打开", "我要用", "我想用", "我要看",
            "切换到", "切换成", "切到", "跳转到", "跳到", "进入了", "进入",
            "启动", "运行", "打开一下", "打开", "开一下", "开个", "上个", "去",
    };

    private static final String[] SUFFIXES = {
            "应用程序", "这个应用", "这个软件", "应用", "软件", "一下", "吧", "啊", "呀", "呢", "嘛",
    };

    public static Result parse(String raw, List<AppInfo> apps, Map<String, Set<String>> aliases) {
        Result r = new Result();
        if (raw == null) return r;
        String t = stripWake(normalize(raw));
        if (t.isEmpty()) return r;

        // 1) 内置指令：只认整句相等。带「打开」前缀的（"打开应用列表"）先剥前缀再比一次，
        //    但不做模糊 —— "返回"两个字被包含在某个应用名里时不应该执行按键。
        for (String[] c : COMMANDS) {
            if (c[0].equals(t)) {
                r.kind = CMD;
                r.pkg = c[1];
                r.label = c[0];
                r.text = t;
                return r;
            }
        }

        String body = strip(t);
        r.text = body.isEmpty() ? t : body;
        for (String[] c : COMMANDS) {
            if (c[0].equals(r.text)) {
                r.kind = CMD;
                r.pkg = c[1];
                r.label = c[0];
                return r;
            }
        }

        // 2) 应用名 / 别名匹配
        String q = r.text;
        if (q.isEmpty() || apps == null || apps.isEmpty()) return r;

        Map<AppInfo, Integer> scored = new HashMap<>();
        List<AppInfo> ordered = new ArrayList<>();
        for (AppInfo a : apps) {
            if (a == null || a.pkg == null) continue;
            int s = scoreOne(q, a.label);
            Set<String> al = aliases == null ? null : aliases.get(a.pkg);
            if (al != null) {
                for (String x : al) {
                    int s2 = scoreOne(q, x) + 4;   // 用户亲口教的说法，优先级略高于名字
                    if (s2 > s) s = s2;
                }
            }
            if (s >= MIN_SCORE) {
                scored.put(a, s);
                ordered.add(a);
            }
        }
        if (ordered.isEmpty()) return r;

        ordered.sort((x, y) -> scored.get(y) - scored.get(x));
        AppInfo best = ordered.get(0);
        int bestScore = scored.get(best);
        r.pkg = best.pkg;
        r.label = best.label;

        if (ordered.size() > 1 && bestScore - scored.get(ordered.get(1)) < AMBIGUOUS_GAP) {
            // 差得太近 = 听不出是哪个：与其赌一个，不如让用户点（点完会记成别名）
            r.kind = AMBIGUOUS;
            for (int i = 0; i < ordered.size() && i < 4; i++) {
                if (bestScore - scored.get(ordered.get(i)) < AMBIGUOUS_GAP) {
                    r.candidates.add(ordered.get(i));
                }
            }
            return r;
        }
        r.kind = APP;
        return r;
    }

    /** 去空白 / 标点，英文转小写 */
    public static String normalize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20) continue;
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);   // 全角 → 半角
            if (c == '。' || c == '，' || c == '、' || c == '？' || c == '！'
                    || c == '：' || c == '；' || c == '“' || c == '”'
                    || c == '‘' || c == '’' || c == '（' || c == '）'
                    || c == ' ' || c == '.' || c == ',' || c == '?' || c == '!'
                    || c == ':' || c == ';' || c == '\'' || c == '"'
                    || c == '(' || c == ')' || c == '~' || c == '～') continue;
            b.append(c);
        }
        return b.toString().toLowerCase();
    }

    /**
     * 去掉开头的唤醒词。
     *
     * 唤醒后我们会把环形缓冲里最后 300ms 补喂给识别器（防止「打开」两个字被切掉），
     * 那段音频里往往还带着唤醒词的尾巴，识别出来就是「你好小桌打开QQ音乐」。
     * ASR 的错字（小助 / 小住 / 小朱）一并当唤醒词处理。
     */
    private static final String[] WAKE_WORDS = {
            "你好小桌", "小桌小桌", "小桌同学", "你好晓桌", "你好小助",
            "你好", "小桌", "小助", "小住", "小朱", "小竹",
            "我在", "我在呢",           // 助手自己的 TTS 回复被回采进来的情况
    };

    private static String stripWake(String t) {
        String s = t;
        for (int guard = 0; guard < 3; guard++) {
            boolean hit = false;
            for (String w : WAKE_WORDS) {
                if (s.startsWith(w) && s.length() > w.length()) {
                    s = s.substring(w.length());
                    hit = true;
                    break;
                }
            }
            if (!hit) break;
        }
        return s;
    }

    /** 剥掉「打开 / 帮我打开 / 切到 …」前缀和「一下 / 吧 / 应用」后缀 */
    private static String strip(String t) {        String s = t;
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 4) {
            changed = false;
            for (String p : PREFIXES) {
                if (s.length() > p.length() && s.startsWith(p)) {
                    s = s.substring(p.length());
                    changed = true;
                }
            }
            for (String p : SUFFIXES) {
                if (s.length() > p.length() && s.endsWith(p)) {
                    s = s.substring(0, s.length() - p.length());
                    changed = true;
                }
            }
        }
        return s;
    }

    /** 单个候选名打分（0 = 不沾边） */
    static int scoreOne(String q, String name) {
        if (name == null) return 0;
        String s = normalize(name);
        if (s.isEmpty() || q.isEmpty()) return 0;
        if (s.equals(q)) return 100;
        if (q.length() >= 2) {
            if (s.startsWith(q)) return clamp(94 - (s.length() - q.length()) * 2, 62, 94);
            if (s.contains(q)) return clamp(90 - (s.length() - q.length()) * 2, 62, 90);
            if (s.length() >= 2 && q.contains(s)) return clamp(88 - (q.length() - s.length()), 62, 88);
        }
        // 编辑距离：只在不长不短的说法上启用，太短（1 个字）会到处乱命中
        int maxLen = Math.max(s.length(), q.length());
        if (q.length() >= 3 && Math.abs(s.length() - q.length()) <= Math.max(1, maxLen / 2)) {
            int d = distance(s, q, 3);
            double ratio = 1 - (double) d / maxLen;
            if (ratio >= 0.5) return (int) Math.round(40 + ratio * 40);   // 0.5→60, 1.0→80
        }
        return 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 带提前退出的编辑距离：超过 cap 直接返回 cap+1 */
    static int distance(String a, String b, int cap) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > cap) return cap + 1;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin > cap) return cap + 1;
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[lb];
    }

    /**
     * 「第几个」→ 下标（0 起），认不出来返回 -1。
     *
     * 候选列表出现后我们继续听，用户说「第一个 / 第2个 / 选第二个 / 二」都算。
     * ASR 经常把「二」听成「而」、「三」听成「散」，所以先做一层同音归一。
     */
    static int parsePick(String raw, int count) {
        if (raw == null || count <= 0) return -1;
        String t = normalize(raw);
        if (t.isEmpty()) return -1;
        // 去掉「我要/就/选/要/打开/第」这类前缀，保留数字部分
        String[] junk = {"我要", "就", "选", "要", "打开", "用", "第"};
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String j : junk) {
                if (t.startsWith(j) && t.length() > j.length()) {
                    t = t.substring(j.length());
                    stripped = true;
                }
            }
        }
        if (t.endsWith("个") || t.endsWith("号")) t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) return -1;
        char c = t.charAt(0);
        int idx;
        switch (c) {
            case '1': case '一': case '衣': idx = 1; break;
            case '2': case '二': case '两': case '而': idx = 2; break;
            case '3': case '三': case '散': idx = 3; break;
            case '4': case '四': case '是': idx = 4; break;
            default: return -1;
        }
        // 只认「1~4」这种纯数字/单字，别把「一个导航」误判成选择
        if (t.length() > 1) {
            char n = t.charAt(1);
            if (n != '个' && n != '号') return -1;
        }
        return (idx >= 1 && idx <= count) ? idx - 1 : -1;
    }

    /** 语音链路日志里用的简短描述 */
    static String describe(Result r) {
        switch (r.kind) {
            case CMD: return "指令 " + r.label + " → " + r.pkg;
            case APP: return "应用 " + r.label + " → " + r.pkg;
            case AMBIGUOUS: {
                StringBuilder b = new StringBuilder("候选");
                for (AppInfo a : r.candidates) b.append(' ').append(a.label);
                return b.toString();
            }
            default: return "未识别";
        }
    }

    /** 供测试：把一组说法跑一遍，返回每条的判定（诊断页用） */
    static String selfTest(List<AppInfo> apps, Map<String, Set<String>> aliases) {
        String[] cases = {"打开高德地图", "高德", "导航", "来点音乐", "网易云", "qq",
                "打开应用列表", "返回", "息屏", "切换到百度地图"};
        StringBuilder b = new StringBuilder();
        for (String c : cases) {
            Result r = parse(c, apps, aliases);
            b.append(c).append(" → ").append(describe(r)).append('\n');
        }
        return b.toString();
    }

    /** 常用应用的默认别名（按名字里的关键字挂，换安装包也不影响） */
    static Map<String, Set<String>> builtinAliases(List<AppInfo> apps) {
        Map<String, Set<String>> m = new HashMap<>();
        if (apps == null) return m;
        String[][] rules = {
                {"高德地图", "高德|导航|地图|amap"},
                {"百度地图", "百度|小度|bdmap"},
                {"腾讯地图", "腾讯地图|tmap"},
                {"网易云音乐", "网易云|云音乐|网易"},
                {"QQ音乐", "qq音乐|qq|q音"},
                {"酷狗音乐", "酷狗|kugou"},
                {"酷我音乐", "酷我|kuwo"},
                {"喜马拉雅", "喜马|听书|播客"},
                {"哔哩哔哩", "b站|bilibili|哔哩"},
                {"抖音", "抖音|tiktok|抖"},
                {"快手", "快手"},
                {"微信", "微信|wechat"},
                {"QQ", "qq"},
                {"爱奇艺", "爱奇艺|iqiyi"},
                {"腾讯视频", "腾讯视频|企鹅"},
                {"优酷", "优酷|youku"},
                {"收音机", "收音机|电台|fm"},
                {"电话", "电话|拨号|打电话"},
                {"蓝牙音乐", "蓝牙音乐|蓝牙"},
                {"文件管理", "文件|文件夹|文件管理"},
                {"浏览器", "浏览器|上网|网页"},
        };
        for (AppInfo a : apps) {
            if (a == null || a.label == null) continue;
            for (String[] rule : rules) {
                if (a.label.contains(rule[0])) {
                    Set<String> set = m.get(a.pkg);
                    if (set == null) {
                        set = new LinkedHashSet<>();
                        m.put(a.pkg, set);
                    }
                    for (String x : rule[1].split("\\|")) set.add(x);
                }
            }
        }
        return m;
    }
}
