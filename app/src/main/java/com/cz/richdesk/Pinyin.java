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
