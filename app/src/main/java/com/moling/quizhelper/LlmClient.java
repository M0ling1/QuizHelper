package com.moling.quizhelper;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** OpenAI 兼容 chat/completions 客户端（DeepSeek 走的就是这套）。纯 HttpURLConnection，无第三方依赖。 */
public final class LlmClient {

    private static final String TAG = "LlmClient";

    private LlmClient() {
    }

    /**
     * 思维链把 max_tokens 花完、结论还没写出来时改用的直答模型。
     * deepseek-chat 不做思维链，同样的题目几秒就能出 JSON。
     */
    private static final String FALLBACK_MODEL = "deepseek-chat";

    public static class Reply {
        public boolean ok;
        public String content = "";
        public String error = "";
        public long costMs;
        /** 回复被 max_tokens 截断（finish_reason=length）。 */
        public boolean truncated;
        /** 这次是拿思维链（reasoning_content）当答案用的 —— 说明 content 是空的。 */
        public boolean usedReasoning;
        /** 兜底/降级的说明，非空时值得看一眼。 */
        public String note = "";
        /**
         * 这次失败值不值得重试。
         * <p>
         * 超时、断网、5xx、限流、返回体缺胳膊少腿 —— 都是「这一下没成」，同一份请求
         * 再发一次很可能就通了，{@link FloatingService} 会照着这个标志重试。
         * 而「没填 API Key」「401 余额不足」「400 参数错」这类再发一百次也是同一个结果，
         * 重试只是把用户的等待时间乘三，标 false。
         */
        public boolean retryable;
    }

    /**
     * 填空题的额外要求。**临时拼在系统提示词后面**，不改用户存的那份 ——
     * 用户的提示词是他自己写的，我们没道理去改；而这个约定是程序用得到的，
     * 开关一关就该消失，所以每次请求现拼。
     */
    private static final String FILL_RULES =
            "\n\n【填空题】页面上没有 A/B/C 选项、或者要你写一段文字作答的，就是填空题，"
                    + "作答正文放进 fill。**一道题里有几个空**（题面上有几条下划线/几个输入框），"
                    + "就再给一个 blanks 数组：按空在题面上从左到右、从上到下的先后顺序，一个空一项：\n"
                    + "{\"answer\":\"\",\"fill\":\"1. 甲 2. 乙\",\"blanks\":[\"甲\",\"乙\"],"
                    + "\"explanation\":\"不超过40字的极简解析\"}\n"
                    + "只有一个空的也放一项（[\"甲\"]）。**几个空就给几项，不许漏、不许合并**；"
                    + "某个空实在答不出就放空字符串占位，别把后面的答案往前挪 —— "
                    + "程序按顺序一个空一个框地填，串一个空整题就错位了。\n"
                    + "blanks 里只放**填进那个空里的字**：不要题号、不要「答案：」、不要解释、不要 markdown。"
                    + "fill 是拼给人看/复制的合并正文，多个空用「1. 甲」「2. 乙」分行。\n"
                    + "**题面上用「1.____」「2.____」标空、下面又列出「1. □」「2. □」作答框的**："
                    + "编号只是标位置，只按题目真正要填的空数给 blanks —— 几个作答框就几项，"
                    + "别把题面编号和作答框各算一遍，也别把编号写进答案。\n"
                    + "**一页上有好几道这样的大题时**：每道大题各占 items 里的一条，"
                    + "各自带自己的 blanks，**绝不跨题合并、也绝不把上一题的答案写进下一题**。"
                    + "哪怕两道大题题干紧挨着、格式一模一样，也按屏幕从上到下的顺序分开写：\n"
                    + "{\"items\":[{\"n\":1,\"q\":\"中国的首都是\",\"answer\":\"\","
                    + "\"fill\":\"1. 北京 2. 长江\",\"blanks\":[\"北京\",\"长江\"],\"explanation\":\"…\"},"
                    + "{\"n\":2,\"q\":\"1+1=\",\"answer\":\"\",\"fill\":\"1. 2 2. 9\","
                    + "\"blanks\":[\"2\",\"9\"],\"explanation\":\"…\"}]}\n"
                    + "程序按每题 blanks 的个数找屏幕上对应的空，一道大题的 blanks 少了或多了，"
                    + "后面所有大题都会整体错位。\n"
                    + "选择题 fill 留空字符串（\"\"）、blanks 给空数组 []。"
                    + "填空题如果同时还给了候选选项，answer 里填该选的字母，blanks 里给正文。\n"
                    + "**只答屏幕上完整看得见的空**：题干或者空被屏幕边缘裁掉、只露一半的，"
                    + "只答完整看得见的那些；整题都看不全就 answer 填 \"?\"、blanks 给 []，"
                    + "等程序往下滑一屏再答。绝不要凭半截题干猜整题。";

