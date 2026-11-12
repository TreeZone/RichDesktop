package com.cz.richdesk;

import android.icu.text.Transliterator;

import java.util.HashMap;
import java.util.Map;

/**
 * 汉字 → 无声调拼音。只服务于一个目的：**把听错字救回来**。
 *
 * 为什么需要它：识别错的类型分两种。
 *   - 字形错（酷狗 → 酷够）：编辑距离能兜住，一两个字之差。
 *   - 同音错（网易云 → 网亦云、酷狗 → 哭狗）：「云/亦」在字形上差得很远，
 *     编辑距离算出来是个大数，但**读音完全一样**。而人说话产生的错误恰恰是这种。
 * 所以汉字（尤其是短词）做匹配时，拼音相似度比字形相似度更能反映"用户到底说的哪个"。
 *
 * 实现用系统自带的 ICU 转写器（android.icu.text.Transliterator），
 * 不引第三方库、不占包体，也不用维护几万字的拼音表。
 * 转出来是带空格的 "ku gou yin le"，去掉空格和声调就是 "kugouyinle"。
 *
 * 注意：它是**尽力而为**的 —— ICU 数据缺失或者遇到纯英文时会原样返回，
 * 那种情况下拼音串等于原文，算出来的分数自然也不会高于字形匹配，不会帮倒忙。
 */
final class Pinyin {

    /** ICU 转写器 ID，按可用性依次尝试（不同 Android 版本暴露的组合不完全一样） */
    private static final String[] IDS = {
            "Han-Latin; Latin-ASCII",
            "Han-Latin",
            "Any-Latin; Latin-ASCII",
    };

    private static Transliterator[] chain;
    private static boolean initDone;
    private static boolean broken;

    /** 结果缓存：应用名是固定的，同一个名字没必要每次转一遍 */
    private static final Map<String, String> CACHE = new HashMap<>();
    private static final int CACHE_MAX = 512;

    private Pinyin() { }

    /** 拼音串（小写、无空格、无声调）。转不了时返回原串的小写形式 */
    static synchronized String of(String s) {
        if (s == null || s.isEmpty()) return "";
        String hit = CACHE.get(s);
        if (hit != null) return hit;

        String out = s;
        if (!broken && init()) {
            try {
                out = s;
                for (Transliterator t : chain) {
                    if (t == null) continue;
                    out = t.transliterate(out);
                }
            } catch (Throwable t) {
                // 转写失败不是致命问题：退化成原文，匹配层还有字形那一路
                out = s;
            }
        }
        out = squeeze(out);
        if (CACHE.size() > CACHE_MAX) CACHE.clear();
        CACHE.put(s, out);
        return out;
    }

    /**
     * 声母骨架：把汉字串转成「每个音节的首字母」序列。
     *
     * 为什么需要它：中文 ASR 听英文时，是把英文音素用读音相近的汉字拼出来，
     * **同一个人每次吐的汉字都不一样**（gpsbridge 这次「吉普斯布里奇」、
     * 下次「基皮埃斯布里奇」）。整串汉字 / 整串拼音都匹配不上，但两者的
     * 声母骨架都是 j-p-s-b-l-q —— 音节切分的漂移被零声母跳过吸收掉了。
     *
     * 实现复用 ICU 的链式转写（Han-Latin 输出按字带空格 "ji pu si bu li qi"），
     * 每段取首字母即可：zh/ch/sh 的首字母天然就是 z/c/s，等于自动折叠了翘舌。
     * 再补一层 r→l：这台车机的 ASR 常把「瑞」听成「雷」（r/l 混）。
     *
     * 它**只用于候选排序**，不作为直接执行的依据（见 VoiceIntent.skeletonScore）。
     * ICU 没给音节分隔时会退化成很短的串，那种情况直接返回空串让上层忽略，不帮倒忙。
     */
    static synchronized String initials(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s;
        if (!broken && init()) {
            try {
                for (Transliterator t : chain) {
                    if (t != null) out = t.transliterate(out);
                }
            } catch (Throwable ignored) {
                out = s;
            }
        }
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < out.length()) {
            char c = deTone(out.charAt(i));
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            if (c >= 'a' && c <= 'z') {
                char first = c;
                i++;
                while (i < out.length()) {           // 跳过这个音节剩下的字母
                    char n = deTone(out.charAt(i));
                    if (n >= 'A' && n <= 'Z') n = (char) (n + 32);
                    if (n >= 'a' && n <= 'z') i++;
                    else break;
                }
                // 零声母音节（a/e/o 起头）最不稳定，这次有下次没有，直接不算
                if (first == 'a' || first == 'e' || first == 'o') continue;
                if (first == 'r') first = 'l';        // 小瑞 → 小雷
                b.append(first);
            } else {
                i++;                                  // 空格 / 标点 / 数字都当分隔符
            }
        }
        // 退化保护：没有音节分隔时整串会被当成 1 个音节，骨架没有区分力
        return b.length() >= 3 ? b.toString() : "";
    }

    /** 去掉所有非字母数字字符并转小写："ku gou yin le" → "kugouyinle" */
    private static String squeeze(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = deTone(s.charAt(i));
            if (c >= 'A' && c <= 'Z') b.append((char) (c + 32));
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
            // 其余（空格、撇号等）一律丢掉
        }
        return b.toString();
    }

    /**
     * 带声调的字母 → 基础字母。
     *
     * 正常情况下 "Latin-ASCII" 那级转写已经把声调去掉了，这个函数用不上。
     * 但那是"链式转写"，某些 ROM 的 ICU 数据不完整时只有 "Han-Latin" 能用，
     * 那时输出是 "hǎo" 这种带调串 —— 不处理的话 ǎ 不在 a-z 里会被整字丢掉，
     * "hao" 就变成 "ho"，拼音层直接废掉。所以这里自己兜一层。
     */
    private static char deTone(char c) {
        switch (c) {
            case 'ā': case 'á': case 'ǎ': case 'à': return 'a';
            case 'ē': case 'é': case 'ě': case 'è': return 'e';
            case 'ī': case 'í': case 'ǐ': case 'ì': return 'i';
            case 'ō': case 'ó': case 'ǒ': case 'ò': return 'o';
            case 'ū': case 'ú': case 'ǔ': case 'ù': return 'u';
            case 'ǖ': case 'ǘ': case 'ǚ': case 'ǜ': case 'ü': return 'v';
            case 'ń': case 'ň': return 'n';
            case 'ḿ': return 'm';
            default: return c;
        }
    }

    private static boolean init() {
        if (initDone) return chain != null;
        initDone = true;
        for (String id : IDS) {
            try {
                Transliterator t = Transliterator.getInstance(id);
                if (t != null) {
                    chain = new Transliterator[]{t};
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        broken = true;
        FileLog.append("VOICE 系统缺少汉字转拼音能力（ICU Transliterator 不可用），"
                + "同音错字只能靠别名兜");
        return false;
    }
}
