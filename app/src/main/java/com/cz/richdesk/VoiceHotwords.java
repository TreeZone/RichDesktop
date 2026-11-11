package com.cz.richdesk;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把本机应用名做成「热词表」喂给识别器。
 *
 * 为什么需要它：我们用的是 streaming-zipformer 中文模型（词表 5539 个 token，
 * 其中 5508 个是单汉字），它对「酷狗音乐」「哔哩哔哩」这类专有名词没概念，
 * 而恰恰是这个桌面最常说的词。sherpa-onnx 支持解码时做上下文偏置
 * （hotwords + modeling_unit=cjkchar），把这些词直接灌进去，
 * 专有名词的识别率提升是最直接的一档。
 *
 * 两个容易踩死的点，都在下面代码里处理了：
 *
 *  1) **格式**：cjkchar 模式下每个汉字都是一个 token，所以热词必须**用空格拆开**
 *     写成「酷 狗 音 乐 :1.0」。写成连着的「酷狗音乐」不会报错，但也完全不生效 ——
 *     这是最坑的地方（静默无效，不给你任何提示）。
 *  2) **词表校验**：热词里只要有一个字不在 tokens.txt 里，sherpa 加载模型时就会
 *     直接失败 —— 而语音是整条链路，识别器起不来等于功能全废。
 *     所以生成前先把词表读出来，含生僻字的应用名直接跳过。
 *
 * 英文应用名（YouTube / Spotify 之类）**进不了热词表**：模型词表里一个英文字母都没有，
 * 塞进去只会让加载失败。那类名字靠 VoiceAliases 的谐音别名兜（见那里的说明）。
 */
final class VoiceHotwords {

    private static final String TAG = "PipDesk";
    /** 热词权重。统一 1.0，真正的强度由 recognizer 的 hotwords_score 控制 */
    private static final String BOOST = "1.0";
    /** 太短的词（单字）偏置意义不大，还会带歪普通句子 */
    private static final int MIN_LEN = 2;

    /** 词表缓存：5500 字的集合，生成一次就够，避免每次加载模型都重读 assets */
    private static Set<Character> vocab;
    /** 上次生成的内容，用来判断"要不要重写文件" */
    private static String lastBody;

    private VoiceHotwords() { }

    /**
     * 生成 / 复用热词文件，返回绝对路径；无法生成（没有可用词）返回 null。
     *
     * @param names 可启动应用的名字（语音能认的全部范围）
     */
    static synchronized String ensure(Context ctx, List<String> names) {
        if (ctx == null || names == null || names.isEmpty()) return null;
        Set<Character> v = vocab(ctx);
        if (v.isEmpty()) {
            FileLog.append("VOICE 热词：读不到 tokens.txt，本次不加偏置");
            return null;
        }

        StringBuilder b = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int skipped = 0;
        for (String raw : names) {
            String w = clean(raw);
            if (w.length() < MIN_LEN) continue;
            if (!seen.add(w)) continue;
            if (!allKnown(w, v)) {
                skipped++;
                continue;   // 含词表里没有的字：塞进去会让模型加载失败
            }
            // cjkchar：逐字用空格分开（写成连串不会生效，且不报错）
            for (int i = 0; i < w.length(); i++) {
                if (i > 0) b.append(' ');
                b.append(w.charAt(i));
            }
            b.append(" :").append(BOOST).append('\n');
        }
        if (b.length() == 0) {
            FileLog.append("VOICE 热词：没有可用词（" + names.size() + " 个名字全被跳过）");
            return null;
        }

        String body = b.toString();
        if (body.equals(lastBody)) return path(ctx);   // 没变就别动文件
        lastBody = body;

        File dir = new File(ctx.getFilesDir(), "voice");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File out = new File(dir, "hotwords.txt");
        try {
            FileOutputStream fo = new FileOutputStream(out);
            fo.write(body.getBytes("UTF-8"));
            fo.close();
            FileLog.append("VOICE 热词表已生成：" + seen.size() + " 条"
                    + (skipped > 0 ? "，跳过 " + skipped + " 条（含词表外的字）" : "")
                    + " → " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            FileLog.append("VOICE 热词表写入失败：" + t);
            return null;
        }
    }

    /** 词表变了（重装应用等）时调用，下次 ensure 会重写文件 */
    static synchronized void invalidate() {
        lastBody = null;
    }

    private static String path(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "voice"), "hotwords.txt").getAbsolutePath();
    }

    /** 去掉空白和标点，只留"能被模型读出来"的字符 */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20) continue;
            if (c == '。' || c == '，' || c == '、' || c == '！' || c == '？'
                    || c == '：' || c == '；' || c == '·' || c == '-' || c == '_'
                    || c == '.' || c == ',' || c == '!' || c == '?' || c == ':'
                    || c == '(' || c == ')' || c == '（' || c == '）'
                    || c == '[' || c == ']' || c == '【' || c == '】'
                    || c == '+' || c == '&' || c == '\'' || c == '"') continue;
            b.append(c);
        }
        return b.toString();
    }

    private static boolean allKnown(String w, Set<Character> v) {
        for (int i = 0; i < w.length(); i++) {
            if (!v.contains(w.charAt(i))) return false;
        }
        return true;
    }

    /** 读 assets/asr/tokens.txt 里的单字词表（第一列是 token） */
    private static synchronized Set<Character> vocab(Context ctx) {
        if (vocab != null) return vocab;
        Set<Character> set = new HashSet<>(6000);
        InputStream in = null;
        try {
            in = ctx.getAssets().open("asr/tokens.txt");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                String tok = sp > 0 ? line.substring(0, sp) : line;
                if (tok.length() == 1) {
                    char c = tok.charAt(0);
                    // 只收汉字：`<blk>` 之类的特殊符号不进集合
                    if (c >= 0x4E00 && c <= 0x9FFF) set.add(c);
                }
            }
            r.close();
        } catch (Throwable t) {
            FileLog.append("VOICE 读 tokens.txt 失败：" + t);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
        vocab = set;
        return set;
    }

    /** 诊断页用：热词表当前内容（前 N 行） */
    static String dump(Context ctx) {
        if (ctx == null) return "(无)";
        File f = new File(new File(ctx.getFilesDir(), "voice"), "hotwords.txt");
        if (!f.isFile()) return "(还没生成)";
        List<String> lines = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                lines.add(line);
            }
            r.close();
        } catch (Throwable t) {
            return "(读取失败 " + t + ")";
        }
        StringBuilder b = new StringBuilder();
        b.append(lines.size()).append(" 条\n");
        for (int i = 0; i < lines.size() && i < 20; i++) {
            b.append(lines.get(i)).append('\n');
        }
        if (lines.size() > 20) b.append("…\n");
        return b.toString();
    }

    /** 日志里用的简短描述 */
    static String summary(Context ctx) {
        return String.format(Locale.US, "hotwords=%s", dump(ctx).split("\n")[0]);
    }
}