    /**
     * 答题的机械约定。**每次都拼**，不像 {@link #FILL_RULES} 跟着开关走 ——
     * 这两条是程序用得到的：一屏多题时要按题号一组一组地点（点错组就等于点到别的题上），
     * 单选题多给字母会把选项点乱。用户自己的提示词里写没写都得有。
     */
    private static final String ANSWER_RULES =
            "\n\n【一屏多题】屏幕上可能同时有好几道题（竖排）。请**从上到下按顺序**逐题解答，"
                    + "一道题一条，不要漏、不要合并、不要串题 —— 第 2 题的答案绝不能写成第 1 题的。"
                    + "所有题放进 items 数组，数组顺序就是屏幕上的先后顺序：\n"
                    + "{\"items\":[{\"n\":1,\"q\":\"题干开头\",\"answer\":\"A\",\"multi\":false,"
                    + "\"explanation\":\"…\"},{\"n\":2,\"q\":\"题干开头\",\"answer\":\"AC\","
                    + "\"multi\":true,\"explanation\":\"…\"}]}\n"
                    + "n 是这道题在屏幕上从上往下的次序，从 1 开始数：题面自己写了「1.」「2.」就用它，"
                    + "没写编号的（纯竖着排的题）照样按第 1、2、3… 数下去，不能空着。\n"
                    + "q 是这道题的**题干原文**：把屏幕上那一行题干**逐字抄下来**（照抄，别改写、别翻译、"
                    + "别总结、别加题号），8～20 个字就够。程序拿它把答案对到屏幕上的选项组 —— "
                    + "抄得准就不会点错题。**题干压根没显示出来**（滚到屏幕外了）的，q 填空字符串 \"\"，"
                    + "绝不许自己编一句。\n"
                    + "屏幕上只有一道题时也用这个格式，items 里就一条。\n"
                    + "**只答屏幕上看得见的题**：屏幕外、被滚动条挡住的、只露一半看不清的，一律不要猜、"
                    + "不要编进 items —— 程序按题号去找对应的选项，多一条就会串位。\n"
                    + "选项标签照**页面上印的那套**来：页面写 ①②③ 就回「①」，写全角 ＡＢ 就回「Ａ」，"
                    + "写日文 ア/イ/ウ 就回假名 —— 原样抄，不要自己换算成 ABC。\n"
                    + "题干里的日语、希腊字母（α β γ）、化学式（H₂O / CO₂）、医学缩写照原样理解，"
                    + "不认识也不能跳过这题，按你掌握的知识正常作答。\n"
                    + "【单选还是多选】以题面上的文字为准：写了「单选」「单项选择题」的，这一题只给一个字母，"
                    + "multi 填 false；写了「多选」「多项选择题」「不定项」的才给多个字母，multi 填 true。"
                    + "题面没写又判断不出来时 multi 填 false、只给一个字母 —— 单选题多给字母会把选项点乱。\n"
                    + "【选项没显示全就别答这题，答案填 \"?\"】屏幕文字是 OCR / 无障碍抠出来的，漏字是常态。"
                    + "一道题能不能答，**只看它的选项齐不齐**：题干在、选项标签从第一个连续排到最后"
                    + "（A B C D…）、每条选项都带着正文，才答。只要碰上下面任何一种，这题就填 \"?\"：\n"
                    + "  · 只读到题干，一个选项都没有；\n"
                    + "  · 只读到「A. 3」「B. 4」一截，后面的选项没出来（标签序列断在中间）；\n"
                    + "  · 只有光杆标签 A B C D，选项正文读不到；\n"
                    + "  · 选项被滚动条挡住、被通知横幅压住、只露一半。\n"
                    + "**这种题绝对不许猜字母、不许按位置蒙**：猜错的字母会被程序真的点下去，"
                    + "等这题完整显示出来、正确答案再点一下，屏幕上就同时留着两个选项，等于答错。"
                    + "填 \"?\" 不会漏掉它 —— 下一轮它完整显示出来时程序会再答一次。\n"
                    + "但**别因为「选项不全」就把它当成填空题**：那只是没读全，不是要你写一段文字。"
                    + "fill 只在页面确实要求作答一段文字时才用。\n"
                    + "这类答不了的题照样按它在屏幕上的位置编进 items（answer 填 \"?\"），"
                    + "别把它从数组里删掉 —— 删了后面的题号就会整体错位、答案点到别的题上。\n"
                    + "只有连题目在问什么都看不出来（题干本身就是半截）才发现 answer 填 \"?\"。";

