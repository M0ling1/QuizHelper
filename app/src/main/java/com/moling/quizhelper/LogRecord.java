package com.moling.quizhelper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogRecord {
    public long time;
    public String source = "";
    public String question = "";
    public String answer = "";
    /** 填空题的正文（已经收拾成纯文本，也复制进剪贴板的那一份）。 */
    public String fill = "";
    public String explanation = "";
    public long costMs;
    public int clicked = -1;   // -1 未尝试 / >=0 成功点击的选项数
    public String error = "";

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    public String timeText() {
        return FMT.format(new Date(time));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("t", time);
            o.put("s", source);
            o.put("q", question);
            o.put("a", answer);
            o.put("f", fill);
            o.put("e", explanation);
            o.put("c", costMs);
            o.put("k", clicked);
            o.put("x", error);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static LogRecord fromJson(String line) {
        try {
            JSONObject o = new JSONObject(line);
            LogRecord r = new LogRecord();
            r.time = o.optLong("t");
            r.source = o.optString("s", "");
            r.question = o.optString("q", "");
            r.answer = o.optString("a", "");
            r.fill = o.optString("f", "");
            r.explanation = o.optString("e", "");
            r.costMs = o.optLong("c");
            r.clicked = o.optInt("k", -1);
            r.error = o.optString("x", "");
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}
