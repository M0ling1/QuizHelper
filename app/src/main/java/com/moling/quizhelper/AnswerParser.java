package com.moling.quizhelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从大模型回复里抠出答案字母 + 解析。容忍 JSON / 纯字母 / 带前缀的写法。 */
public final class AnswerParser {

    private AnswerParser() {
    }

    public static class Parsed {
        public String answer = "";
        public String explanation = "";
        /** 填空题的正文。已经收拾成纯文本（见 {@link PlainText}），非空就说明这题是填空。 */
        public String fill = "";
        /**
         * 填空题一个空一条答案，顺序就是**空在题面上的先后顺序**。
         * <p>
         * 一条大题里有两个以上空时，模型按约定回 {@code blanks:["第一空","第二空"]}，
         * 程序照着顺序把它们打进屏幕上空着的输入框。只有一个空、或者模型没给
         * blanks 数组时，这里就放一条 —— 调用方不用分情况处理。
         */
        public List<String> blanks = new ArrayList<>();
        /** 模型标明的题号（items 里的 n），1 起；没有就是 0。一屏多题时用来对位。 */
        public int n = 0;
        /**
         * 模型**照抄**回来的题干开头（items 里的 q）。一屏多题时拿它跟屏幕上的题干对，
         * 比题号靠谱得多 —— 见 {@code matchByStem}。没抄就是空串。
         */
        public String q = "";
        /** 模型说这题是不是多选（items 里的 multi）。null 表示它没说。 */
        public Boolean multi = null;
        /**
         * 一屏多题时，按屏幕从上到下的顺序一题一条。只有一道题时这里是空的，
         * 答案照旧在 {@link #answer} 里 —— 老调用方不用改。
         */
        public List<Parsed> items = new ArrayList<>();
        /** 模型原话里的字母顺序（没排序过）。单选题截断时按这个取第一个。 */
        String rawAnswer = "";
    }

    private static final Pattern P_ANSWER_LINE = Pattern.compile(
            "(?:正确答案|答案|应选|answer)\\s*(?:是|为|：|:)?\\s*\\**\\s*([A-Za-z][A-Za-z\\s,，、/]{0,20})",
            Pattern.CASE_INSENSITIVE);

    /** 题面上写着「单选」的说法。任一条命中就算这题只有一个正确选项。 */
    private static final String[] SINGLE_MARKS = {
            "单选", "单项选择", "唯一正确", "只有一个正确", "只有一个答案", "仅一个正确", "最佳选项"};

    /** 题面上写着「多选」的说法。 */
    private static final String[] MULTI_MARKS = {
            "多选", "多项选择", "不定项", "两个或两个以上", "两个以上", "选项多于一个", "少选"};

    public static Parsed parse(String content, String labelsCsv) {
        return parse(content, labelsCsv, null);
    }

    /**
     * @param screenText 这一屏读到的原文，用来判断题面上写的是「单选」还是「多选」。
     *                   传 null 就不判 —— 模型自己标的 multi 字段还算数。
     */
    public static Parsed parse(String content, String labelsCsv, String screenText) {
        String allowed = allowedLetters(labelsCsv);
        Parsed p;
        List<Parsed> items = parseItems(content, allowed);
        if (!items.isEmpty()) {
            // 一屏多题：顶层字段取第一条，老调用方（播报、日志、填空题）照旧能读到答案
            Parsed first = items.get(0);
            p = new Parsed();
            p.answer = first.answer;
            p.explanation = first.explanation;
            p.fill = first.fill;
            p.n = first.n;
            p.q = first.q;
            p.multi = first.multi;
            p.rawAnswer = first.rawAnswer;
            p.blanks = first.blanks;
            p.items = items;
        } else {
            p = parseOne(content, labelsCsv);
        }
        applyChoiceLimit(p, pageChoiceMode(screenText), allowed);
        dropFillOnChoicePage(p, screenText, allowed);
        return p;
    }

    /**
     * 这屏明明是选择题，模型却当成了填空题 —— 把那段填空正文丢掉。
     * <p>
     * 用户报的场景：取词只拿到题干、或者只拿到「A」「B」几个光杆标签，模型就认定
     * 「没有选项 = 填空题」，写了一整段正文进 fill。程序那边一见 fill 就走填空题那条路
     * （正文塞剪贴板、暂停回答），一道好好的选择题就这么被绕过去了。
     * 屏幕上有选项标签就说明这是选择题，答案该在 answer 的字母里。
     * <p>
     * 只丢**没给出字母答案**（answer 空或 "?"）的那些条目：模型既给了字母又给了正文时，
     * 说明它看懂了题，那多半是「给了候选选项的填空题」，正文留着。
     * 页面上一个选项标签都没有（真填空题）时这里也不动。
     */
    private static void dropFillOnChoicePage(Parsed p, String screenText, String allowed) {
        if (!looksLikeChoicePage(screenText, allowed)) return;
        // 一屏混排（填空 + 选择）：只要有一条给了字母答案，就说明模型把页面看懂了，
        // 别的条目没给字母很可能就是**真的填空**。这时候丢 fill 会把填空题整条丢掉，
        // 而「把选择题误当填空」的代价现在只是往里填字 —— 页面上没有输入框就什么也
        // 不会发生，所以这里宁可变宽。
        if (p.items.size() > 1) {
            boolean anyLetters = false;
            for (Parsed it : p.items) {
                if (!missingAnswer(it)) anyLetters = true;
            }
            if (anyLetters) return;
        }
        if (p.items.isEmpty()) {
            if (missingAnswer(p)) {
                p.fill = "";
                p.blanks.clear();
            }
            return;
        }
        for (Parsed it : p.items) {
            if (missingAnswer(it)) {
                it.fill = "";
                it.blanks.clear();
            }
        }
        if (missingAnswer(p.items.get(0))) {              // 顶层 fill 跟着第一条走
            p.fill = "";
            p.blanks.clear();
        }
    }

    private static boolean missingAnswer(Parsed it) {
        String a = it.answer == null ? "" : it.answer.trim();
        return a.isEmpty() || "?".equals(a);
    }

