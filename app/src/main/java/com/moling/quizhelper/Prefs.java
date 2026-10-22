package com.moling.quizhelper;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局设置读写。所有 key 都是扁平的 String/Int/Boolean。 */
public final class Prefs {

    private Prefs() {
    }

    public static final String DEF_BASE_URL = "https://api.deepseek.com";
    public static final String DEF_MODEL = "deepseek-flash";
    /** 老版本的默认模型。只用来判断「用户从没动过模型」，好把他迁到新默认。 */
    public static final String OLD_DEF_MODEL = "deepseek-chat";
    public static final String DEF_LABELS = "A,B,C,D,E,F,G,H";
    public static final String DEF_NEXT_KEYWORDS =
            "下一题,下一页,下一节,下一关,下一步,继续,确认,提交,Next,next,NEXT";

    public static final String DEF_PROMPT =
            "你是一个答题助手。用户会给你一段从手机屏幕上识别出来的题目文本，可能包含题目、选项、以及一些无关的界面文字。\n"
                    + "请判断题目并选出正确答案。\n"
                    + "严格只输出一个 JSON 对象，不要输出任何其它文字，不要使用 markdown 代码块：\n"
                    + "{\"items\":[{\"n\":1,\"answer\":\"A\",\"multi\":false,\"explanation\":\"不超过40字的极简解析\"}]}\n"
                    + "屏幕上只有一道题时 items 里就一条；一屏有好几道题时一题一条，"
                    + "**按屏幕从上到下的顺序**排，不要漏、不要合并、不要串题。\n"
                    + "n 是这道题在屏幕上从上往下的次序，从 1 开始数 —— 题面没写「1. 2. 3.」"
                    + "也照样数。屏幕外、只露一半看不清的题不要猜、不要编进 items。\n"
                    + "选项标签照页面上印的那套抄（① ② ③ / Ａ Ｂ / ア イ ウ 都原样抄，别换算成 ABC）；"
                    + "题干里的日语、希腊字母、化学式、医学缩写照原样理解。\n"
                    + "answer 字段：答案字母。单选题只写一个字母（multi 填 false）；"
                    + "多选题把字母连起来写，例如 AC 或 ACE，不要空格、不要逗号、不要标点（multi 填 true）。\n"
                    + "explanation 字段：中文，40字以内，一句话说明理由。\n"
                    + "如果题目不完整或无法判断，answer 填 \"?\"，explanation 说明缺什么。";

    /** 上一版的默认提示词。只用来判断「用户从没动过提示词」，好把他迁到新默认。 */
    public static final String OLD_DEF_PROMPT =
            "你是一个答题助手。用户会给你一段从手机屏幕上识别出来的题目文本，可能包含题目、选项、以及一些无关的界面文字。\n"
                    + "请判断题目并选出正确答案。\n"
                    + "严格只输出一个 JSON 对象，不要输出任何其它文字，不要使用 markdown 代码块：\n"
                    + "{\"answer\":\"A\",\"explanation\":\"不超过40字的极简解析\"}\n"
                    + "answer 字段：答案字母。多选题把字母连起来写，例如 AC 或 ACE，不要空格、不要逗号、不要标点。\n"
                    + "explanation 字段：中文，40字以内，一句话说明理由。\n"
                    + "如果题目不完整或无法判断，answer 填 \"?\"，explanation 说明缺什么。";

    private static SharedPreferences sp;

