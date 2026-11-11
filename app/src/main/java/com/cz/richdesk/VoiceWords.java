package com.cz.richdesk;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 唤醒词 → KWS 关键词文件。
 *
 * KWS 模型不是「听文本」，它认的是**带声调的拼音 token 序列**
 * （assets/kws/tokens.txt 里那 227 个音素），关键词文件长这样：
 *
 *     n ǐ h ǎo x iǎo r uì @你好小瑞
 *
 * 所以「自定义唤醒词」这件事的实质是：汉字 → 拼音 → 切成模型认识的音素。
 * 为了不依赖任何在线服务，这里做了两件离线的事：
 *   1) assets/kws/pinyin.txt：20891 个常用汉字 → 带声调拼音（构建时用 pypinyin 生成）；
 *   2) 按 tokens.txt 里的音素表做**最长前缀切分**（ruì → r + uì，xiǎo → x + iǎo）。
 *
 * 切不出来（生僻字 / 音素表里没有）就返回 null，调用方退回默认唤醒词 —— 宁可
 * 唤醒词不是用户想要的那个，也不能因为一个怪字让整套语音起不来。
 */
final class VoiceWords {

    /** 默认唤醒词：设置里没改过就用它 */
    static final String DEFAULT = "你好小瑞";

    private static Set<String> tokens;
    private static Map<Character, String> pinyin;

    private VoiceWords() { }

    /** 唤醒词（去空白；空 = 用默认） */
    static String wakeWord(Context c) {
        String w = Prefs.voiceWakeWord(c);
        return (w == null || w.trim().isEmpty()) ? DEFAULT : w.trim();
    }

    /**
     * 生成（或复用）关键词文件，返回绝对路径；失败返回 null。
     * 同一个词只写一次：文件名带拼音哈希，改词自然换文件。
     */
    static String keywordsFile(Context c, String word) {
        if (c == null || word == null) return null;
        String w = word.trim();
        if (w.isEmpty()) return null;
        synchronized (VoiceWords.class) {
            try {
                if (tokens == null) tokens = readTokens(c);
                if (pinyin == null) pinyin = readPinyin(c);
                if (tokens.isEmpty() || pinyin.isEmpty()) return null;

                StringBuilder line = new StringBuilder();
                for (int i = 0; i < w.length(); i++) {
                    char ch = w.charAt(i);
                    if (ch == ' ' || ch == '\t') continue;
                    String py = pinyin.get(ch);
                    if (py == null) {
                        FileLog.append("VOICE 唤醒词「" + w + "」里的「" + ch + "」没有拼音，退回默认");
                        return null;
                    }
                    if (!appendSyllable(line, py)) {
                        FileLog.append("VOICE 唤醒词「" + w + "」的「" + py + "」切不出音素，退回默认");
                        return null;
                    }
                }
                if (line.length() == 0) return null;

                File dir = new File(c.getFilesDir(), "kws");
                if (!dir.exists() && !dir.mkdirs()) return null;
                File f = new File(dir, "keywords_" + Integer.toHexString(w.hashCode()) + ".txt");
                String text = line.toString().trim() + " @" + w + "\n";
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    OutputStreamWriter ow = new OutputStreamWriter(fo, StandardCharsets.UTF_8);
                    ow.write(text);
                    ow.flush();
                }
                FileLog.append("VOICE 唤醒词「" + w + "」→ " + text.trim());
                return f.getAbsolutePath();
            } catch (Throwable t) {
                FileLog.append("VOICE 生成唤醒词失败 " + t);
                return null;
            }
        }
    }

    /**
     * 一个音节切成模型音素：整音节就是一个 token 就直接用（ān / èr / ń），
     * 否则按「声母（最长 3）+ 韵母」切。
     */
    private static boolean appendSyllable(StringBuilder sb, String py) {
        if (tokens.contains(py)) {
            sb.append(py).append(' ');
            return true;
        }
        for (int cut = Math.min(3, py.length() - 1); cut >= 1; cut--) {
            String ini = py.substring(0, cut);
            String fin = py.substring(cut);
            if (tokens.contains(ini) && tokens.contains(fin)) {
                sb.append(ini).append(' ').append(fin).append(' ');
                return true;
            }
        }
        return false;
    }

    /** 音素表：tokens.txt 每行「token id」 */
    private static Set<String> readTokens(Context c) throws Exception {
        Set<String> set = new HashSet<>();
        try (InputStream in = c.getAssets().open("kws/tokens.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.lastIndexOf(' ');
                String t = (sp > 0 ? line.substring(0, sp) : line).trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        return set;
    }

    /** 汉字 → 带声调拼音（构建时生成，~200KB，一次读进内存） */
    private static Map<Character, String> readPinyin(Context c) throws Exception {
        Map<Character, String> map = new HashMap<>(32768);
        try (InputStream in = c.getAssets().open("kws/pinyin.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp != 1) continue;
                map.put(line.charAt(0), line.substring(2).trim());
            }
        }
        return map;
    }
}