    /**
     * 屏幕文字里有没有印着选项标签：「A.」「A、」「①」「Ａ．」「ア．」。
     * <p>
     * 判得松一点没关系（少丢一次 fill 而已），但不能被英文句子骗了 ——
     * 「A study shows…」这种首字母后头跟空格的不算，得是标签后面直接跟分隔符、
     * 或者整行就是个光杆标签（学习通读出来就是一行一个「A」）。
     */
    private static boolean looksLikeChoicePage(String screenText, String allowed) {
        if (screenText == null) return false;
        for (String line : screenText.split("\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            char c0 = t.charAt(0);
            if (labelChar(c0, allowed) == 0) continue;
            String rest = t.substring(1).trim();
            boolean sep = !rest.isEmpty() && ".、．)）:：,，]".indexOf(rest.charAt(0)) >= 0;
            // 光杆「A」「①」「A、」：标签后面不能再有字。只判长度的话，填进空里的
            // 短答案（「H2O」）会被当成一条光杆选项 —— 下一轮重读这屏时整题被误判成
            // 选择题，填空答案反而被丢掉。
            boolean bare = t.length() <= 3 && normStem(rest).isEmpty();
            boolean wide = c0 > 0x2000;                  // 圈码/全角/假名本身就是标签
            if (sep || bare || (wide && !rest.isEmpty())) return true;
        }
        return false;
    }

    /**
     * 题面上写的是单选还是多选。1 = 单选，-1 = 多选/不定项，0 = 没写、或者两种都出现过
     * （一屏多题里既有单选又有多选，那种只能一题一题看模型标的 multi）。
     */
    public static int pageChoiceMode(String screenText) {
        if (screenText == null || screenText.isEmpty()) return 0;
        boolean single = false, multi = false;
        for (String k : SINGLE_MARKS) {
            if (screenText.contains(k)) {
                single = true;
                break;
            }
        }
        for (String k : MULTI_MARKS) {
            if (screenText.contains(k)) {
                multi = true;
                break;
            }
        }
        if (single && !multi) return 1;
        if (multi && !single) return -1;
        return 0;
    }

    /**
     * 单选题只留一个字母。
     * <p>
     * 模型经常不听话：题面明明写着「单选」，它还是回「ACD」。三个字母照点下去，
     * 单选题每点一次就把上一个替换掉，最后留在屏幕上的就是最后点的那一个 ——
     * 用户看到的就是「ACD 来回选一次，最终随机」。
     */
    private static void applyChoiceLimit(Parsed p, int pageMode, String allowed) {
        if (p.items.isEmpty()) {
            clampOne(p, pageMode, allowed);
            return;
        }
        for (Parsed it : p.items) clampOne(it, pageMode, allowed);
        Parsed first = p.items.get(0);
        p.answer = first.answer;            // 顶层跟着第一条走
        if (p.fill.isEmpty()) p.fill = first.fill;
    }

    private static void clampOne(Parsed it, int pageMode, String allowed) {
        if (it.answer.length() <= 1) return;
        // 题面写着单选 → 单选；题面没写 → 只有模型**明确**说 multi=true 才当多选。
        // 拿不准就往单选靠：单选题上多给字母是把选项来回点一遍、最后留哪个全看运气
        // （用户报的就是这个），而多选题少留一个字母只是漏选，害处小得多。
        boolean single = pageMode == 1 || (pageMode == 0 && !Boolean.TRUE.equals(it.multi));
        if (!single) return;
        // 取模型最先说的那个字母。它排过序了（normalize 按标签次序排），
        // 所以要拿没排序的原话来找第一个 —— 「DCA」该留 D，不该留 A。
        String raw = it.rawAnswer;
        String keep = null;
        if (raw.length() <= 8) {
            for (int i = 0; i < raw.length(); i++) {
                char ch = labelChar(raw.charAt(i), allowed);
                if (ch != 0 && allowed.indexOf(ch) >= 0) {
                    keep = String.valueOf(ch);
                    break;
                }
            }
        }
        if (keep == null) keep = it.answer.substring(0, 1);
        it.answer = keep;
    }

    /**
     * 一屏多题时模型给的 items 数组。没有这个数组就返回空（走了老路）。
     * <p>
     * 认几种字段名，取**最后一个**能解析出数组的 JSON —— 思维链里可能先写一版再改口。
     * 空条目也留着当占位：第 2 条空了但第 3 条有答案，位置不能往前挪，
     * 否则答案就会点到第 2 题上。
     */
    private static List<Parsed> parseItems(String content, String allowed) {
        if (content == null || content.isEmpty()) return new ArrayList<>();
        String c = content.replace("```json", "```").replace("```JSON", "```")
                .replace("```", " ").trim();
        List<int[]> spans = jsonSpans(c);
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] sp = spans.get(i);
            try {
                JSONObject o = new JSONObject(c.substring(sp[0], sp[1] + 1));
                org.json.JSONArray arr = itemsArray(o);
                if (arr == null || arr.length() == 0) continue;
                List<Parsed> out = new ArrayList<>();
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject e = arr.optJSONObject(k);
                    Parsed it = new Parsed();
                    if (e != null) {
                        String raw = firstOf(e, "answer", "ans", "choice", "selected", "key");
                        it.rawAnswer = raw;
                        it.answer = normalize(raw, allowed);
                        it.fill = PlainText.clean(fillField(e));
                        it.blanks = blanksOf(e);
                        if (!it.blanks.isEmpty()) {
                            // blanks 才是逐空答案；fill 只用来显示/复制，按空编号拼起来
                            it.fill = joinBlanks(it.blanks);
                        } else if (!it.fill.isEmpty()) {
                            it.blanks.add(it.fill);
                        }
                        String x = firstOf(e, "explanation", "explain", "reason", "why");
                        it.explanation = x.trim();
                        it.n = numberField(e);
                        // 题干照抄（用来对位）：只认它自己那几个键，别把整段题干塞进 explanation
                        it.q = firstOf(e, "q", "stem", "stemText", "标题").trim();
                        String m = firstOf(e, "multi", "multiple", "isMulti", "multiChoice");
                        if (!m.trim().isEmpty()) {
                            String v = m.trim().toLowerCase();
                            it.multi = v.startsWith("t") || v.startsWith("y")
                                    || v.equals("1") || v.contains("多") || v.startsWith("on");
                        }
                        if (it.explanation.isEmpty() && it.answer.isEmpty() && it.fill.isEmpty()) {
                            // 真的一条空：留着占位，别让后面的答案往前串
                            it.explanation = "";
                        }
                    }
                    out.add(it);
                }
                return out;
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>();
    }

    /** 从对象里取 items / answers / list 之类的数组。 */
    private static org.json.JSONArray itemsArray(JSONObject o) {
        String[] keys = {"items", "answers", "list", "results", "questions", "data"};
        for (String k : keys) {
            org.json.JSONArray a = o.optJSONArray(k);
            if (a != null && a.length() > 0) return a;
        }
        return null;
    }

