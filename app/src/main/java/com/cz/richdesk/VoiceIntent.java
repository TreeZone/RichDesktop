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
        /** 命中分数（0~100，只有 APP 有） */
        public int score;
        /** 命中的是哪一层：name / prefix / contains / edit / pinyin / alias:xxx */
        public String via = "";
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
        // cleanWake 同时处理字面唤醒词和同音错字（小瑞 → 小雷），匹配和显示用同一套口径
        String t = cleanWake(raw);
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
        Map<AppInfo, String> why = new HashMap<>();
        List<AppInfo> ordered = new ArrayList<>();
        for (AppInfo a : apps) {
            if (a == null || a.pkg == null) continue;
            String[] src = new String[1];
            int s = scoreOneEx(q, a.label, src);
            String via = src[0];
            Set<String> al = aliases == null ? null : aliases.get(a.pkg);
            if (al != null) {
                for (String x : al) {
                    String[] src2 = new String[1];
                    int s2 = scoreOneEx(q, x, src2) + 4;   // 用户亲口教的说法，优先级略高于名字
                    if (s2 > s) {
                        s = s2;
                        via = "alias:" + src2[0];
                    }
                }
                // 声母骨架：英文应用名 ASR 每次吐的汉字都不同，整串比对必然落空。
                // 骨架只判「像不像」，上限 78 分、永远低于直接执行门槛 →
                // 只负责把最像的排到候选第一位，绝不会静默打开。
                int sk = skeletonScore(q, al);
                if (sk > s) {
                    s = sk;
                    via = "skeleton";
                }
            }
            if (s >= MIN_SCORE) {
                scored.put(a, s);
                why.put(a, via == null ? "?" : via);
                ordered.add(a);
            }
        }
        if (ordered.isEmpty()) {
            // 【英文应用兜底】中文模型词表里一个拉丁字母都没有，"打开 gpsbridge"
            // 必然被识别成一串不相干的汉字，一个应用也匹配不上。
            // 与其回「没找到「…」」，把带英文名的应用全部收集（按相关度稳定排序），
            // 交给 Voice 侧分页 + 说名字检索 —— 不硬截 9 个，候选数量没有上限。
            // 别名绑定在这里**不是**前提：第一次用就有效。
            if (q.length() >= 2 && appendLatinCandidates(r.candidates, apps, q, aliases) > 0) {
                r.kind = AMBIGUOUS;
                r.via = "fallback:latin";
                r.score = 0;
                return r;
            }
            return r;
        }

        ordered.sort((x, y) -> scored.get(y) - scored.get(x));
        AppInfo best = ordered.get(0);
        int bestScore = scored.get(best);
        r.pkg = best.pkg;
        r.label = best.label;
        r.score = bestScore;
        r.via = why.get(best);

        // 【C】低置信度一律先确认，别赌一个直接打开。
        // 旧版只在「多个 app 分数咬得很近」时才给候选；但真实事故是只有一个短名字
        // （音乐）靠「被说法包含」拿了 86 分、把正确的「波点音乐」(70 分) 压下去，
        // 分差 16 ≥ AMBIGUOUS_GAP，于是静默打开了错的 app。
        // 现在：只有字形完全一致 / 用户教过的别名 / 读音完全一致的整名才直接执行；
        // 其余 contains / prefix / edit / 模糊拼音都弹候选（可点，也可说「第一个」）。
        boolean confident = bestScore >= 90
                || "name".equals(r.via)
                || (r.via != null && r.via.startsWith("alias:"))
                || "pinyin:exact".equals(r.via);
        if (!confident) {
            r.kind = AMBIGUOUS;
            for (int i = 0; i < ordered.size() && i < 4; i++) {
                if (bestScore - scored.get(ordered.get(i)) < 25) r.candidates.add(ordered.get(i));
            }
            if (r.candidates.isEmpty()) r.candidates.add(best);   // 至少让用户能确认这一个
            // 低置信度时把英文名应用一并补上：否则某个中文短名字歪打正着，
            // 候选里就只有它，用户想开的英文 app 根本没得选。
            appendLatinCandidates(r.candidates, apps, q, aliases);
            return r;
        }

        if (ordered.size() > 1 && bestScore - scored.get(ordered.get(1)) < AMBIGUOUS_GAP) {
            // 差得太近 = 听不出是哪个：与其赌一个，不如让用户点（点完会记成别名）
            r.kind = AMBIGUOUS;
            for (int i = 0; i < ordered.size() && i < 4; i++) {
                if (bestScore - scored.get(ordered.get(i)) < AMBIGUOUS_GAP) {
                    r.candidates.add(ordered.get(i));
                }
            }
            appendLatinCandidates(r.candidates, apps, q, aliases);
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
     * ASR 的错字（小助 / 小住 / 小朱 / 小雷）一并当唤醒词处理：字面表只放常见的，
     * 其余交给 {@link #cleanWake(String)} 的拼音同音判定，不用穷举。
     */
    private static final String[] WAKE_WORDS = {
            "你好小瑞", "你好小锐", "你好小睿", "你好小蕊", "你好小芮", "你好小雷",
            "你好小桌", "小桌小桌", "小桌同学", "你好晓桌", "你好小助",
            "你好", "小瑞", "小锐", "小睿", "小蕊", "小雷", "小蕾", "小磊",
            "小桌", "小助", "小住", "小朱", "小竹",
            "我在", "我在呢",           // 助手自己的 TTS 回复被回采进来的情况
    };

    /**
     * 用户在设置里配的唤醒词（由 Voice 加载语音时注入）。
     *
     * 为什么必须动态注进来：上面那张表是写死的，而唤醒词是可以改的 ——
     * 默认就是「你好小瑞」，改过之后表里那些「小桌」全都对不上，
     * 于是「你好小瑞，打开微信」识别成一整句去匹配应用名，永远匹配不上。
     */
    private static volatile String[] customWake = new String[0];

    /** 设置当前唤醒词。会同时接受完整词和去前缀后的短词（「你好小瑞」→「小瑞」） */
    static void setWakeWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            customWake = new String[0];
            return;
        }
        String w = word.trim();
        String tail = w.length() > 2 ? w.substring(2) : "";
        customWake = tail.length() >= 2 ? new String[]{w, tail} : new String[]{w};
    }

    /**
     * 剥掉开头的唤醒词，**匹配层与显示层共用**。
     *
     * 旧版 stripWake 只认写死的字面变体，且要求「剥完还得剩字」；用户实测
     * 「你好小瑞」唤醒后字幕里仍会出现「小瑞」，或 ASR 听成「小雷」根本剥不掉。
     * 这里补两件事：
     *   1) 字面表按长度降序剥，整句只剩唤醒词时也允许剥成空（显示不该出现它）；
     *   2) 以自定义唤醒词（完整词 + 去前缀短词）的拼音串为基准做同音判定，
     *      「小瑞 → 小雷 / 小蕾 / 小磊」这类错字一律认成唤醒词尾巴。
     *
     * 刻意**不做单字前缀剥除**：否则「小红书」这类以「小」开头的真实应用名
     * 会被误剥成「红书」。至少 2 个字才允许按拼音前缀剥（流式字幕的前半截）。
     */
    public static String cleanWake(String raw) {
        if (raw == null) return "";
        String s = normalize(raw);
        if (s.isEmpty()) return "";
        final String[] cw = customWake;

        java.util.List<String> words = new java.util.ArrayList<>();
        java.util.Collections.addAll(words, WAKE_WORDS);
        java.util.Collections.addAll(words, cw);
        words.sort((a, b) -> b.length() - a.length());
        for (String w : words) {
            if (w.length() >= 2 && s.startsWith(w)) return s.substring(w.length());
        }

        for (String w : cw) {
            if (w.length() < 2) continue;
            String pw = Pinyin.of(w);
            if (pw.isEmpty() || pw.equals(w)) continue;     // 没转出拼音就别瞎比
            int n = Math.min(w.length(), s.length());
            if (n < 2) continue;
            String p = Pinyin.of(s.substring(0, n));
            if (p.isEmpty()) continue;
            // 完整长度：允许 2 个字母的同音误差（rui / lei）
            if (pw.equals(p) || distance(pw, p, 2) <= 2) return s.substring(n);
            // 流式只出了前半截（≥2 字）：按拼音前缀剥
            if (pw.startsWith(p)) return s.substring(n);
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
        return scoreOneEx(q, name, null);
    }

    /**
     * 打分，并告诉调用方是**哪一层**命中的。
     *
     * 层次按可信度从高到低：完全相同 → 前缀 → 包含 → 编辑距离 → 拼音。
     * 来源写进日志，看到「70分 edit」就知道是靠字形硬凑的，一眼能定位误匹配。
     *
     * 【关键修正】旧版是**逐层短路 return**：编辑距离命中就返回 70，拼音层根本没机会跑。
     * 于是「播点音乐」对「波点音乐」只拿 70 分，反而输给「音乐」靠 q.contains(s) 拿的 86 分，
     * 直接打开了错的 app。现在各层都算、取最大值，同音整名能拿到 96 分稳赢。
     *
     * @param src 长度 ≥1 的数组，用来接收来源；可传 null
     */
    static int scoreOneEx(String q, String name, String[] src) {
        if (name == null) return 0;
        String s = normalize(name);
        if (s.isEmpty() || q.isEmpty()) return 0;
        if (s.equals(q)) {
            set(src, "name");
            return 100;
        }
        int best = 0;
        String via = "";

        if (q.length() >= 2) {
            if (s.startsWith(q)) {
                best = clamp(94 - (s.length() - q.length()) * 2, 62, 94);
                via = "prefix";
            }
            int c = clamp(90 - (s.length() - q.length()) * 2, 62, 90);
            if (s.contains(q) && c > best) { best = c; via = "contains"; }
            // 「应用名是说法的子串」是最危险的一档：短名字（音乐 / 地图 / 电话）
            // 能靠它抢走任何包含自己的长说法。加覆盖率门槛，并把上限压到 72，
            // 保证它永远低于「整名同音 / 编辑距离」的命中。
            if (s.length() >= 2 && q.contains(s)) {
                int cover = s.length() * 100 / q.length();
                int c2 = cover >= 70 ? clamp(50 + cover / 4, 60, 72) : 0;
                if (c2 > best) { best = c2; via = "contains"; }
            }
        }
        // 编辑距离：名字与说法长度要相近，否则「音乐」也能蹭到「播点音乐」的分
        int maxLen = Math.max(s.length(), q.length());
        int minLen = Math.min(s.length(), q.length());
        if (q.length() >= 3 && minLen * 100 / maxLen >= 60
                && Math.abs(s.length() - q.length()) <= Math.max(1, maxLen / 2)) {
            int d = distance(s, q, 3);
            double ratio = 1 - (double) d / maxLen;
            int e = ratio >= 0.5 ? (int) Math.round(40 + ratio * 40) : 0;  // 0.5→60, 1.0→80
            if (e > best) { best = e; via = "edit"; }
        }
        // 读音这层现在一定会跑到：同音错字（波/播、云/亦）字形上差得远，全靠它救
        String[] pySrc = new String[1];
        int py = pinyinScore(q, s, pySrc);
        if (py > best) { best = py; via = pySrc[0] == null ? "pinyin" : pySrc[0]; }

        set(src, via.isEmpty() ? "none" : via);
        return best;
    }

    private static void set(String[] src, String v) {
        if (src != null && src.length > 0) src[0] = v;
    }

    /**
     * 按拼音比较，返回 0 表示不沾边；完全同音时把来源标成 pinyin:exact。
     *
     * 读音**完全一致**意味着只是同一个词的不同写法（波点 / 播点），可信度仅次于
     * 字形完全相同，给 96 分 —— 这样它才不会输给任何「短名字被说法包含」的低层命中。
     * 其余模糊拼音仍压在 80 以下，避免一个"听着像"的候选抢走精确匹配。
     */
    private static int pinyinScore(String q, String s, String[] src) {
        if (q.length() < 2 || s.length() < 2) return 0;
        String pq = Pinyin.of(q);
        String ps = Pinyin.of(s);
        if (pq.isEmpty() || ps.isEmpty()) return 0;
        // 拼音没被改写（纯英文/数字，ICU 不动它们）→ 等于把字形又比一遍，没意义
        if (pq.equals(q) && ps.equals(s)) return 0;

        if (pq.equals(ps)) { set(src, "pinyin:exact"); return 96; }

        // 说法往往只是应用名的一部分（「网易云」⊂「网易云音乐」），拼音串自然也是前缀。
        if (pq.length() >= 3) {
            if (ps.startsWith(pq)) { set(src, "pinyin"); return 80; }
            if (ps.contains(pq))   { set(src, "pinyin"); return 74; }
        }

        int maxLen = Math.max(pq.length(), ps.length());
        if (maxLen < 3) return 0;
        if (Math.abs(pq.length() - ps.length()) * 2 > maxLen) return 0;   // 长度差太多，别硬凑
        int d = distance(pq, ps, 4);
        if (d > 4) return 0;
        double ratio = 1 - (double) d / maxLen;
        if (ratio >= 0.72) {
            set(src, "pinyin");
            return (int) Math.round(50 + ratio * 28);                      // 0.72→70, 1.0→78
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

    /** 名字里带拉丁字母：中文 ASR 词表里一个字母都没有，这类名字永远匹配不上 */
    private static boolean isLatinName(String label) {
        if (label == null) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return true;
        }
        return false;
    }

    /**
     * 把带英文名的应用补进候选，返回补进去的条数。
     *
     * 【不再硬截 9 个】车机上这类应用可能几十个，截前 9 个 + HashMap 的任意顺序
     * 会让排在后面的 app 永远选不到（用户 14:24 指出）。现在全部收集、稳定排序，
     * 数量交给 Voice 侧分页消化：
     *   · 学过这个 app 的说法 → 骨架相似度高 → 顶到第一页；
     *   · 其余按名字字母序 → 顺序可预期，翻页时不会乱跳。
     */
    private static int appendLatinCandidates(List<AppInfo> out, List<AppInfo> apps,
                                             String q, Map<String, Set<String>> aliases) {
        if (out == null || apps == null) return 0;
        final List<AppInfo> latin = new ArrayList<>();
        for (AppInfo a : apps) {
            if (a == null || a.pkg == null || !isLatinName(a.label)) continue;
            boolean dup = false;
            for (AppInfo x : out) {
                if (x != null && x.pkg != null && x.pkg.equals(a.pkg)) { dup = true; break; }
            }
            if (!dup) {
                for (AppInfo x : latin) {
                    if (x.pkg.equals(a.pkg)) { dup = true; break; }
                }
            }
            if (dup) continue;
            latin.add(a);
            if (latin.size() >= 60) break;      // 极端保护；再多靠「说名字」找
        }
        if (latin.isEmpty()) return 0;
        final Map<AppInfo, Integer> rel = new HashMap<>();
        for (AppInfo a : latin) {
            Set<String> al = aliases == null ? null : aliases.get(a.pkg);
            rel.put(a, al == null ? 0 : skeletonScore(q, al));
        }
        latin.sort((x, y) -> {
            int d = rel.get(y) - rel.get(x);
            if (d != 0) return d;
            String nx = x.label == null ? "" : x.label;
            String ny = y.label == null ? "" : y.label;
            return nx.compareToIgnoreCase(ny);
        });
        out.addAll(latin);
        return latin.size();
    }

    /** 候选分页：+1 下一页 / -1 上一页 / 0 不是翻页指令 */
    static int parsePage(String raw) {
        String t = normalize(raw);
        if (t.isEmpty()) return 0;
        String[] next = {"下一页", "下一批", "下一组", "换一批", "换一页", "后面的", "往后"};
        for (String w : next) if (t.equals(w) || t.startsWith(w)) return 1;
        String[] prev = {"上一页", "上一批", "上一组", "前面的", "往前", "退回"};
        for (String w : prev) if (t.equals(w) || t.startsWith(w)) return -1;
        return 0;
    }

    /**
     * 候选态不报序号、直接说出名字时，在**完整候选**里再匹配一次。
     * 英文名 label 是字母、ASR 只吐汉字，所以主要靠已学别名的声母骨架；
     * 必须明显领先第二名（≥7 分）才选中，否则宁可当新指令，不误选。
     */
    static int pickByText(String raw, List<AppInfo> cands, Map<String, Set<String>> aliases) {
        String q = normalize(cleanWake(raw));
        if (q.length() < 2 || cands == null || cands.isEmpty()) return -1;
        int best = -1, bestScore = 0, second = 0;
        for (int i = 0; i < cands.size(); i++) {
            AppInfo a = cands.get(i);
            if (a == null || a.pkg == null) continue;
            String[] src = new String[1];
            int s = scoreOneEx(q, a.label, src);
            Set<String> al = aliases == null ? null : aliases.get(a.pkg);
            if (al != null) {
                for (String x : al) {
                    String[] s2 = new String[1];
                    int v = scoreOneEx(q, x, s2) + 4;
                    if (v > s) s = v;
                }
                s = Math.max(s, skeletonScore(q, al));
            }
            if (s > bestScore) {
                second = bestScore;
                bestScore = s;
                best = i;
            } else if (s > second) {
                second = s;
            }
        }
        return (best >= 0 && bestScore >= 60 && bestScore - second >= 7) ? best : -1;
    }

    /**
     * 声母骨架相似分：把「这次吐的汉字串」和「以前点选过的汉字串」都转成
     * 声母序列再比，吸收字形漂移与音节切分漂移。
     *
     * **上限 78 是刻意的**：低于「≥90 直接执行」的门槛，所以骨架命中一定还会弹候选，
     * 它只决定候选顺序。用户质疑「每次汉字都不一样，绑定了也没用」——
     * 这里的答案是：不靠绑定精确命中，靠骨架把正确 app 排到第一个，让人一次选中。
     */
    private static int skeletonScore(String q, Set<String> samples) {
        if (q == null || samples == null || samples.isEmpty()) return 0;
        String qa = Pinyin.initials(normalize(q));
        if (qa.length() < 3) return 0;
        int best = 0;
        for (String x : samples) {
            String xa = Pinyin.initials(normalize(x));
            if (xa.length() < 3) continue;
            int max = Math.max(qa.length(), xa.length());
            if (Math.abs(qa.length() - xa.length()) > 2) continue;
            int d = distance(qa, xa, 3);
            if (d > 3) continue;
            double ratio = 1 - (double) d / max;
            if (ratio >= 0.62) {
                int sc = (int) Math.round(50 + ratio * 28);
                if (sc > best) best = sc;
            }
        }
        return Math.min(best, 78);
    }

    /** 候选弹框后说「都不是 / 不对 / 取消」：不选中任何一项，直接收掉 */
    static boolean isPickCancel(String raw) {
        String t = normalize(raw);
        if (t.isEmpty()) return false;
        String[] words = {"都不是", "都不对", "不对", "不是这些", "取消了", "取消",
                "算了", "不要了", "不要", "重新说", "都不是这些"};
        for (String w : words) {
            if (t.equals(w) || t.startsWith(w)) return true;
        }
        return false;
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
            case '5': case '五': case '舞': idx = 5; break;
            case '6': case '六': case '溜': idx = 6; break;
            case '7': case '七': case '漆': idx = 7; break;
            case '8': case '八': case '巴': idx = 8; break;
            case '9': case '九': case '酒': idx = 9; break;
            default: return -1;
        }
        // 只认「第 N 个」这种纯数字/单字，别把「一个导航」误判成选择
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
            case APP: return "应用 " + r.label + " → " + r.pkg
                    + "（" + r.score + "分 " + r.via + "）";
            case AMBIGUOUS: {
                StringBuilder b = new StringBuilder("候选");
                // 英文名应用可能几十个，日志只列前 8 个 + 总数，别刷成一行长龙
                for (int i = 0; i < r.candidates.size() && i < 8; i++) {
                    b.append(' ').append(r.candidates.get(i).label);
                }
                if (r.candidates.size() > 8) b.append(" …共 ").append(r.candidates.size()).append(" 个");
                b.append("（最高 ").append(r.score).append("分 ").append(r.via).append("）");
                return b.toString();
            }
            default: return "未识别";
        }
    }

    /** 供测试：把一组说法跑一遍，返回每条的判定（诊断页用） */
    static String selfTest(List<AppInfo> apps, Map<String, Set<String>> aliases) {
        // 后四条专门测"听错字"：网亦云/酷够/比里比里 都该靠拼音那一层命中，
        // 优图布 该靠 YouTube 的谐音别名命中（模型吐不出英文字母）
        String[] cases = {"打开高德地图", "高德", "导航", "来点音乐", "网易云", "qq",
                "打开应用列表", "返回", "息屏", "切换到百度地图",
                "网亦云", "打开酷够", "比里比里", "优图布"};
        StringBuilder b = new StringBuilder();
        for (String c : cases) {
            Result r = parse(c, apps, aliases);
            b.append(c).append(" → ").append(describe(r)).append('\n');
        }
        return b.toString();
    }

    /**
     * 常用应用的默认别名（按名字里的关键字挂，换安装包也不影响）。
     *
     * 【英文名为什么必须列谐音】我们用的识别模型词表是 5508 个单汉字，
     * **一个英文字母都没有** —— 用户说「YouTube」，模型只会吐出最接近的汉字串
     * （优图布 / 油管 / 有土伯…），永远不可能吐出 "youtube"。
     * 所以英文应用必须把"中国人会怎么说、模型会怎么听岔"的中文写法一起登记，
     * 否则那类名字在语音里等于不存在。字母写法也留着：用户教过别名、或者换了
     * 双语模型之后还能直接命中。
     */
    static Map<String, Set<String>> builtinAliases(List<AppInfo> apps) {
        Map<String, Set<String>> m = new HashMap<>();
        if (apps == null) return m;
        String[][] rules = {
                {"高德地图", "高德|导航|地图|amap|高德导航"},
                {"百度地图", "百度|小度|bdmap|百度导航"},
                {"腾讯地图", "腾讯地图|tmap"},
                {"网易云音乐", "网易云|云音乐|网易|网抑云"},
                {"QQ音乐", "qq音乐|qq|q音|扣扣音乐"},
                {"酷狗音乐", "酷狗|kugou|酷够|哭狗"},
                {"酷我音乐", "酷我|kuwo|酷窝"},
                {"喜马拉雅", "喜马|听书|播客|ximalaya"},
                {"哔哩哔哩", "b站|bilibili|哔哩|哔哩哔哩|比利比利|比里比里|bili|小破站"},
                {"抖音", "抖音|tiktok|抖|嘀嗒"},
                {"快手", "快手|kuaishou"},
                {"微信", "微信|wechat|威信|vx"},
                {"QQ", "qq|扣扣"},
                {"爱奇艺", "爱奇艺|iqiyi|爱奇异|奇艺"},
                {"腾讯视频", "腾讯视频|企鹅|腾讯"},
                {"优酷", "优酷|youku|优库"},
                {"收音机", "收音机|电台|fm|广播"},
                {"电话", "电话|拨号|打电话|通讯录"},
                {"蓝牙音乐", "蓝牙音乐|蓝牙|bt音乐"},
                {"文件管理", "文件|文件夹|文件管理|文档"},
                {"浏览器", "浏览器|上网|网页|browser"},
                // ---- 下面这些主要是给"英文名被听成中文"准备的 ----
                {"YouTube", "youtube|油管|优兔|尤图布|优图布|有土伯|油土鳖|ytb"},
                {"Telegram", "telegram|电报|特拉|纸飞机|tg"},
                {"WhatsApp", "whatsapp|瓦次|瓦茨|沃茨|whats"},
                {"Spotify", "spotify|斯波提|声田|斯波蒂菲|斯波提飞"},
                {"Instagram", "instagram|因斯|照片墙|ins|ig"},
                {"Twitter", "twitter|推特|小蓝鸟|x平台"},
                {"Facebook", "facebook|脸书|非死不可|fb"},
                {"Netflix", "netflix|奈飞|网飞"},
                {"Chrome", "chrome|谷歌浏览器|克罗姆"},
                {"谷歌地图", "谷歌地图|googlemaps|狗哥地图"},
                {"谷歌商店", "play商店|谷歌商店|googleplay|应用商店"},
                {"Gmail", "gmail|谷歌邮箱|寄邮件"},
                {"Disney", "disney|迪士尼|迪斯尼"},
                {"Amazon", "amazon|亚马逊"},
                {"Uber", "uber|优步|乌伯"},
                {"Reddit", "reddit|红迪"},
                {"LinkedIn", "linkedin|领英"},
                {"Steam", "steam|蒸汽|斯缇姆"},
                {"Discord", "discord|迪斯科德|dc"},
                {"Zoom", "zoom|租姆|祖姆"},
        };
        for (AppInfo a : apps) {
            if (a == null || a.label == null) continue;
            String low = a.label.toLowerCase(java.util.Locale.US);
            for (String[] rule : rules) {
                // 忽略大小写：YouTube / youtube / YOUTUBE 都要能挂上
                if (low.contains(rule[0].toLowerCase(java.util.Locale.US))) {
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
