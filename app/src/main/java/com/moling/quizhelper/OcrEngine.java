package com.moling.quizhelper;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 本地离线 OCR（ML Kit 中文识别，模型内置在 APK 里，不需要 Google Play 服务）。 */
public final class OcrEngine {

    private OcrEngine() {
    }

    public static class Line {
        public String text;
        public Rect rect;
        public Line(String t, Rect r) { text = t; rect = r; }
    }

    public static class Result {
        public String text = "";
        public List<Line> lines = new ArrayList<>();
    }

    private static volatile com.google.mlkit.vision.text.TextRecognizer recognizer;

    private static com.google.mlkit.vision.text.TextRecognizer get() {
        if (recognizer == null) {
            synchronized (OcrEngine.class) {
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(
                            new ChineseTextRecognizerOptions.Builder().build());
                }
            }
        }
        return recognizer;
    }

    /**
     * @param topPct    顶部裁掉的百分比（去掉状态栏/标题栏）
     * @param bottomPct 底部裁掉的百分比
     * 返回的 rect 已经换算回整屏坐标系，方便直接用坐标点按。
     */
    public static Result recognize(Bitmap full, int topPct, int bottomPct) throws Exception {
        int h = full.getHeight(), w = full.getWidth();
        int top = Math.max(0, Math.min(h - 1, h * topPct / 100));
        int bottom = Math.max(top + 1, h - h * bottomPct / 100);
        Bitmap use = full;
        if (top > 0 || bottom < h) {
            use = Bitmap.createBitmap(full, 0, top, w, bottom - top);
        }

        Text text = Tasks.await(get().process(InputImage.fromBitmap(use, 0)), 60, TimeUnit.SECONDS);

        Result res = new Result();
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String t = line.getText();
                if (t == null || t.trim().isEmpty()) continue;
                Rect r = line.getBoundingBox();
                if (r != null && use != full) r.offset(0, top);
                res.lines.add(new Line(t.trim(), r));
                sb.append(t.trim()).append('\n');
            }
        }
        res.text = sb.toString().trim();
        if (use != full) use.recycle();
        return res;
    }

    /** 找“A.”“A、”“A ”这样的选项行，返回它在整屏上的矩形。 */
    public static Rect findLabel(List<Line> lines, String label) {
        if (lines == null) return null;
        // 先按「有分隔符」严格匹配，找不到再放宽。严格的那遍优先，避免误伤。
        for (Line l : lines) {
            if (l.rect == null) continue;
            if (matchesLabel(l.text, label)) return l.rect;
        }
        // 放宽匹配时取最短的一条：选项总是比题干短。
        Rect best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Line l : lines) {
            if (l.rect == null) continue;
            if (matchesLabelLoose(l.text, label) && l.text.trim().length() < bestLen) {
                best = l.rect;
                bestLen = l.text.trim().length();
            }
        }
        return best;
    }

    /** 标签前面的装饰：「（A）」「【A】」「Ⓐ」前面的那个括号。 */
    private static final String OPEN_DECO = "（([【{｛〈《「『\"'`·•";
    /** 标签后面的分隔符：「A.」「A、」「A）」里的那个标点。 */
    private static final String CLOSE_DECO = "）)]】}｝〉》」』、,，.。:：;；·•|/-—　";

    /** 选项文字开头的解析结果。 */
    public static final class Head {
        /** 认出来的标签（已归一成大写 A-Z）；0 表示这压根不像选项。 */
        public char label;
        /** 标签和内容之间有括号/标点/空格 —— 这种可信度最高。 */
        public boolean decorated;
        /** 标签之后剩下的内容。 */
        public String rest = "";
    }

    /**
     * 从一段文字开头抠出选项标签。
     * <p>
     * 要对付的花样比想象中多：
     * <pre>
     *   A克局跑分      Ａ克局跑分（全角）     （A）克局跑分
     *   A.克局跑分     A、克局跑分           【A】克局跑分
     *   Ⓐ克局跑分      ①克局跑分             ❶克局跑分
     * </pre>
     * 一律先剥掉前缀装饰，再把各种「1」的写法归一成字母，最后把剩下的交给
     * 调用方按内容判断到底是不是选项。
     */
    public static Head parseHead(String s) {
        Head h = new Head();
        if (s == null) return h;
        int n = s.length(), i = 0;
        while (i < n && (Character.isWhitespace(s.charAt(i)) || OPEN_DECO.indexOf(s.charAt(i)) >= 0)) i++;
        if (i >= n) return h;

        int cp = s.codePointAt(i);
        char lab = labelOf(cp);
        if (lab == 0) return h;
        i += Character.charCount(cp);

        // 标签和内容之间夹了东西（括号、点号、空格）→ 高可信
        if (i < n && (Character.isWhitespace(s.charAt(i)) || CLOSE_DECO.indexOf(s.charAt(i)) >= 0)) {
            h.decorated = true;
        }
        while (i < n && CLOSE_DECO.indexOf(s.charAt(i)) >= 0) i++;
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;

        h.label = lab;
        h.rest = s.substring(i);
        return h;
    }

    /** 把各种「第几个选项」的写法归一成 A-Z；认不出返回 0。标签集合取用户设置里的那份。 */
    public static char labelOf(int cp) {
        String custom;
        try {
            custom = AnswerParser.allowedLetters(Prefs.labels());
        } catch (Throwable t) {
            custom = null;          // 设置还没初始化：只认内置那几套写法
        }
        return labelOf(cp, custom);
    }

    /**
     * 同 {@link #labelOf(int)}，但标签集合由调用方给 —— 解析自检跑的是别的标签集，
     * 不能拿用户设置里的那份去比。
     */
    public static char labelOf(int cp, String allowed) {
        if (cp >= 'A' && cp <= 'Z') return (char) cp;
        if (cp >= 'a' && cp <= 'z') return (char) (cp - 'a' + 'A');
        if (cp >= 0xFF21 && cp <= 0xFF3A) return (char) (cp - 0xFF21 + 'A');   // Ａ-Ｚ 全角
        if (cp >= 0xFF41 && cp <= 0xFF5A) return (char) (cp - 0xFF41 + 'A');   // ａ-ｚ 全角
        if (cp >= 0x24B6 && cp <= 0x24CF) return (char) (cp - 0x24B6 + 'A');   // Ⓐ-Ⓩ
        if (cp >= 0x2460 && cp <= 0x2473) return (char) (cp - 0x2460 + 'A');   // ①-⑳
        if (cp >= 0x2474 && cp <= 0x2487) return (char) (cp - 0x2474 + 'A');   // ⑴-⒇
        if (cp >= 0x2488 && cp <= 0x249B) return (char) (cp - 0x2488 + 'A');   // ⒈-⒛
        if (cp >= 0x2776 && cp <= 0x277F) return (char) (cp - 0x2776 + 'A');   // ❶-❿
        if (cp >= 0x2780 && cp <= 0x2789) return (char) (cp - 0x2780 + 'A');   // ➀-➉
        if (cp >= 0x278A && cp <= 0x2793) return (char) (cp - 0x278A + 'A');   // ➊-➓
        // 页面用的是别的一套符号（日语卷子常写 ア/イ/ウ/エ，也可能用 α/β 或别的）：
        // 用户在「选项标签」里填了什么，那个字本身就当标签用，一个字一档。
        if (allowed != null && allowed.indexOf(cp) >= 0) return (char) cp;
        return 0;
    }

    /**
     * 严格匹配：标签和内容之间有括号、点号或空格（“A.”“A、”“（A）”“Ａ. ”）。
     * <p>
     * 标签后面什么都没有（节点文字就是「A」）也算 —— 有的页面把标签和选项正文
     * 拆成两个节点，那种情况下这个纯标签节点就是我们要找的。
     */
    public static boolean matchesLabel(String s, String label) {
        if (s == null || label == null || label.isEmpty()) return false;
        Head h = parseHead(s);
        if (h.label != Character.toUpperCase(label.charAt(0))) return false;
        return h.decorated || h.rest.isEmpty();
    }

    /**
     * 放宽匹配：标签直接黏着内容，中间没有任何分隔符 ——
     * 「A克局跑分」「B1240nm」「CAsk」「DSkade」都是。
     * <p>
     * 难点在于分清「这个节点是选项」还是「这个节点是一串标签」。判据用**大小写**：
     * 选项内容常见英文单词（Ask / Skade / alchemaniac），不会整截都是大写；
     * 而「一串标签」必然是连着的大写字母（BC、AI、ABCD）。汉字和数字当然也算内容。
     */
    public static boolean matchesLabelLoose(String s, String label) {
        if (s == null || label == null || label.isEmpty()) return false;
        if (s.trim().length() > 60) return false;      // 这么长多半是题干，不是选项
        Head h = parseHead(s);
        if (h.label != Character.toUpperCase(label.charAt(0))) return false;
        if (h.decorated || h.rest.isEmpty()) return false;   // 这两类严格匹配已经收走了
        return !isOnlyLabels(h.rest);
    }

    /**
     * 这截字符串是不是「纯粹一串大写标签字母」（"BC"、"AI"、"A B C"）。
     * 注意 Character.isLetter('克') 是 true，所以不能用「是不是字母」判 —— 只认 A-Z。
     */
    private static boolean isOnlyLabels(String rest) {
        if (rest.length() > 4) return false;        // 这么长的基本都是有内容了
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c > 0x7F) return false;             // 汉字等 → 内容
            if (Character.isWhitespace(c)) continue;
            if (CLOSE_DECO.indexOf(c) >= 0) continue;
            if (c < 'A' || c > 'Z') return false;   // 数字/小写/符号 → 内容
        }
        return true;
    }

    public static Rect findKeyword(List<Line> lines, List<String> keywords) {
        if (lines == null) return null;
        for (String kw : keywords) {
            for (Line l : lines) {
                if (l.rect != null && l.text != null && l.text.contains(kw)) return l.rect;
            }
        }
        return null;
    }

    public static List<String> keywordList(String csv) {
        String[] a = Prefs.splitList(csv);
        List<String> out = new ArrayList<>();
        Collections.addAll(out, a);
        return out;
    }
}
