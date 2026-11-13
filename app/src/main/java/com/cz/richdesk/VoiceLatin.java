package com.cz.richdesk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 英文字母 → 中文口语读法。给「打开 QQ」这类说法兜底。
 *
 * 为什么需要它：识别模型是纯中文的（词表 1000 条 BPE，一个拉丁字母都没有），
 * 用户说 QQ / 网易云 / B站 里的字母，ASR 只能吐读音相近的汉字（扣扣、秋秋、酷酷）。
 * 而匹配层拿 "扣扣" 去比应用名 "QQ" 必然落空 —— 两边根本不在一个字符集里。
 *
 * 解法不是换模型（双语模型 198MB 起，还要重写词表），而是**把英文名翻译成
 * 中国人会说出来的那个中文词**，当成别名挂上去：
 *   QQ       → 扣扣 / 秋秋 / 酷酷
 *   QQ音乐    → 扣扣音乐 / 秋秋音乐 / 酷酷音乐
 *   B站       → 比站
 * 之后走的是现成的别名通道（命中即执行），不需要动匹配算法。
 *
 * 每条读音只保留**拼音不同**的代表字：秋/球/求 都是 qiu，留一个就够。
 * 太长的英文名（超过 5 个字母）不转 —— "gpsbridge" 逐字母读出来没人这么说，
 * 硬转只会造出一堆永远匹配不上的词，反而拖慢匹配。
 */
final class VoiceLatin {

    /** 各字母的中文读法，按常见程度排序；同音的只留一个代表字 */
    private static final String[][] READINGS = {
            {"诶"},                  // A
            {"比"},                  // B
            {"西"},                  // C
            {"迪"},                  // D
            {"伊"},                  // E
            {"艾弗"},                // F
            {"吉"},                  // G
            {"艾尺"},                // H
            {"爱"},                  // I
            {"杰"},                  // J
            {"开"},                  // K
            {"艾勒"},                // L
            {"艾姆"},                // M
            {"恩"},                  // N
            {"欧"},                  // O
            {"屁"},                  // P
            {"扣", "秋", "酷"},       // Q —— 中文口语三种读法都常见
            {"阿"},                  // R
            {"艾斯"},                // S
            {"提"},                  // T
            {"优"},                  // U
            {"维"},                  // V
            {"达不溜"},              // W
            {"艾克斯"},              // X
            {"歪"},                  // Y
            {"贼"},                  // Z
    };

    /** 连续字母段最多这么长才转换：再长就不是"字母读音"而是英文单词了 */
    private static final int MAX_RUN = 5;
    /** 最多给几种读法（Q 有三档，其余大多只有一档） */
    private static final int MAX_VARIANTS = 3;

    private VoiceLatin() { }

    /** 名字里有没有拉丁字母 */
    static boolean hasLatin(String label) {
        if (label == null) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return true;
        }
        return false;
    }

    /**
     * 生成这个应用名的中文读法（0~3 条，去重）。
     *
     * 汉字原样保留、字母段逐字母读；有任何一段字母超过 {@link #MAX_RUN} 个就直接
     * 不生成（宁可不给，也不要造出"吉屁艾斯比阿艾迪伊"这种谁也说不出的词）。
     */
    static List<String> readingsOf(String label) {
        List<String> out = new ArrayList<>();
        if (label == null || label.isEmpty()) return out;
        // 纯中文名不生成：否则会给自己挂一条与名字完全相同的别名，白拿 +4 分
        if (!hasLatin(label)) return out;

        // 先按段拆：中文段原样、字母段展开成"每个位置的可选读音"
        List<Object> parts = new ArrayList<>();   // String（中文）或 List<String[]>（字母段）
        int i = 0;
        while (i < label.length()) {
            char c = label.charAt(i);
            if (isAsciiLetter(c)) {
                int j = i;
                while (j < label.length() && isAsciiLetter(label.charAt(j))) j++;
                int len = j - i;
                if (len > MAX_RUN) return out;     // 太长，整体放弃
                List<String[]> seg = new ArrayList<>(len);
                for (int k = i; k < j; k++) {
                    seg.add(READINGS[Character.toUpperCase(label.charAt(k)) - 'A']);
                }
                parts.add(seg);
                i = j;
            } else {
                parts.add(String.valueOf(c));
                i++;
            }
        }

        for (int v = 0; v < MAX_VARIANTS; v++) {
            StringBuilder b = new StringBuilder();
            boolean advanced = false;              // 这一档是否真的和第一档不同
            for (Object p : parts) {
                if (p instanceof String) {
                    b.append((String) p);
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<String[]> seg = (List<String[]>) p;
                for (String[] opts : seg) {
                    int idx = Math.min(v, opts.length - 1);
                    if (idx > 0) advanced = true;
                    b.append(opts[idx]);
                }
            }
            String s = b.toString();
            if (v > 0 && !advanced) break;         // 后面几档没有新读法，不用再加
            if (s.length() >= 2 && !s.equals(label) && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** 给别名表补上自动生成的中文读法（只处理带拉丁字母的名字） */
    static void fillAliases(java.util.Map<String, Set<String>> m, String pkg, String label) {
        if (m == null || pkg == null || !hasLatin(label)) return;
        List<String> rs = readingsOf(label);
        if (rs.isEmpty()) return;
        Set<String> set = m.get(pkg);
        if (set == null) {
            set = new LinkedHashSet<>();
            m.put(pkg, set);
        }
        set.addAll(rs);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