    public static void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences("quizhelper", Context.MODE_PRIVATE);
            migrateModel();
            migratePrompt();
            migrateOcrSource();
        }
    }

    /**
     * 默认提示词换了（改成 items 数组 + 单选/多选），老的那份得跟着换 ——
     * 否则用户存着的老提示词还在要求「只输出 {"answer":"A"}」，跟程序新增的
     * 一屏多题约定打架。同样只迁「压根没动过提示词」的。
     */
    private static void migratePrompt() {
        // 第一程：老的「只输出 {"answer":"A"}」那一版
        if (!sp.getBoolean("migrated_items", false)) {
            if (OLD_DEF_PROMPT.equals(sp.getString("prompt", OLD_DEF_PROMPT))) {
                sp.edit().putString("prompt", DEF_PROMPT).apply();
            }
            sp.edit().putBoolean("migrated_items", true).apply();
        }
        // 第二程：加了一屏多题、但还没提「选项标签照页面印的那套抄 / 日语·化学式」的那版。
        // 用几个只可能出现在默认提示词里的整句来认，不逐字比 —— 差一个空格就认不出来，
        // 那样升级后再也追不上新默认。用户自己写的提示词对不上这几句，一律不碰。
        if (!sp.getBoolean("migrated_prompt_items2", false)) {
            String cur = sp.getString("prompt", DEF_PROMPT);
            if (cur != null && cur.startsWith("你是一个答题助手")
                    && cur.contains("{\"items\":[{\"n\":1")
                    && cur.contains("屏幕上只有一道题时 items 里就一条")
                    && !cur.contains("照页面上印的那套抄")) {
                sp.edit().putString("prompt", DEF_PROMPT).apply();
            }
            sp.edit().putBoolean("migrated_prompt_items2", true).apply();
        }
    }

    /**
     * 默认取词方式从「自动」换成「视觉优先（4）」，把老用户迁过来。
     * <p>
     * 只迁 0（老的「自动」）和 3（「仅视觉大模型」）：这两个都等于用户**没有专门指定**
     * 哪条路，迁到新默认正好是他本来想要的效果（发截图给大模型，读不到退无障碍）。
     * 1（仅无障碍）、2（仅本地 OCR）是明明白白挑了一条路，不动人家的。
     * 只迁一次，用户之后自己改回来也不会再被改掉。
     */
    private static void migrateOcrSource() {
        if (sp.getBoolean("migrated_vision_first", false)) return;
        int m = sp.getInt("ocr_source", 0);
        if (m == 0 || m == 3) sp.edit().putInt("ocr_source", DEF_OCR_SOURCE).apply();
        sp.edit().putBoolean("migrated_vision_first", true).apply();
    }

    /**
     * 老版本的默认模型是 deepseek-chat，现在默认换成 deepseek-flash。
     * <p>
     * 只迁移「压根没动过模型」的：值正好还是旧默认才换。用户自己填的模型名一律不碰，
     * 而且只迁一次 —— 否则用户特意改回 deepseek-chat 又会被悄悄改掉。
     */
    private static void migrateModel() {
        if (sp.getBoolean("migrated_flash", false)) return;
        if (OLD_DEF_MODEL.equals(sp.getString("model", OLD_DEF_MODEL))) {
            sp.edit().putString("model", DEF_MODEL).apply();
        }
        sp.edit().putBoolean("migrated_flash", true).apply();
    }

    private static SharedPreferences p() {
        if (sp == null) throw new IllegalStateException("Prefs.init() 未调用");
        return sp;
    }

    private static String gs(String k, String d) {
        return p().getString(k, d);
    }

    private static void ps(String k, String v) {
        p().edit().putString(k, v).apply();
    }

    private static int gi(String k, int d) {
        return p().getInt(k, d);
    }

    private static void pi(String k, int v) {
        p().edit().putInt(k, v).apply();
    }

    private static boolean gb(String k, boolean d) {
        return p().getBoolean(k, d);
    }

    private static void pb(String k, boolean v) {
        p().edit().putBoolean(k, v).apply();
    }

    // ---------- 大模型 ----------

    public static String apiKey() { return gs("api_key", ""); }
    public static void setApiKey(String v) { ps("api_key", v.trim()); }

    public static String baseUrl() { return gs("base_url", DEF_BASE_URL); }
    public static void setBaseUrl(String v) { ps("base_url", v.trim()); }

    public static String model() { return gs("model", DEF_MODEL); }
    public static void setModel(String v) { ps("model", v.trim()); }

    /**
     * 思考程度："" 不传 / low / medium / high。
     * <p>
     * OpenAI 兼容的 {@code reasoning_effort} 字段，DeepSeek、火山、硅基流动这些
     * 都认。空字符串时**整个字段都不发** —— 有些服务端碰上不认识的参数会直接 400，
     * 所以默认不发，用户自己去界面里挑。
     */
    public static String reasoningEffort() { return gs("reasoning_effort", "medium"); }
    public static void setReasoningEffort(String v) { ps("reasoning_effort", v == null ? "" : v.trim()); }

    public static String prompt() { return gs("prompt", DEF_PROMPT); }
    public static void setPrompt(String v) { ps("prompt", v); }

    // ---------- 取词 ----------

    /**
     * 默认的取词方式：<b>视觉优先</b> —— 先把整张截图发给多模态模型，它读不出来
     * （图是黑的、拿不到截图、模型没从图里认出题）再退到无障碍文本。
     * <p>
     * 为什么把这条当默认：截图里题干和选项是**一起**摆在那儿的，模型一眼看全；
     * 而 WebView 那套无障碍树常常只给光杆选项、还按行漏字，拼回来就缺胳膊少腿。
     */
    public static final int DEF_OCR_SOURCE = 4;

    /** 0 自动 / 1 仅无障碍 / 2 仅本地OCR / 3 仅视觉大模型 / 4 视觉优先（默认，读不到退无障碍） */
    public static int ocrSource() { return gi("ocr_source", DEF_OCR_SOURCE); }
    public static void setOcrSource(int v) { pi("ocr_source", v); }

    /** 0 自动 / 1 仅 Shizuku / 2 仅 MediaProjection */
    public static int shotSource() { return gi("shot_source", 0); }
    public static void setShotSource(int v) { pi("shot_source", v); }

    public static int cropTop() { return gi("crop_top", 3); }
    public static void setCropTop(int v) { pi("crop_top", clamp(v, 0, 45)); }

    public static int cropBottom() { return gi("crop_bottom", 0); }
    public static void setCropBottom(int v) { pi("crop_bottom", clamp(v, 0, 45)); }

    public static String visionUrl() { return gs("vision_url", "https://api.deepseek.com"); }
    public static void setVisionUrl(String v) { ps("vision_url", v.trim()); }

    public static String visionKey() { return gs("vision_key", ""); }
    public static void setVisionKey(String v) { ps("vision_key", v.trim()); }

    public static String visionModel() { return gs("vision_model", "deepseek-flash"); }
    public static void setVisionModel(String v) { ps("vision_model", v.trim()); }

    /**
     * 真正发出去时用的视觉模型地址 / 密钥 / 模型名：**没单独填就跟着主模型走**。
     * <p>
     * 现在同一个 key 往往就吃得下图片（DeepSeek 那套 OpenAI 兼容的 {@code image_url}
     * 就是），用户没必要再配第二份。上面那三个「原样」的留着给设置页显示 ——
     * 设置页要能如实反映「这一栏是不是空的」，不能替用户填上。
     * <p>
     * 想用别的多模态服务（通义、豆包……）就单独填，填了就以填的为准。
     */
    public static String effVisionUrl() { String s = visionUrl(); return s.isEmpty() ? baseUrl() : s; }
    public static String effVisionKey() { String s = visionKey(); return s.isEmpty() ? apiKey() : s; }
    public static String effVisionModel() { String s = visionModel(); return s.isEmpty() ? model() : s; }

    public static boolean hasVision() {
        return !effVisionUrl().isEmpty() && !effVisionKey().isEmpty() && !effVisionModel().isEmpty();
    }

    /**
     * 最近几屏「在哪道题上点了哪些字母」，一行一条，格式 {@code 题目指纹\u0001字母串}。
     * <p>
     * 存下来只为了一件事：别把页面上已经选好的答案**又点一遍**（WebView 的选项点一下
     * 就是取消）。见 {@code FloatingService.alreadyClicked}。
     * <p>
     * 早期只存一条（最近那一屏）。实测不够用：连答从最后一题回到上一题重读一次、
     * 或者用户自己点了「答题卡」跳回第 1 题，只要中间答过别的屏，这条记忆就被顶掉了，
     * 于是又对着已选中的选项点一遍 —— 多选题就这么被点没了。改存最近
     * {@link #CLICK_MEMO_MAX} 屏，来回翻页也不会重踩。
     */
    public static String lastClicks() { return gs("last_clicks", ""); }
    public static void setLastClicks(String v) { ps("last_clicks", v == null ? "" : v); }

    /** 点击记忆留几屏。一份指纹 + 几个字母，留着不占地方。 */
    private static final int CLICK_MEMO_MAX = 20;

    /** 记住「这一屏点过哪些字母」。同一屏只留最新那条（字母串是累加的，旧的没用了）。 */
    public static void rememberClicks(String key, String letters) {
        if (key == null || key.isEmpty() || letters == null || letters.isEmpty()) return;
        StringBuilder sb = new StringBuilder(key).append('\u0001').append(letters);
        int n = 1;
        for (String old : lastClicks().split("\n")) {
            if (old.isEmpty()) continue;
            int sep = old.indexOf('\u0001');
            if (sep <= 0) continue;
            if (old.substring(0, sep).equals(key)) continue;   // 同一屏
            if (n++ >= CLICK_MEMO_MAX) break;
            sb.append('\n').append(old);
        }
        setLastClicks(sb.toString());
    }

    /** 这一屏记不记得点过 {@code letter}。 */
    public static boolean clickedBefore(String key, String letter) {
        if (key == null || key.isEmpty() || letter == null || letter.isEmpty()) return false;
        for (String line : lastClicks().split("\n")) {
            int sep = line.indexOf('\u0001');
            if (sep <= 0) continue;
            if (line.substring(0, sep).equals(key) && line.indexOf(letter, sep + 1) >= 0) return true;
        }
        return false;
    }

    /** 当前配置是否可能需要截图 */
    public static boolean needsScreenshot() {
        int m = ocrSource();
        return m != 1;
    }

    /**
     * 无障碍这条路答不出来（读到的字不够、大模型给了「？」、请求失败）时，
     * 自动换截图 OCR 再读一遍、再问一次。
     */
    public static boolean fallbackOcr() { return gb("fallback_ocr", true); }
    public static void setFallbackOcr(boolean v) { pb("fallback_ocr", v); }

    /**
     * OCR 这条路答不出来时，自动换无障碍文本再读一遍、再问一次。
     * 和 {@link #fallbackOcr()} 是一对：哪个先、哪个兜底由「文本来源」决定。
     */
    public static boolean fallbackA11y() { return gb("fallback_a11y", true); }
    public static void setFallbackA11y(boolean v) { pb("fallback_a11y", v); }

    /**
     * 竖屏排列的长页面：这一屏的题答完、又找不到「下一题」按钮时，往下滑一屏接着答。
     * 一页七题这种卷子就是靠它一屏一屏往下走的 —— 不滑的话页面原地不动，
     * 上面的题会被反复答。
     */
    public static boolean autoScroll() { return gb("auto_scroll", true); }
    public static void setAutoScroll(boolean v) { pb("auto_scroll", v); }

    // ---------- 点击 ----------

    public static String labels() { return gs("labels", DEF_LABELS); }
    public static void setLabels(String v) { ps("labels", v.trim()); }

    public static String nextKeywords() { return gs("next_keywords", DEF_NEXT_KEYWORDS); }
    public static void setNextKeywords(String v) { ps("next_keywords", v); }

    /** 0 自动 / 1 仅无障碍 / 2 仅 Shizuku / 3 仅手势 */
    public static int clickSource() { return gi("click_source", 0); }
    public static void setClickSource(int v) { pi("click_source", v); }

    public static boolean autoClick() { return gb("auto_click", true); }
    public static void setAutoClick(boolean v) { pb("auto_click", v); }

    public static boolean autoNext() { return gb("auto_next", true); }
    public static void setAutoNext(boolean v) { pb("auto_next", v); }

    public static float nextDelaySec() { return p().getFloat("next_delay", 1.2f); }
    public static void setNextDelaySec(float v) { p().edit().putFloat("next_delay", v).apply(); }

    /**
     * 自动连答：答完一题后，不管有没有点掉「下一题」，都等 n 秒自己再读一次屏接着答。
     * 有些 App 的「下一题」是个图标/被遮挡/关键词对不上，点不中也不会自己翻页，
     * 光靠「自动点下一题」会卡死在那儿；这个开关是兜底。
     */
    public static boolean autoLoop() { return gb("auto_loop", true); }
    public static void setAutoLoop(boolean v) { pb("auto_loop", v); }

    /** 自动连答的间隔（秒）。这是「答完这题」到「开始读下一题」的等待，别设太小。 */
    public static float loopDelaySec() { return p().getFloat("loop_delay", 2f); }
    public static void setLoopDelaySec(float v) { p().edit().putFloat("loop_delay", v).apply(); }

    public static int clickGapMs() { return gi("click_gap", 260); }
    public static void setClickGapMs(int v) { pi("click_gap", clamp(v, 0, 5000)); }

    // ---------- 填空题 ----------

    /**
     * 填空题：让模型把要作答的正文写进 fill 字段，程序收拾成纯文本塞进剪贴板，
     * 页面上要是有选项也顺手点一遍。
     */
    public static boolean fillCopy() { return gb("fill_copy", true); }
    public static void setFillCopy(boolean v) { pb("fill_copy", v); }

    /**
     * 填空题：把答案按顺序直接打进页面上**空着的**输入框（无障碍 ACTION_SET_TEXT）。
     * <p>
     * 默认开。一条大题里有好几个空时，模型的 blanks 数组就是空在题面上的先后顺序，
     * 这里照着顺序一个框一个框填。已经有字的框不动 —— 用户自己填过的不覆盖，
     * 往下滑过一屏之后也不会把上一屏填过的答案再填一遍。
     */
    public static boolean autoFill() { return gb("auto_fill", true); }
    public static void setAutoFill(boolean v) { pb("auto_fill", v); }

    /**
     * 填空题填完之后：0 = 暂停回答（默认），大于 0 = 等这么多秒再接着答下一题。
     * <p>
     * 默认暂停是因为正文要留给用户去粘贴，页面这时候多半还停在原题上，
     * 自动连答接着跑只会把这一屏再答一遍、把刚点上的选项又点掉。
     */
    public static int fillWaitSec() { return gi("fill_wait_sec", 1); }
    public static void setFillWaitSec(int v) { pi("fill_wait_sec", clamp(v, 0, 300)); }

    // ---------- 展示 ----------

    public static int notifSec() { return gi("notif_sec", 8); }
    public static void setNotifSec(int v) { pi("notif_sec", clamp(v, 1, 600)); }

    public static int bubbleSec() { return gi("bubble_sec", 6); }
    public static void setBubbleSec(int v) { pi("bubble_sec", clamp(v, 1, 600)); }

    public static boolean tts() { return gb("tts", false); }
    public static void setTts(boolean v) { pb("tts", v); }

    /** 语音除了念答案，再念一遍解析。念之前会把上一句掐掉，不会排成一条长队。 */
    public static boolean ttsExplanation() { return gb("tts_explanation", false); }
    public static void setTtsExplanation(boolean v) { pb("tts_explanation", v); }

    public static boolean bubble() { return gb("bubble", true); }
    public static void setBubble(boolean v) { pb("bubble", v); }

    /** 答案通知（状态栏那条带解析的）。和下面的常驻通知是两回事，各关各的。 */
    public static boolean notifyAnswer() { return gb("notify_answer", true); }
    public static void setNotifyAnswer(boolean v) { pb("notify_answer", v); }

    /**
     * 是否保留「悬浮球已就绪」那条常驻状态栏通知。默认**不留**：答题时头顶一直挂着
     * 一条通知很碍事，而且截屏取词时它还可能被拍进去。
     * <p>
     * 关掉之后服务换成普通 startService 跑，靠无障碍服务把进程吊着；要用
     * MediaProjection 截图时（系统强制要求前台服务）会临时把它打开。
     */
    public static boolean keepServiceNotif() { return gb("keep_service_notif", false); }
    public static void setKeepServiceNotif(boolean v) { pb("keep_service_notif", v); }

    /** 解析气泡位置预置：0 左上 1 右上 2 左下 3 右下 4 中间 5 自定义（用下面的百分比）。 */
    public static int bubblePos() { return gi("bubble_pos", 2); }
    public static void setBubblePos(int v) { pi("bubble_pos", clamp(v, 0, 5)); }

    /** 自定义位置，屏幕宽/高的百分比 0~100。 */
    public static int bubbleX() { return gi("bubble_x", 5); }
    public static void setBubbleX(int v) { pi("bubble_x", clamp(v, 0, 100)); }

    public static int bubbleY() { return gi("bubble_y", 25); }
    public static void setBubbleY(int v) { pi("bubble_y", clamp(v, 0, 100)); }

    /**
     * 悬浮球不透明度。下限锁在 80：Android 12 起低于 0.8 的悬浮窗会被判定为
     * 不可信窗口，触摸事件可能不再派发给它（实测 0.36 时球完全点不动）。
     */
    public static int floatAlpha() { return gi("float_alpha", 90); }
    public static void setFloatAlpha(int v) { pi("float_alpha", clamp(v, 80, 100)); }

    public static int floatX() { return gi("float_x", 1040); }
    public static int floatY() { return gi("float_y", 3); }

    public static void setFloatXY(int x, int y) {
        p().edit().putInt("float_x", x).putInt("float_y", y).apply();
    }

    /** 悬浮球静止多少秒后自动缩到屏幕边缘，0 = 不自动隐藏 */
    public static int autoHideSec() { return gi("auto_hide_sec", 10); }
    public static void setAutoHideSec(int v) { pi("auto_hide_sec", clamp(v, 0, 300)); }

    // ---------- 其它 ----------

    public static int timeoutSec() { return gi("timeout", 60); }
    public static void setTimeoutSec(int v) { pi("timeout", clamp(v, 5, 300)); }

    public static boolean hideOverlayOnShot() { return gb("hide_on_shot", true); }
    public static void setHideOverlayOnShot(boolean v) { pb("hide_on_shot", v); }

    public static boolean logRaw() { return gb("log_raw", true); }
    public static void setLogRaw(boolean v) { pb("log_raw", v); }

    // ---------- 工具 ----------

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static String[] splitList(String s) {
        if (s == null) return new String[0];
        String[] parts = s.split("[,，\\n\\r;；|]+");
        int n = 0;
        for (String x : parts) if (!x.trim().isEmpty()) n++;
        String[] out = new String[n];
        int i = 0;
        for (String x : parts) {
            String t = x.trim();
            if (!t.isEmpty()) out[i++] = t;
        }
        return out;
    }
}