    private static String systemPrompt() {
        return Prefs.prompt() + ANSWER_RULES
                + ((Prefs.fillCopy() || Prefs.autoFill()) ? FILL_RULES : "");
    }

    public static Reply ask(String ocrText) {
        String key = Prefs.apiKey();
        if (key.isEmpty()) {
            Reply r = new Reply();
            r.error = "还没填 API Key，去设置里填一下";
            return r;
        }
        JSONArray messages = new JSONArray();
        try {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt()));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", ocrText));
        } catch (Exception e) {
            Reply r = new Reply();
            r.error = "构造请求失败: " + e;
            return r;
        }
        Reply r = post(Prefs.baseUrl(), key, Prefs.model(), buildBody(messages, MAX_TOKENS));

        // reasoning 模型（deepseek-reasoner 之类）碰到难题会把 token 预算整个烧在思维链上，
        // 还没写出最后那行 JSON 就被截断 —— 表现就是用户看到的「大模型反馈空」。
        // 这里换直答模型重问一遍，题目文本还在，多花几秒但能拿到答案。
        //
        // usedReasoning（content 空、只剩思维链）同样得重问。实测思维链里全是
        // 「A 还是 B？……嗯 D 应该是东」这种自言自语，解析器从里面抠出来的字母纯属撞运气：
        // 有一轮就这么抠出个「1.B」，答的还不是屏幕上那道题。直答模型给的是正正经经的 JSON。
        if ((r.truncated || r.usedReasoning) && !FALLBACK_MODEL.equals(Prefs.model())) {
            Reply r2 = post(Prefs.baseUrl(), key, FALLBACK_MODEL, buildBody(messages, MAX_TOKENS));
            if (r2.ok && !r2.truncated && !r2.usedReasoning) {
                r2.note = (r.usedReasoning ? "首答只有思维链、没有结论（content 为空），"
                        : "首答被截断（思维链吃光 token），")
                        + "已换 " + FALLBACK_MODEL + " 重问";
                long first = r.costMs;
                r2.costMs += first;
                return r2;
            }
        }
        return r;
    }

    /** 直接把截图发给多模态模型，让它一次完成“读题 + 作答”。 */
    public static Reply askVision(Bitmap bmp) {
        if (!Prefs.hasVision()) {
            Reply r = new Reply();
            r.error = "没填 API Key，视觉这条路发不出去";
            return r;
        }
        try {
            String b64 = toJpegBase64(bmp, 1600);
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", systemPrompt()));
            content.put(new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", content));

            // 视觉模型对 max_tokens 的上限各家不一样，给保守值
            return post(Prefs.effVisionUrl(), Prefs.effVisionKey(), Prefs.effVisionModel(),
                    buildBody(messages, 1024));
        } catch (Exception e) {
            Reply r = new Reply();
            r.error = "图片编码失败: " + e;
            return r;
        }
    }

    /**
     * 输出预算。deepseek-reasoner 的思维链**也算在这个额度里** —— 之前给 700，
     * 难题光推理就用完了，content 一个字都没有。实测一道冷门游戏题思维链要三千多字，
     * 所以直接给到 8192。
     */
    private static final int MAX_TOKENS = 8192;

    private static String buildBody(JSONArray messages, int maxTokens) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", Prefs.model());
            body.put("messages", messages);
            body.put("temperature", 0);
            body.put("stream", false);
            body.put("max_tokens", maxTokens);
            // 思考程度：不填就一个字段都不发（有的服务端见到不认识的参数直接 400）
            String effort = Prefs.reasoningEffort();
            if (effort != null && !effort.isEmpty()) body.put("reasoning_effort", effort);
        } catch (Exception ignored) {
        }
        return body.toString();
    }

    private static Reply post(String baseUrl, String key, String model, String body) {
        Reply r = new Reply();
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String url = endpoint(baseUrl);
            // model 以参数传入可能和 Prefs 不同（视觉模型），这里按参数覆盖
            JSONObject b = new JSONObject(body);
            if (model != null && !model.isEmpty()) b.put("model", model);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(Prefs.timeoutSec() * 1000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "QuizHelper/1.0");

            byte[] payload = b.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);

            if (code < 200 || code >= 300) {
                r.error = "HTTP " + code + "：" + shorten(text, 260);
                // 4xx 是「你这请求本身有问题」：401 没 key、402 余额不足、400 参数错、
                // 404 模型名写错。原样再发一次还是同样的答复，重试纯属耽误时间。
                // 408 请求超时、409 冲突、429 限流，以及 5xx 服务端抖动 —— 这些值得再试。
                r.retryable = code == 408 || code == 409 || code == 429 || code >= 500;
                return r;
            }

            JSONObject o = new JSONObject(text);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                r.error = "返回里没有 choices：" + shorten(text, 200);
                r.retryable = true;        // 网关/中转偶发吐半截，再问一次通常就有
                return r;
            }
            JSONObject choice0 = choices.getJSONObject(0);
            JSONObject msg = choice0.optJSONObject("message");
            String content = msg != null ? msg.optString("content", "") : "";
            String reasoning = msg != null ? msg.optString("reasoning_content", "") : "";
            String finish = choice0.optString("finish_reason", "");

            if (content.trim().isEmpty()) {
                // deepseek-reasoner 这类模型会把思维链放在 reasoning_content 里。
                // max_tokens 被思维链吃光时 content 就是空的，只能退而求其次用思维链兜底
                // （它末尾通常就是结论），同时把 max_tokens 调大从根上避免。
                if (!reasoning.trim().isEmpty()) {
                    r.ok = true;
                    r.content = reasoning.trim();
                    r.truncated = "length".equals(finish);
                    r.usedReasoning = true;
                    r.note = "content 为空，已改用 reasoning_content";
                    return r;
                }
                r.error = "模型返回了空内容"
                        + (finish.isEmpty() ? "" : "（finish_reason=" + finish + "）")
                        + "：" + shorten(text, 200);
                r.retryable = true;        // 空回复是典型的偶发，重问一次多半就有了
                return r;
            }
            r.ok = true;
            r.content = content.trim();
            r.truncated = "length".equals(finish);
            if (r.truncated) r.note = "回复被 max_tokens 截断（finish_reason=length）";
            return r;
        } catch (Exception e) {
            Log.w(TAG, "post failed", e);
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            // 超时 / 连接被断 / DNS 抽风都在这一类里 —— 正是最该重试的
            r.retryable = true;
            return r;
        } finally {
            r.costMs = System.currentTimeMillis() - t0;
            if (conn != null) conn.disconnect();
        }
    }

    /** 自检：要一句“1+1=2”，确认 key / 网络 / 模型都通。 */
    public static Reply ping() {
        JSONArray messages = new JSONArray();
        try {
            messages.put(new JSONObject().put("role", "user").put("content", "只回复两个字：正常"));
        } catch (Exception ignored) {
        }
        // 别给太小：reasoner 光思维链就能把一两百 token 花掉，自检会假报警
        return post(Prefs.baseUrl(), Prefs.apiKey(), Prefs.model(), buildBody(messages, MAX_TOKENS));
    }

    private static String endpoint(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) b = Prefs.DEF_BASE_URL;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    public static String toJpegBase64(Bitmap bmp, int maxEdge) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = 1f;
        int longer = Math.max(w, h);
        if (longer > maxEdge) scale = maxEdge / (float) longer;
        Bitmap use = bmp;
        if (scale < 1f) {
            use = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        use.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        if (use != bmp) use.recycle();
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