    /** 题号：n / num / no / index / q / id。都没有就返回 0（按顺序排）。 */
    private static int numberField(JSONObject o) {
        String[] keys = {"n", "num", "no", "index", "q", "id", "number"};
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (v.isEmpty()) continue;
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < v.length(); i++) {
                char ch = v.charAt(i);
                if (ch >= '0' && ch <= '9') digits.append(ch);
                else break;
            }
            if (digits.length() > 0 && digits.length() <= 2) {
                try {
                    return Integer.parseInt(digits.toString());
                } catch (Throwable ignored) {
                }
            }
        }
        return 0;
    }

    private static String firstOf(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    private static Parsed parseOne(String content, String labelsCsv) {
        Parsed p = new Parsed();
        if (content == null) content = "";
        String c = content.trim();
        String allowed = allowedLetters(labelsCsv);

        // 去掉 markdown 代码块
        c = c.replace("```json", "```").replace("```JSON", "```").replace("```", " ").trim();

        // 1) JSON。模型可能吐出好多个 {} —— 尤其是 deepseek-reasoner 的思维链，
        //    它会在推理过程中先写一版答案、再推翻重写。所以从**最后一个**开始试。
        List<int[]> spans = jsonSpans(c);
        // 循环条件是「还没解析出东西」：填空题的 answer 本来就是空的，只看 answer 会
        // 一直往前翻，把更早那版 JSON 的 fill 又盖回来。
        for (int i = spans.size() - 1; i >= 0 && p.answer.isEmpty() && p.fill.isEmpty(); i--) {
            int[] sp = spans.get(i);
            try {
                JSONObject o = new JSONObject(c.substring(sp[0], sp[1] + 1));
                String a = o.optString("answer", "");
                String raw = fillField(o);
                List<String> bl = blanksOf(o);
                // 填空题本来就该是 answer 空、fill/blanks 有内容（提示词里就是这么约定的），
                // 所以只要三样都空才不算数 —— 早先这里只认 answer，于是模型老老实实
                // 按约定回的 {"answer":"","fill":"正文"} 被整段丢掉，正文没了、反倒被
                // 后面那两步猜出一串不着边的字母点下去。
                if (a.trim().isEmpty() && raw.trim().isEmpty() && bl.isEmpty()) continue;
                String x = o.optString("explanation", "");
                if (x.isEmpty()) x = o.optString("explain", "");
                if (x.isEmpty()) x = o.optString("reason", "");
                String norm = normalize(a, allowed);
                // 填空题可能压根没有字母答案（answer 是空的），只有 fill/blanks 有内容，
                // 所以不能像以前那样「没字母就当这版 JSON 不算数」。
                if (norm.isEmpty() && raw.isEmpty() && bl.isEmpty()) continue;
                p.answer = norm;
                p.rawAnswer = a;
                p.fill = PlainText.clean(raw);
                p.blanks = bl;
                if (!p.blanks.isEmpty()) {
                    p.fill = joinBlanks(p.blanks);
                } else if (!p.fill.isEmpty()) {
                    p.blanks.add(p.fill);
                }
                p.explanation = x.trim();
            } catch (Exception ignored) {
            }
        }

        // 2) “答案：AC” 这类行。同样取**最后**一次出现：思维链里常先猜一个再改口。
        //    填空题（fill 有内容）就别再猜字母了 —— 猜出来的字母会被当成选项点下去。
        if (p.answer.isEmpty() && p.fill.isEmpty()) {
            Matcher m = P_ANSWER_LINE.matcher(c);
            String last = null;
            while (m.find()) {
                if (m.group(1) != null) last = m.group(1);
            }
            if (last != null) {
                p.answer = normalize(last, allowed);
                p.rawAnswer = last;
            }
        }

        // 3) 兜底：挑大写字母。用**尾部**两百字 —— 思维链的结论在最后，
        //    开头那段「选项A是…」会挑出完全不相关的字母。
        if (p.answer.isEmpty() && p.fill.isEmpty()) {
            p.answer = pickUpperLetters(c, allowed);
            p.rawAnswer = p.answer;
        }

        if (p.explanation.isEmpty()) {
            p.explanation = firstMeaningfulLine(c, p.answer);
        }
        if (p.explanation.length() > 120) {
            p.explanation = p.explanation.substring(0, 120) + "…";
        }
        return p;
    }

    /**
     * 从 JSON 里取填空题正文。字段名认几种常见写法 —— 模型不一定老老实实叫 fill
     * （有时写成 fillIn / blank / answer_text，尤其是换了提示词或换模型之后）。
     */
    private static String fillField(JSONObject o) {
        String[] keys = {"fill", "fillIn", "fill_in", "blank", "blankText", "answer_text"};
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    /**
     * 多空题的逐空答案：{@code blanks:["甲","乙"]}（也认 blanksText / fillBlanks 这些写法）。
     * 每个空都收拾成纯文本；空条目保留占位 —— 第 1 空没答、第 2 空有答案时，
     * 位置不能往前挪，否则第二空的答案会填进第一个框。
     */
    private static List<String> blanksOf(JSONObject o) {
        List<String> out = new ArrayList<>();
        String[] keys = {"blanks", "fillBlanks", "blankList", "answersText"};
        for (String k : keys) {
            org.json.JSONArray arr = o.optJSONArray(k);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                out.add(PlainText.clean(v));
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    /** 逐空答案拼成一段给人看的正文：一个空就是原文，多个空按 1. 2. 分条。 */
    private static String joinBlanks(List<String> blanks) {
        if (blanks.size() == 1) return blanks.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blanks.size(); i++) {
            if (blanks.get(i).isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(i + 1).append(". ").append(blanks.get(i));
        }
        return sb.toString();
    }

    /** 只保留标签集合内的字母，去重、按标签顺序排序。 */
    public static String normalize(String raw, String allowed) {
        if (raw == null) return "";
        StringBuilder hit = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = labelChar(raw.charAt(i), allowed);
            if (ch != 0 && allowed.indexOf(ch) >= 0 && hit.indexOf(String.valueOf(ch)) < 0) {
                hit.append(ch);
            }
            if (hit.length() >= 8) break;
        }
        return sortByAllowed(hit.toString(), allowed);
    }

    /**
     * 一个字符能不能当标签，能就归一成标签本身。
     * <p>
     * 模型是照着**页面上印的那套符号**回答的：页面写 ①②③ 它就回「①」，
     * 写全角 ＡＢ 就回「Ａ」，日语卷子写 ア/イ 就回假名。这里统统折算成内部的那一档，
     * 折算不出来（普通汉字、普通符号）返回 0 —— 免得把题干里的字当成答案。
     */
    private static char labelChar(char c, String allowed) {
        char lab = OcrEngine.labelOf(c, allowed);
        if (lab >= 'a' && lab <= 'z') lab = (char) (lab - 'a' + 'A');
        return lab;
    }

    private static String sortByAllowed(String letters, String allowed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < allowed.length(); i++) {
            char ch = allowed.charAt(i);
            if (letters.indexOf(ch) >= 0) sb.append(ch);
        }
        return sb.toString();
    }

    /** 只在原文是大写的字母里挑，避免把英文单词拆碎。 */
    private static String pickUpperLetters(String c, String allowed) {
        // 用尾部：模型（尤其是 reasoner 的思维链）把结论放在最后
        String head = c.length() > 200 ? c.substring(c.length() - 200) : c;
        StringBuilder hit = new StringBuilder();
        for (int i = 0; i < head.length(); i++) {
            char ch = head.charAt(i);
            if (allowed.indexOf(ch) >= 0 && hit.indexOf(String.valueOf(ch)) < 0) {
                hit.append(ch);
            }
            if (hit.length() >= 8) break;
        }
        return sortByAllowed(hit.toString(), allowed);
    }

    /**
     * 扫出文本里所有成对的 {} 区间。用花括号配平来切，而不是
     * indexOf('{') 配 lastIndexOf('}') —— 后者会把思维链里前后不相关的两段
     * 拼成一个畸形 JSON，直接解析失败。
     */
    private static List<int[]> jsonSpans(String c) {
        List<int[]> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (inStr) {
                if (ch == '\\') i++;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) out.add(new int[]{start, i});
                }
            }
        }
        return out;
    }

    private static String firstMeaningfulLine(String c, String answer) {
        for (String line : c.split("\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.length() <= 2 && answer.contains(t)) continue;
            if (t.startsWith("{") || t.startsWith("[")) continue;
            return t.length() > 120 ? t.substring(0, 120) + "…" : t;
        }
        return "";
    }

    /**
     * 「选项标签」这一栏能填的不止 A-H：日语卷子常用 ア/イ/ウ/エ，中文页面常见 ①②③，
     * 也有用 （１）（２） 的。所以这里不再只认拉丁字母 —— 拉丁字母统一成大写，
     * 别的字（假名、圈码、全角、希腊字母……）原样留着，一个字当一档。
     */
    public static String allowedLetters(String labelsCsv) {
        String[] arr = Prefs.splitList(labelsCsv);
        StringBuilder sb = new StringBuilder();
        for (String a : arr) {
            if (a.isEmpty()) continue;
            char ch = a.charAt(0);
            if (ch >= 'a' && ch <= 'z') ch = (char) (ch - 'a' + 'A');
            if (sb.indexOf(String.valueOf(ch)) < 0) sb.append(ch);
        }
        if (sb.length() == 0) sb.append("ABCDEFGH");
        return sb.toString();
    }

    // ---------------- 一屏多题：答案对到哪一组 ----------------

    /** 「1.」「2、」「3．」这种题号。认到第 6 题就得让组号从它开始数。 */
    private static final Pattern P_QNO = Pattern.compile(
            "(?m)^\\s*(\\d{1,3})\\s*[.、．)）:：]\\s*\\S");

    /**
     * 一组选项至多几条（超了多半是把一整列标签都当成一组了）。和
     * {@code QuizAccessibilityService.MAX_OPTS} 保持一致。
     * <p>
     * 这里没有「至少几条」：分组要和 {@code QuizAccessibilityService.splitByLabelRun}
     * 一模一样，只有一条的段也得留着（下一题只露出一个 A 就是这种情况）；
     * 够不够两条去看 {@code optionGroupVisibility}，那里会按「没露全」处理。
     */
    private static final int MAX_OPTS = 8;

    /**
     * 一屏多题时，**按每条题干上印的题号**把答案对到选项组上。
     * <p>
     * 往下滑过一屏之后，屏幕最上面常常留着上一题的整排选项（A B C D 全在，只是题干
     * 滚出去了）。分组那套照样把这排选项算成一组，可模型看不见题干，**不会**把这道题
     * 写进 items —— 于是模型给的题号（5、6）和组号（1、2）整体差了一位：太阳从哪边
     * 升起那道题的 D 就点到了上一题的 D 上（用户报的「水的化学式本该选 A，却又把 BC
     * 选上了」就是这一类）。题干上印的题号是**两边都看得见**的东西，照它对就不会差这一位。
     * <p>
     * 只在切出来的组数和屏幕上的组数**正好对上**时才认（数不上就返回 null，退回按
     * 位置数的那套）；题干上没印题号的那一组记 0，永远对不上任何题号。
     *
     * @return 长度 = {@code groups}，每组的印刷题号；数不出来返回 null
     */
    public static int[] groupPrintedNos(String screenText, int groups) {
        String[] lines = screenLines(screenText);
        List<List<Integer>> runs = optionRuns(lines, groups);
        if (runs == null) return null;
        int[] out = new int[groups];
        for (int g = 0; g < groups; g++) out[g] = printedNoAbove(lines, runs.get(g).get(0));
        return out;
    }

    /** 拆好行、备用。 */
    private static String[] screenLines(String screenText) {
        return screenText == null ? new String[0] : screenText.split("\\n");
    }

    /**
     * 把屏幕切成一组组选项（每组是行号表）。切出来的组数对不上 {@code groups} 就返回 null。
     * <p>
     * 切法和 {@code QuizAccessibilityService.splitByLabelRun} 必须一模一样，否则
     * 「按题干对组」「按印刷题号对组」两套都会因为组数对不上而整屏作废：
     * <ul>
     *   <li>标签回到第一个、或者往前退（D 后面跟 A）就断一组 —— 一道题里选项不会倒着印；</li>
     *   <li>开头那段「上一题的尾巴」（不是从 A 起头、后面又有从 A 起头的段）先砍掉；</li>
     *   <li>只有一条的段也留着：{@code B C D / 下一题题干 / A} 里那条 A 就是下一题
     *       仅露出来的一行，丢掉它这一屏就成了一组都没有。</li>
     * </ul>
     */
    private static List<List<Integer>> optionRuns(String[] lines, int groups) {
        if (lines.length == 0 || groups <= 0) return null;
        String allowed = allowedLetters(Prefs.labels());
        if (allowed.isEmpty()) return null;
        List<List<Integer>> runs = new ArrayList<>();      // 每组选项占的行号
        List<Integer> cur = new ArrayList<>();
        char prev = 0;
        for (int i = 0; i < lines.length; i++) {
            char lab = optionLineLabel(lines[i], allowed);
            if (lab == 0) continue;
            if (!cur.isEmpty() && labelSequenceBreak(prev, lab, allowed)) {
                if (cur.size() <= MAX_OPTS) runs.add(cur);
                cur = new ArrayList<>();
                prev = 0;
            }
            cur.add(i);
            prev = lab;
        }
        if (!cur.isEmpty() && cur.size() <= MAX_OPTS) runs.add(cur);

        // 开头那段「上一题的尾巴」：第一段不是从第一个标签起头，而后面还有一段是。
        if (runs.size() > 1) {
            char firstLab = allowed.charAt(0);
            if (optionLineLabel(lines[runs.get(0).get(0)], allowed) != firstLab) {
                for (int i = 1; i < runs.size(); i++) {
                    if (optionLineLabel(lines[runs.get(i).get(0)], allowed) == firstLab) {
                        runs = new ArrayList<>(runs.subList(i, runs.size()));
                        break;
                    }
                }
            }
        }
        return runs.size() == groups ? runs : null;
    }

    /**
     * 这个标签相对于上一条，是不是「新的一道题又开始了」 —— 与
     * {@code QuizAccessibilityService.labelSequenceBreak} 同一套判法。
     */
    private static boolean labelSequenceBreak(char prev, char label, String allowed) {
        if (prev == 0) return false;
        if (allowed != null && !allowed.isEmpty() && label == allowed.charAt(0)) {
            return true;                       // 标签回到第一个 = 下一题
        }
        int pi = allowed == null ? -1 : allowed.indexOf(prev);
        int ci = allowed == null ? -1 : allowed.indexOf(label);
        if (pi >= 0 && ci >= 0) return ci <= pi;     // 标签表里的：倒退或重复
        return label <= prev;                        // 表外的（圈码/假名等）按字符序
    }

    /**
     * 每组选项上面最近的那行题干。屏幕上最上面那题的题干常常滚出去了，那一组会是空串。
     */
    private static String[] groupStems(String screenText, int groups) {
        String[] lines = screenLines(screenText);
        List<List<Integer>> runs = optionRuns(lines, groups);
        if (runs == null) return null;
        String[] out = new String[groups];
        for (int g = 0; g < groups; g++) out[g] = stemAbove(lines, runs.get(g).get(0));
        return out;
    }

    /** 这一行是选项行就返回它的标签，不是返回 0。判法和 {@link #looksLikeChoicePage} 一套。 */
    /**
     * 屏幕顶上是不是被切掉了半道题：从头往下找第一条选项行，它要不是标签表的第一个标签
     * 起头（比如一上来就是「C. 12」「D. 13」），那就是上一题滚剩的尾巴 ——
     * {@code QuizAccessibilityService.trimLeadingFragment} 会把这几条丢掉，
     * 可模型看不见题号、常常照样把它数成一道题。
     */
    private static boolean leadingTrimmed(String screenText) {
        if (screenText == null || screenText.isEmpty()) return false;
        String allowed = allowedLetters(Prefs.labels());
        if (allowed.isEmpty()) return false;
        char firstLab = allowed.charAt(0);
        for (String line : screenText.split("\\n")) {
            char lab = optionLineLabel(line, allowed);
            if (lab == 0) continue;              // 题干行，接着往下找
            return lab != firstLab;
        }
        return false;
    }

    /**
     * 模型抄回来的题干，是不是屏幕**最后一组选项下面**那一段 —— 也就是
     * 「下一题的题干露出来了、它的选项还在屏幕外」那道题。
     * <p>
     * 用户报的布局：{@code A B C D} 下面跟着 {@code 2.XXX?}。模型看见下一题的题干，
     * 可能就照着它回了答案；可这一屏只有上一题的选项，按位置硬点就点到上一题上了。
     * 这条判断用来把那种答案拦下来：不点，改成往下滑去看全下一题的选项。
     * 只对「抄了题干、而且这条题干确实对不上任何一组选项」的条目用。
     */
    public static boolean stemBelowOptions(String q, String screenText) {
        String a = normStem(q);
        if (a.length() < 4 || screenText == null || screenText.isEmpty()) return false;
        String allowed = allowedLetters(Prefs.labels());
        if (allowed.isEmpty()) return false;
        String[] lines = screenLines(screenText);
        int lastOpt = -1;
        for (int i = 0; i < lines.length; i++) {
            if (optionLineLabel(lines[i], allowed) != 0) lastOpt = i;
        }
        if (lastOpt < 0 || lastOpt >= lines.length - 1) return false;
        StringBuilder tail = new StringBuilder();
        for (int i = lastOpt + 1; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (tail.length() > 0) tail.append('\n');
            tail.append(t);
        }
        return tail.length() > 0 && sameStem(q, tail.toString());
    }

    private static char optionLineLabel(String line, String allowed) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty()) return 0;
        char c0 = t.charAt(0);
        char lab = labelChar(c0, allowed);
        if (lab == 0) return 0;
        String rest = t.substring(1).trim();
        boolean sep = !rest.isEmpty() && ".、．)）:：,，]".indexOf(rest.charAt(0)) >= 0;
        boolean bare = t.length() <= 3;                    // 光杆「A」「①」
        boolean wide = c0 > 0x2000;                        // 圈码/全角/假名本身就是标签
        return (sep || bare || (wide && !rest.isEmpty())) ? lab : 0;
    }

    /** 从第 {@code idx} 行往上找最近的一行题干，取出它印的题号；没有就 0。 */
    private static int printedNoAbove(String[] lines, int idx) {
        String allowed = allowedLetters(Prefs.labels());
        int floor = Math.max(0, idx - 5);
        for (int j = idx - 1; j >= floor; j--) {
            String t = lines[j].trim();
            if (t.isEmpty() || optionLineLabel(t, allowed) != 0) continue;
            Matcher m = P_QNO.matcher(t);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (Throwable ignored) {
                }
            }
            return 0;      // 顶着最近的一行普通文字看：它不带题号，这组就没题号
        }
        return 0;
    }

    /**
     * 照印刷题号一一对应。**只要有一条真的给了答案的题对不上，整条作废**（返回 null，
     * 退回按位置数的那套）—— 半对半不对说明我们整套理解就是错的，宁可不点。
     * 模型自己标了「答不了」（answer = "?"）的题对不上不算数：它本来就不该点。
     */
    private static int[] matchByPrintedNo(List<Parsed> items, int[] nos, int groups) {
        int[] out = new int[items.size()];
        java.util.Arrays.fill(out, -1);
        int last = -1;
        for (int i = 0; i < items.size(); i++) {
            if (missingAnswer(items.get(i))) continue;     // 「这题答不了」本来就不点，不占组
            int n = items.get(i).n;
            int g = -1;
            for (int k = 0; k < groups; k++) {
                if (nos[k] == n) {
                    g = k;
                    break;
                }
            }
            if (g < 0) return null;          // 给了答案却找不到对应的一组 = 这套理解不对
            if (g <= last) return null;      // 题号和组号的先后顺序打架 = 套错了
            last = g;
            out[i] = g;
        }
        return out;
    }

    /** 从第 {@code idx} 行往上找最近的一行题干原文；找不到给 ""。 */
    private static String stemAbove(String[] lines, int idx) {
        String allowed = allowedLetters(Prefs.labels());
        int floor = Math.max(0, idx - 5);
        for (int j = idx - 1; j >= floor; j--) {
            String t = lines[j].trim();
            if (t.isEmpty() || optionLineLabel(t, allowed) != 0) continue;
            return t;
        }
        return "";
    }

    /**
     * 照**模型自己抄回来的题干**对组。这一类比题号更硬：题号可以是模型按位置数的，
     * 题干却是它在屏幕上真看见的字。
     * <p>
     * 一屏里最麻烦的两种情况它都能分开 —— 顶上那半截题的选项还在（第 4 题的 A、B、C、D
     * 印在屏幕最上面，题干滚出去了），模型**有时候把它当一道题编进 items**（填个 "?"），
     * 有时候**整个不提**。两种情况的「第几条答案对第几组」正好差一位，光看题号分不出来，
     * 可题干对不对得上是一眼的事：题干滚出去的那组，上面那行是空的，谁都匹配不上。
     * <p>
     * 对不上的那几条（抄岔了、指到已经用过的组）一律留 -1 不点。这里的位置是
     * **按题干原文定的绝对位置**，不存在「整体差一位」，所以漏点一条只损失那一条，绝不会
     * 因为一条对不上就把整屏的答案挪到别的题上去 —— 比「一条不对就整套作废、退回按位置
     * 数」安全得多。
     * <p>
     * 只要模型抄了题干，就以题干为准，**一条都没对上也不退回按位置数**。用户报的
     * 「A B C D / 2.下一题题干」这种一屏就是这样：模型看见的是下一题的题干，它照着
     * 那条题干回了答案，可那道题的选项还在屏幕下面（这一屏只有上一题的一组选项）。
     * 退回按位置数会把它当成「屏幕上第 1 题」，直接点在上方那组选项上 ——
     * 宁可这条不点，等滑下去看见选项再答。一条 q 都没抄（q 都空着）才返回 null，
     * 退回按题号/按位置那套。关键字少于 4 个字的不认，免得「下列」这种词到处撞。
     */
    public static int[] matchByStem(List<Parsed> items, int groups, String screenText) {
        String[] stems = groupStems(screenText, groups);
        if (stems == null) return null;
        int[] out = new int[items.size()];
        java.util.Arrays.fill(out, -1);
        boolean[] used = new boolean[groups];
        boolean copied = false;
        int last = -1;
        for (int i = 0; i < items.size(); i++) {
            Parsed it = items.get(i);
            String q = normStem(it.q);
            if (q.length() < 4) continue;
            copied = true;
            int hit = -1;
            boolean twice = false;
            for (int g = last + 1; g < groups; g++) {
                if (used[g]) continue;
                String s = normStem(stems[g]);
                if (s.isEmpty()) continue;
                if (s.contains(q) || q.contains(s)) {
                    if (hit >= 0) {
                        twice = true;
                        break;
                    }
                    hit = g;
                }
            }
            if (twice || hit < 0) continue;      // 对不上就不点，别猜
            used[hit] = true;
            last = hit;
            out[i] = hit;
        }
        return copied ? out : null;
    }

    /**
     * 题干比对用：去掉空白和标点，只留字，省得全角/半角、空格把同一条题干拆成两条。
     * <p>
     * 公开出来还有第二个用处：{@code FloatingService} 拿它复核「读屏到点答案这一会儿
     * 屏幕翻了没有」—— 模型照抄的题干还在不在当前这屏上。
     */
    public static String normStem(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 模型抄回来的题干，是不是就出自这段屏幕文字 —— **允许 OCR 认错几个字**。
     * <p>
     * 学习通这类页面：无障碍只能读到光杆选项，题目是截图 OCR 认出来的，题干里夹着错字
     * （实测「染色体体」认成「染色体本」、「畸」认成「休」）。拿 {@code contains} 逐字比，
     * 一个错字就判成「题干不在这屏上」，明明屏幕没动却每轮都不点 —— 用户看到的就是
     * 「一直在读、就是不答」。
     * <p>
     * 所以两头都要够：
     * <ul>
     *   <li>**连着的**最长那一段至少占题干四成 —— 错一两个字只是把题干断成两三截，
     *       每截都还挺长；而整页文字里那些「字都散在各处、凑巧都对得上」的假匹配
     *       连不了这么长（这一条专门挡它）。</li>
     *   <li>**整句**对得上的字至少占八成（不要求连着，按先后顺序算）—— 错字把题干断成
     *       好几截时看这个：十九个字错一个还剩九成五，真翻到别的题上通常只剩「下列」
     *       那两三个字。</li>
     * </ul>
     */
    public static boolean sameStem(String q, String screenText) {
        String a = normStem(q), b = normStem(screenText);
        if (a.length() < 4 || b.isEmpty()) return false;
        if (longestCommonRun(a, b) * 10 < a.length() * 4) return false;
        return lcsLen(a, b) * 10 >= a.length() * 8;
    }

    /**
     * 两个串**按先后顺序**能对上多少个字（不要求挨着）。滚一维 DP。
     * <p>
     * 比「最长那一段连着」更抗错字：题干中间错两个字，就把最长段砍掉一半，
     * 可整句里对上的字还是九成 —— 到底是不是同一道题，看这个更靠谱。
     */
    private static int lcsLen(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                cur[j] = ca == b.charAt(j - 1)
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    /** 两个串里最长的那段**连着的**公共子串有多长（字符数）。滚一维 DP，够快。 */
    private static int longestCommonRun(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        int best = 0;
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                if (ca == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) best = cur[j];
                } else {
                    cur[j] = 0;
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return best;
    }

    /** 屏幕文字里第一道题的印刷题号（「6. 下列……」→ 6）。找不到给 0。 */
    public static int firstPrintedNo(String screenText) {
        if (screenText == null || screenText.isEmpty()) return 0;
        Matcher m = P_QNO.matcher(screenText);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /**
     * 把模型给的每条答案对到屏幕上的第几组选项。
     * <p>
     * 题号有两种可能的意思，模型两种都会用：「屏幕上从上往下第几题」，或者
     * 「题面印的那个号」。一屏正好从第 1 题开始印时两者一样，可翻到「6.」「7.」
     * 那一屏就分家了 —— 之前只按前者算（组号 = n-1），于是第 6、7 题被判成
     * 「屏幕上找不到对应的选项组」，一条都没点。这里两种起点都试。
     * <p>
     * 而且**从前面对得上几条就点几条**，不要求每条都对得上：屏幕上的题少、
     * 模型多回了一条（常见于某道题只露了题干、选项在屏幕外，它老老实实填了 "?"），
     * 以前会让整屏一条都不点 —— 明明前一两条是对得上的。对不上的那些丢在队尾不点，
     * 就还是「宁可漏点，不能串题」。队列**中间**对不上则从那里截断：题号一旦乱了，
     * 后面的对应关系就无从谈起，宁可不点。
     *
     * @return 与 items 一一对应的组号（0 起）；-1 = 这条对不上，别点。
     */
    public static int[] mapGroups(List<Parsed> items, int groups, String screenText) {
        int[] out = new int[items.size()];
        java.util.Arrays.fill(out, -1);
        if (items.isEmpty() || groups <= 0) return out;

        // 最硬的一条放最前面：模型抄回来的题干原文（见 matchByStem）。它不需要题号，
        // 「纯竖着排、一个编号都没有」的卷子也能对。抄了并且对得上就用它。
        int[] byStem = matchByStem(items, groups, screenText);
        if (byStem != null) return byStem;

        boolean allHaveN = true;
        for (Parsed it : items) {
            if (it.n < 1) {
                allHaveN = false;
                break;
            }
        }

        if (allHaveN) {
            // 题干上印的题号。屏幕上最上面那题只有选项、题干滚出去了
            // 的时候，模型不会把它编进 items，可分组那套还留着它 —— 按位置数就整体差一位，
            // 每条答案都点到下一题上（见 groupPrintedNos 的说明）。
            int[] nos = groupPrintedNos(screenText, groups);
            if (nos != null) {
                int[] byNo = matchByPrintedNo(items, nos, groups);
                if (byNo != null) return byNo;
            }

            int first = firstPrintedNo(screenText);
            // 起点候选：0 = 题号是「屏幕上第几题」；first-1 = 题号是「题面印的号」；
            // first-1-leading = 题面印的号，但最上面那 leading 组只有选项、题干滚出去了
            // （带不出题号，见 groupPrintedNos），模型却照样按原题号给它编了号。
            int[] bases = new int[3];
            int nb = 0;
            bases[nb++] = 0;
            if (first > 1) {
                bases[nb++] = first - 1;
                int leading = 0;
                if (nos != null) {
                    while (leading < groups && nos[leading] == 0) leading++;
                }
                int b3 = first - 1 - leading;
                if (leading > 0 && b3 > 0 && b3 != bases[0] && (nb < 2 || b3 != bases[1])) {
                    bases[nb++] = b3;
                }
            }
            // 屏幕是从半道截出来的（往下滚过一屏）时，最上面那道题往往只剩几条选项，
            // 模型会老老实实给它一条 "?"、然后接着往下编号。可分组那套（trimLeadingFragment）
            // 是把这半截题**丢掉**的，于是模型的编号比组号多了一位，**每一题的答案都点到了
            // 下一题上**。所以这里也允许「从第几条开始对」，让两种数法能对上。
            int maxSkip = 0;
            // 「从第几条开始对」只在**顶上真被切掉半道题**的时候才允许不为 0：
            // 屏幕上第一行就是选项、又不是从标签表的第一个标签（A）起头的，说明
            // trimLeadingFragment 丢掉了一条 —— 模型看不见题号，多半把这条也数成一道题。
            // 顶上好好的（第一行是题干，或者第一行就是 A），模型少编一条只是它自己的事，
            // 硬要跳过一条就等于把每道答案都往上挪一题。
            if (leadingTrimmed(screenText)) {
                while (maxSkip < items.size() && maxSkip < 3
                        && missingAnswer(items.get(maxSkip))) maxSkip++;
            }
            int answers = 0;
            for (Parsed it : items) if (!missingAnswer(it)) answers++;
            int bestBase = 0, bestK = -1, bestSkip = 0, bestLeft = Integer.MAX_VALUE;
            int bestClash = Integer.MAX_VALUE;
            for (int sk = 0; sk <= maxSkip; sk++) {
                // bi == nb 的那一档是「base = skip」：被 trimLeadingFragment 丢掉的那半截题
                // （屏幕上只剩 C、D 两条选项），模型看不见题号，就照屏幕顺序数成第 1 题，
                // 于是它给的题号整体比组号大 skip 位。不试这一档的话，每条答案都点到下一题上。
                for (int bi = 0; bi <= nb; bi++) {
                    if (bi == nb && sk == 0) continue;          // base 0 已经在 bases 里了
                    int base = bi < nb ? bases[bi] : sk;
                    int[] f = fit(items, groups, base, sk, nos);
                    int k = f[0], clash = f[1];
                    int left = answers - k;      // 还有几条**有答案的**没着落
                    // 先比对上几条有答案的，再比「答案的题号和组上印的题号打架」几条，
                    // 最后比剩几条没着落。都打平就留前面那个（skip 小、base 靠前）。
                    if (k > bestK
                            || (k == bestK && clash < bestClash)
                            || (k == bestK && clash == bestClash && left < bestLeft)) {
                        bestK = k;
                        bestBase = base;
                        bestSkip = sk;
                        bestLeft = left;
                        bestClash = clash;
                    }
                }
            }
            fit(items, groups, bestBase, bestSkip, nos, out);
            return out;
        }

        // 一条题号都没给：只有「条数正好等于组数」才敢按顺序一一对应
        if (items.size() == groups) {
            for (int i = 0; i < items.size(); i++) out[i] = i;
        }
        return out;
    }

    /**
     * 从第 {@code skip} 条起，依次最多能连到第几条都对得上（组号在范围内、不重复）。
     * <p>
     * {@code nos} 是每组选项上印的题号（见 {@link #groupPrintedNos}，0 = 那组没题号）。
     * 一条答案落到的组**印的题号跟它自己的题号不一样**，这条数法就是硬的错位，
     * 记一笔 clash —— {@link #mapGroups} 在同为对得上几条时优先选 clash 少的那种数法。
     *
     * @return {连上几条, 其中几条题号打架}
     */
    private static int[] fit(List<Parsed> items, int groups, int base, int skip, int[] nos) {
        return fit(items, groups, base, skip, nos, null);
    }

    /**
     * 和上面同一个数法，顺带把对上的组号写进 {@code out}（-1 = 这条没对上）。
     * <p>
     * 数出来的条数只算**有答案的**那些：「?」那条本来就点不着，让它占个位只是为了
     * 后面的答案跟组对得整齐，算进「对上几条」的话，会骗着上面选中那种
     * 「把真正有答案的那条挤掉、换一条 '?' 进来」的数法。
     */
    private static int[] fit(List<Parsed> items, int groups, int base, int skip, int[] nos, int[] out) {
        boolean[] used = new boolean[groups];
        int k = 0, clash = 0;
        for (int i = skip; i < items.size(); i++) {
            int n = items.get(i).n;
            int g = n - 1 - base;
            if (g < 0 || g >= groups) break;
            if (used[g]) break;             // 两条答案挤到同一组 = 对错了
            if (nos != null && nos[g] != 0 && nos[g] != n) clash++;
            used[g] = true;
            if (out != null) out[i] = g;
            if (!missingAnswer(items.get(i))) k++;
        }
        return new int[]{k, clash};
    }

    /** {@code fit} 的老样子：不算 clash（{@link #groupBase} 那边只比连上几条）。 */
    private static int prefixFits(List<Parsed> items, int groups, int base, int skip) {
        return fit(items, groups, base, skip, null)[0];
    }

    private static int prefixFits(List<Parsed> items, int groups, int base) {
        return prefixFits(items, groups, base, 0);
    }

    /**
     * 这屏的题号从第几题起算（给日志用）。0 当起点，或屏幕首题号减一；
     * <b>-1 表示压根不是按位置数的</b> —— 走的是 {@link #groupPrintedNos} 那套
     * （每条答案对着自己题干上印的题号找组），这时「从第几题起算」没有意义。
     */
    public static int groupBase(List<Parsed> items, int groups, String screenText) {
        boolean allHaveN = true;
        for (Parsed it : items) {
            if (it.n < 1) {
                allHaveN = false;
                break;
            }
        }
        if (!allHaveN) return 0;
        int[] nos = groupPrintedNos(screenText, groups);
        if (nos != null && matchByPrintedNo(items, nos, groups) != null) return -1;
        int first = firstPrintedNo(screenText);
        if (first > 1 && prefixFits(items, groups, first - 1) > prefixFits(items, groups, 0)) {
            return first - 1;
        }
        return 0;
    }

    // ---------------- 解析自检 ----------------

    /**
     * 纯本地的解析自检，不花 API 额度、没网也能跑：拿几段固定的「模型回复 + 题面」
     * 走一遍 {@link #parse}，看结果是不是预期的那样。设置页和 {@code adb ... CHECK}
     * 广播都跑这一份，免得两边各写一套、改了一处忘了另一处。
     *
     * @return 每行一条：「ok 名称：结果」或「!! 名称：结果（应为 …）」。
     */
    public static List<String> selfCheck() {
        List<String> out = new ArrayList<>();
        String L = "A,B,C,D,E,F,G,H";
        // 题面写着「单选题」，模型却回了 ACD：只能留它最先说的那一个
        chk(out, "单选截断(ACD)", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"ACD\",\"multi\":false}]}", L,
                "1. 下列说法中正确的是（单选题）"), "1条 A");
        // 模型把字母排成了 DCA：得留原话里的第一个 D，不是排完序的 A
        chk(out, "单选取原话首位(DCA)", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"DCA\",\"multi\":false}]}", L, ""), "1条 D");
        // 题面写多选：几个字母都留着
        chk(out, "多选不截断(ACE)", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"ACE\",\"multi\":true}]}", L,
                "1. 下列属于……的有（多选题）"), "1条 ACE");
        // 题面没写单选多选，模型给了多个字母又没说 multi：拿不准就当单选，只留第一个
        // （单选题上多给字母 = 选项来回点一遍、最后留哪个看运气；多选题漏一个只是漏选）
        chk(out, "题面没写按单选", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"AB\"}]}", L, "1. 甲题\nA. 甲\nB. 乙"), "1条 A");
        // 题面没写，但模型明确说 multi=true：那是它认出来的多选，字母都留着
        chk(out, "题面没写多选认multi", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"AB\",\"multi\":true}]}", L,
                "1. 甲题\nA. 甲\nB. 乙"), "1条 AB");
        // 一屏三题：三条答案按顺序排着，谁也别串到谁头上（多选那条模型照约定标了 multi）
        chk(out, "一屏三题按序", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"A\"},{\"n\":2,\"answer\":\"CD\",\"multi\":true},"
                        + "{\"n\":3,\"answer\":\"B\"}]}",
                L, "1. 甲题\nA. 甲\nB. 乙\n2. 乙题\nA. 丙\nB. 丁\n3. 丙题"), "3条 A|CD|B");
        // 一屏三题但题面没写编号：条数对得上就按顺序一一对应
        chk(out, "无编号按序", parse(
                "{\"items\":[{\"answer\":\"A\"},{\"answer\":\"B\"},{\"answer\":\"C\"}]}",
                L, "甲题\nA. 甲\nB. 乙\n乙题\nA. 丙\nB. 丁\n丙题"), "3条 A|B|C");
        // 中间那条空着也得占位，不然第 3 题的答案会往前挪一格、点到第 2 题上
        chk(out, "空条目占位", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"A\"},{\"answer\":\"\"},{\"n\":3,\"answer\":\"B\"}]}", L, ""),
                "3条 A||B");
        // 老格式（模型没照约定）还得认得出来，不能因为加了 items 就不管了
        chk(out, "老格式兜底", parse(
                "{\"answer\":\"B\",\"explanation\":\"因为……\"}", L, ""), "1条 B");

        // 题面用 ①②③ 印选项，模型也就照着回「①②」：得折算成 A、B
        chk(out, "圈码选项(①②→A)", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"①②\"}]}", L, "1. 甲题\n① 甲\n② 乙"), "1条 A");
        // 全角 ＡＢＣＤ：同上
        chk(out, "全角选项(Ａ→A)", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"ＡＣ\"}]}", L, "1. 甲题\nＡ. 甲\nＣ. 丙"), "1条 A");
        // 日语卷子：选项标签用户在设置里填成 ア,イ,ウ,エ，模型照样回假名 —— 标签原样留着
        // （点的时候就用这个假名去找那一行），但单选还是只留最先说的那一个
        chk(out, "假名标签(单选截断)", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"アイ\"}]}", "ア,イ,ウ,エ",
                "1. 甲题\nア. 甲\nイ. 乙"), "1条 ア");
        // 同上一屏，但题面写了多选：假名照留下来
        chk(out, "假名标签(多选)", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"アイ\",\"multi\":true}]}", "ア,イ,ウ,エ",
                "1. 下列哪些正确（多选题）\nア. 甲\nイ. 乙"), "1条 アイ");

        // 取词只拿到光杆标签（学习通读出来就是这样），模型就当成填空题写了一整段正文：
        // 屏幕上有选项标签，这是选择题，正文不能留 —— 否则程序走填空题那条路
        // （正文塞剪贴板、暂停回答），一整道选择题就这么被绕过去了。
        chkFill(out, "光杆标签不当填空", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"?\",\"fill\":\"一整段作答正文\"}]}", L,
                "1. 下列说法正确的是\nA\nB\nC\nD"), "");
        // 同上，但只读到题干、选项一个都没读出来：一样不能当填空
        chkFill(out, "只读到题干不当填空", parse(
                "{\"answer\":\"\",\"fill\":\"一整段作答正文\"}", L,
                "1. 下列说法中正确的是（　）\nA. 甲"), "");
        // 页面上一个选项标签都没有 —— 真填空题，正文照留
        chkFill(out, "真填空题留正文", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"\",\"fill\":\"一整段作答正文\"}]}", L,
                "1. 请简述细胞膜的主要功能。"), "一整段作答正文");
        // 模型既给了字母答案又给了正文：它看懂了题（带了候选选项的填空题），正文留着
        chkFill(out, "有字母答案就留正文", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"B\",\"fill\":\"正文\"}]}", L,
                "1. 甲题\nA. 甲\nB. 乙"), "正文");
        // 英文句子开头的 A 不能当成选项标签（否则真填空题的正文会被误丢）
        chkFill(out, "英文A不算选项", parse(
                "{\"items\":[{\"n\":1,\"answer\":\"\",\"fill\":\"正文\"}]}", L,
                "A study shows that the membrane works as a barrier."), "正文");

        // 一屏两题，但题面印的是「6.」「7.」：组号得从第 6 题起算，不是从 1 起算
        List<Parsed> six = parse(
                "{\"items\":[{\"n\":6,\"answer\":\"ABCD\",\"multi\":true},"
                        + "{\"n\":7,\"answer\":\"AC\",\"multi\":true}]}", L,
                "6. 下列哪些属于哺乳动物？（多选题）\nA. 狗\nB. 猫\n7. 下列哪些是质数？（多选题）\nA. 2\nB. 4")
                .items;
        chkMap(out, "题号从6起算", mapGroups(six, 2,
                "6. 下列哪些属于哺乳动物？（多选题）\nA. 狗\nB. 猫\n"
                        + "7. 下列哪些是质数？（多选题）\nA. 2\nB. 4"), "0,1");
        // 同一屏、同两条答案，但模型按「屏幕上第几题」写成了 1、2：也得对上
        List<Parsed> one = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"ABCD\",\"multi\":true},"
                        + "{\"n\":2,\"answer\":\"AC\",\"multi\":true}]}", L,
                "6. 下列哪些属于哺乳动物？（多选题）\nA. 狗\nB. 猫\n7. 下列哪些是质数？（多选题）\nA. 2\nB. 4")
                .items;
        chkMap(out, "屏幕上第几题", mapGroups(one, 2,
                "6. 下列哪些属于哺乳动物？（多选题）\nA. 狗\nB. 猫\n"
                        + "7. 下列哪些是质数？（多选题）\nA. 2\nB. 4"), "0,1");
        // 题号既不是印刷号也不是屏幕序号（乱给）：一条都不点，别串位
        List<Parsed> bad = parse(
                "{\"items\":[{\"n\":4,\"answer\":\"A\"},{\"n\":9,\"answer\":\"B\"}]}", L, "4. 甲题\n9. 乙题").items;
        chkMap(out, "题号对不上就不点", mapGroups(bad, 2, "甲题\nA. 甲\n乙题\nB. 乙"), "-1,-1");
        // 一条题号都没有，但条数正好等于组数：按顺序一一对应
        List<Parsed> noN = parse(
                "{\"items\":[{\"answer\":\"A\"},{\"answer\":\"BC\",\"multi\":true}]}", L, "").items;
        chkMap(out, "没题号按顺序", mapGroups(noN, 2, ""), "0,1");
        // 条数对不上又没题号：还是别点
        chkMap(out, "条数不对就不点", mapGroups(noN, 3, ""), "-1,-1");
        // 屏幕外只露了题干的第 3 题被模型填了 "?"：前两条对得上就照点，别被这条拖累
        List<Parsed> extra = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"A\"},{\"n\":2,\"answer\":\"BC\",\"multi\":true},"
                        + "{\"n\":3,\"answer\":\"?\"}]}", L, "1. 甲题\nA. 甲\nB. 乙\n2. 乙题").items;
        chkMap(out, "屏外多一条不拖累", mapGroups(extra, 2, "1. 甲题\nA. 甲\nB. 乙\n2. 乙题"), "0,1,-1");
        // 队列中间对不上（第 3 题前面缺了第 2 题）：从这里截断，后面的一律不点
        List<Parsed> gap = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"A\"},{\"n\":3,\"answer\":\"B\"}]}", L, "").items;
        chkMap(out, "中间对不上就截断", mapGroups(gap, 2, ""), "0,-1");

        // 往下滚过一屏之后：最上面那道题只剩几条选项，分组那套会把它丢掉，
        // 模型却老老实实给它编了 1 号、后面的题依次 2、3 —— 编号比组号多一位。
        // 不认这条的话，每一题的答案都会点到**下一题**上。
        String scrolled = "B. 上海\nC. 广州\nD. 深圳\n2. 下列哪些是偶数？（多选题）\n"
                + "A. 3\nB. 4\nC. 6\nD. 7\n3. 水的化学式是？（单选题）\nA. H2O\nB. CO2\nC. O2\nD. NaCl";
        List<Parsed> lead = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"?\"},{\"n\":2,\"answer\":\"BC\",\"multi\":true},"
                        + "{\"n\":3,\"answer\":\"A\"}]}", L, scrolled).items;
        chkMap(out, "滚屏后补一位", mapGroups(lead, 2, scrolled), "-1,0,1");
        // 反过来：最上面那条 "?" 确实占着一整组（三组都认出来了）就得留着占位，不能往后挪，
        // 挪了后面两题的答案就全点到别人头上。group0 拿到 "?" 不要紧，点的时候会跳过它。
        List<Parsed> keep0 = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"?\"},{\"n\":2,\"answer\":\"A\"},"
                        + "{\"n\":3,\"answer\":\"B\"}]}", L, "1. 甲题\n2. 乙题\n3. 丙题").items;
        chkMap(out, "整题占位不后挪", mapGroups(keep0, 3, "1. 甲题\n2. 乙题\n3. 丙题"), "0,1,2");

        // 滑到半道：屏幕最上面是**上一题滚剩的整排选项**（题干已经在屏幕外），模型看不见
        // 题干、不会把它编进 items，于是模型给的题号（5、6）比组号多一位。照位置数就会把
        // 太阳那题的 D 点到上一题的 D 上 —— 用户报的「水的化学式本该选 A，BC 也选上了」
        // 就是这个。题干上印的题号两边都看得见，照它对才对得上。
        String stemGone = "A. 10\nB. 11\nC. 12 ✓\nD. 13\n5. 太阳从哪个方向升起？（单选题）\n"
                + "A. 北\nB. 南\nC. 西\nD. 东\n6. 下列哪些属于哺乳动物？（多选题）";
        List<Parsed> sun = parse(
                "{\"items\":[{\"n\":5,\"answer\":\"D\"},{\"n\":6,\"answer\":\"?\"}]}", L, stemGone).items;
        chkMap(out, "题干滚出去也不串位", mapGroups(sun, 2, stemGone), "1,-1");
        // 反过来：模型真给最上面那题也编了号（4），说明它看得见题干 —— 这时印刷题号里
        // 没有 4 这一组，不该硬套，退回按位置数的那套（5 起算 → 5、6 落到第 1、2 组）
        List<Parsed> sun3 = parse(
                "{\"items\":[{\"n\":4,\"answer\":\"C\"},{\"n\":5,\"answer\":\"D\"},"
                        + "{\"n\":6,\"answer\":\"?\"}]}", L, stemGone).items;
        chkMap(out, "模型看得见题干就别硬套", mapGroups(sun3, 2, stemGone), "0,1,-1");
        // 顶上是**上一题的尾巴**（C. O2 / D. NaCl，题干和 A、B 都滚出去了）：分组那套会
        // 把它砍掉（不是从第一个标签 A 起头的），分题号时也得砍 —— 不砍的话切出来的组数
        // 正好撞上实际组数，第 4 题的 C 就点到第 5 题上去了。
        String tail = "C. O2\nD. NaCl\n4. 一年有多少个月？（单选题）\nA. 10\nB. 11\nC. 12 ✓\n"
                + "D. 13\n5. 太阳从哪个方向升起？（单选题）\nA. 北\nB. 南\nC. 西\n退出全屏模式";
        List<Parsed> year = parse(
                "{\"items\":[{\"n\":4,\"answer\":\"C\"},{\"n\":5,\"answer\":\"?\"}]}", L, tail).items;
        chkMap(out, "上一题尾巴别当一组", mapGroups(year, 2, tail), "0,-1");
        // 实机日志里真出过的一屏（2026-10-11 09:33:42）：顶上也是半截题，可这次的
        // 「半截」模型看见了 —— 它把 C. 12 / D. 13 数成第 1 题（填了个 "?"），把太阳
        // 那题数成第 2 题。分组那套把这段砍了，所以模型的题号整体比组号大一位。
        // 不认这一档的话：太阳的 D 会被点到第 2 组（哺乳动物那题的选项）上去 ——
        // 那组正好也有个 D 的话，就是实打实的串题。
        String fragGone = "C. 12 ✓\nD. 13\n5. 太阳从哪个方向升起？（单选题）\n"
                + "A. 北\nB. 南\nC. 西\nD. 东 ✓\n6. 下列哪些属于哺乳动物？（多选题）\n"
                + "A. 狗\nB. 猫\nC. 鲸";
        List<Parsed> frag = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"?\"},{\"n\":2,\"answer\":\"D\"},"
                        + "{\"n\":3,\"answer\":\"ABC\"}]}", L, fragGone).items;
        chkMap(out, "半截题被数进去也不串位", mapGroups(frag, 2, fragGone), "-1,0,1");
        // 同一天 09:33:32 的另一屏：顶上是**完整的** A、B、C、D（第 4 题的选项整条都在），
        // 模型还是给它们编了号 1、2、3。这种屏上一条都没被切掉，不许跳过第一条 ——
        // 它的第 2 题就是屏幕上的第 2 组。
        String fragHead = "A. 10 ✓\nB. 11\nC. 12 ✓\nD. 13\n5. 太阳从哪个方向升起？（单选题）\n"
                + "A. 北\nB. 南\nC. 西\nD. 东\n6. 下列哪些属于哺乳动物？（多选题）";
        List<Parsed> head = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"?\"},{\"n\":2,\"answer\":\"D\"},"
                        + "{\"n\":3,\"answer\":\"?\"}]}", L, fragHead).items;
        chkMap(out, "顶上没切过就不许跳过第一条", mapGroups(head, 2, fragHead), "0,1,-1");
        // 顶上没切过、又没有印刷题号时，模型第 1 条填 "?" 也**不能**跳 ——
        // 那一条在屏幕上是有自己那组选项的，跳了就是把每道答案往上挪一题。
        String plain2 = "甲题\nA. 甲\nB. 乙\n乙题\nA. 丙\nB. 丁";
        List<Parsed> plainItems = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"?\"},{\"n\":2,\"answer\":\"A\"},"
                        + "{\"n\":3,\"answer\":\"B\"}]}", L, plain2).items;
        chkMap(out, "顶上没切过就别乱跳", mapGroups(plainItems, 2, plain2), "0,1,-1");

        // 实机日志里真出过的那一屏（2026-10-11 09:39:45）：这回模型**根本没提**顶上那半截题，
        // 它的第 1 条（D、太阳）就是屏幕上第 2 组。光看题号（1、2 对不上印着的 5）分不出
        // 「它数了那半截」还是「它没数」，可题干是它在屏幕上真看见的字 —— 照它对就一定对。
        String twoStems = "A. 10\nB. 11\nC. 12 ✓\nD. 13\n5. 太阳从哪个方向升起？（单选题）\n"
                + "A. 北\nB. 南\nC. 西\nD. 东\n6. 下列哪些属于哺乳动物？（多选题）";
        List<Parsed> byStem = parse(
                "{\"items\":[{\"n\":1,\"q\":\"太阳从哪个方向升起？\",\"answer\":\"D\"},"
                        + "{\"n\":2,\"q\":\"\",\"answer\":\"?\"}]}", L, twoStems).items;
        chkMap(out, "照抄的题干能把答案拉回自己那题", mapGroups(byStem, 2, twoStems), "1,-1");
        // 模型没抄题干（q 空）时不能瞎认：退回按题号/按位置那套，结果和上面那条不一样 ——
        // 这正是「宁可漏点」的那些情况，不许因为多了一档匹配就把没根据的答案点下去。
        List<Parsed> noStem = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"D\"},{\"n\":2,\"answer\":\"?\"}]}", L, twoStems).items;
        chkMap(out, "没抄题干就别乱认", mapGroups(noStem, 2, twoStems), "0,1");
        // 抄回来的题干对不上屏幕（模型改写/翻译过的）：那一条留 -1 不点，**别的照样用它对上的
        // 那条**（太阳那题照抄得一字不差，它就该点在第 2 组）—— 一条抄岔了不牵连别人。
        List<Parsed> badStem = parse(
                "{\"items\":[{\"n\":1,\"q\":\"太阳从哪个方向升起\",\"answer\":\"D\"},"
                        + "{\"n\":2,\"q\":\"下列哪些属于哺乳动物\",\"answer\":\"ABC\"}]}", L, twoStems).items;
        chkMap(out, "抄岔的那条不点，别的照点", mapGroups(badStem, 2, twoStems), "1,-1");

        // 用户报的布局（2026-10-20）：上一题整组选项下面紧跟下一题的题干，下一题的
        // 选项还在屏幕外。模型只看得见下一题的题干，就可能照着它回答案 —— 这条答案
        // 不许落到上方那组选项上（按位置数会把它当「第 1 题」点下去）。
        String nextStemOnly = "1. 中国的首都是？\nA. 北京\nB. 上海\nC. 广州\nD. 深圳\n"
                + "2. 下列哪些是偶数？";
        List<Parsed> below = parse(
                "{\"items\":[{\"n\":2,\"q\":\"下列哪些是偶数？\",\"answer\":\"A\"}]}", L,
                nextStemOnly).items;
        chkMap(out, "答的是下面那道题就不点", mapGroups(below, 1, nextStemOnly), "-1");
        chkText(out, "认得出题干在选项下面",
                String.valueOf(stemBelowOptions("下列哪些是偶数？", nextStemOnly)), "true");
        chkText(out, "上面的题干不算在下面",
                String.valueOf(stemBelowOptions("中国的首都是？", nextStemOnly)), "false");

        // 用户报的第二种布局：上一题只剩 B C D（A 滚出去了），接着是下一题的题干，
        // 下一题只露出一个 A。分组时 B C D 是上一题的尾巴（后面还有从 A 起头的一段），
        // 要砍掉；A 那一段虽然只有一条也得留着 —— 它就是下一题，只是没露全。
        // 模型照抄下一题的题干，就该对到这一组上（点不点由「露没露全」再拦一道）。
        String splitLayout = "B. 上海\nC. 广州\nD. 深圳\n2. 下列哪些是偶数？\nA. 3";
        List<Parsed> part = parse(
                "{\"items\":[{\"n\":2,\"q\":\"下列哪些是偶数？\",\"answer\":\"A\"}]}", L,
                splitLayout).items;
        chkMap(out, "半截题 + 下一题只露 A", mapGroups(part, 1, splitLayout), "0");
        // 模型把上面的半截题当成第 1 题、下一题当成第 2 题（常见）：题号整体多一位，
        // 得靠抄回来的题干把下一条拉回它自己那组，别让它点到上一题上。
        List<Parsed> part2 = parse(
                "{\"items\":[{\"n\":1,\"answer\":\"?\"},"
                        + "{\"n\":2,\"q\":\"下列哪些是偶数？\",\"answer\":\"A\"}]}", L,
                splitLayout).items;
        chkMap(out, "半截题占位也不串位", mapGroups(part2, 1, splitLayout), "-1,0");

        // 「选中标记」那套：页面点中之后会在文字尾巴上加个勾，指纹必须忽略它，
        // 否则连答会以为页面翻页了，重问一遍还顺手把刚选上的选项点掉。
        chkText(out, "勾要剥掉", QuizAccessibilityService.stripSelected("A. 狗 ✓"), "A. 狗");
        chkText(out, "已选要剥掉", QuizAccessibilityService.stripSelected("B. 猫 已选"), "B. 猫");
        chkText(out, "带括号的已选", QuizAccessibilityService.stripSelected("C. 鲸（已选）"), "C. 鲸");
        chkText(out, "空心方框不算选中",
                String.valueOf(QuizAccessibilityService.hasSelectedMark("C. 鲸 ☐")), "false");
        chkText(out, "打勾才算选中",
                String.valueOf(QuizAccessibilityService.hasSelectedMark("C. 鲸 ✓")), "true");
        chkText(out, "正文里的「选中」不算",
                String.valueOf(QuizAccessibilityService.hasSelectedMark("D. 选中全部")), "false");
        chkText(out, "选项正文不能剥没了",
                QuizAccessibilityService.stripSelected("D. 中文 选中全部"), "D. 中文 选中全部");

        // 题干模糊匹配（学习通那套截屏 OCR + 无障碍文本是**两个来源**：模型照抄的是 OCR 认出来的
        // 字，屏幕上这会儿问出来的却是无障碍那份，同一句话两边差个把错字是常态）。
        // 实测 2026-10-11 12:04 连着三轮「读屏到点答案之间屏幕已经翻过去了…这一轮作废不点」，
        // 屏幕压根没动 —— 就栽在这一个错字上（模型抄的「染色体本」vs 屏幕上的「染色体体」）。
        String stemBody = "下列关于1号染色体的说法不正确的是";
        String screenBody = "5. 下列关于1号染色体的说法不正确的是（单选题）A. 它是近端着丝粒染色体";
        chkText(out, "错一个字还认得出同一道题",
                String.valueOf(sameStem("下列关于1号染色体本的说法不正确的是", screenBody)), "true");
        chkText(out, "一字不差当然认得出", String.valueOf(sameStem(stemBody, screenBody)), "true");
        // 反过来：真翻到别的题上必须判成「不在这一屏」，否则这守卫就成了摆设（照点旧答案）。
        chkText(out, "换了一道题就认不出",
                String.valueOf(sameStem("下列关于1号染色体的说法不正确的是",
                        "下列哪些属于哺乳动物？A. 狗 B. 猫 C. 鲸")), "false");
        // 模型抄短了（只抄了题干前半截）也得认出来 —— 屏幕上那条还带着括号里的题型之类。
        chkText(out, "抄短了的题干照样认得出",
                String.valueOf(sameStem("下列哪些属于哺乳动物",
                        "6. 下列哪些属于哺乳动物？（多选题）A. 狗")), "true");
        chkText(out, "太短的题干不算数（不许乱认）",
                String.valueOf(sameStem("下列", "下列哪些属于哺乳动物")), "false");
        return out;
    }

    /** 填空题正文比对：只看 fill，不看字母（那几条测的就是「这段正文该不该留」）。 */
    private static void chkFill(List<String> out, String name, Parsed p, String want) {
        String got = p.fill == null ? "" : p.fill;
        out.add((got.equals(want) ? "ok " : "!! ") + name + "：fill=\"" + got + "\""
                + (got.equals(want) ? "" : "（应为 \"" + want + "\"）"));
    }

    /** 纯文本比对（选中标记那几条用）。 */
    private static void chkText(List<String> out, String name, String got, String want) {
        out.add((got.equals(want) ? "ok " : "!! ") + name + "：" + got
                + (got.equals(want) ? "" : "（应为 " + want + "）"));
    }

    /** 对位自检：比的是「每条答案落在第几组」。 */
    private static void chkMap(List<String> out, String name, int[] got, String want) {
        StringBuilder sb = new StringBuilder();
        for (int g : got) {
            if (sb.length() > 0) sb.append(',');
            sb.append(g);
        }
        String s = sb.toString();
        out.add((s.equals(want) ? "ok " : "!! ") + name + "：" + s
                + (s.equals(want) ? "" : "（应为 " + want + "）"));
    }

    private static void chk(List<String> out, String name, Parsed p, String want) {
        String got = desc(p);
        out.add((got.equals(want) ? "ok " : "!! ") + name + "：" + got
                + (got.equals(want) ? "" : "（应为 " + want + "）"));
    }

    /** 「几条答案、每条的字母」—— 解析自检就比这个串。 */
    public static String desc(Parsed p) {
        List<Parsed> items = p.items.isEmpty()
                ? java.util.Collections.singletonList(p) : p.items;
        StringBuilder sb = new StringBuilder();
        for (Parsed it : items) {
            if (sb.length() > 0) sb.append('|');
            sb.append(it.answer);
        }
        return items.size() + "条 " + sb;
    }
}
