package com.moling.quizhelper;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private EditText etApiKey, etBaseUrl, etModel, etPrompt;
    private Spinner spOcr, spShot, spClick;
    private Spinner spEffort;
    private EditText etCropTop, etCropBottom;
    private EditText etVisionUrl, etVisionKey, etVisionModel;
    private EditText etLabels, etNextKeywords;
    private Switch swAutoClick, swAutoNext, swAutoLoop;
    private Switch swAutoScroll, swFallbackOcr, swFallbackA11y;
    private EditText etNextDelay, etClickGap, etLoopDelay;
    private Switch swFillCopy, swAutoFill;
    private EditText etFillWait;
    private EditText etNotifSec, etBubbleSec, etAlpha, etAutoHide, etTimeout;
    private Switch swTts, swTtsExplain, swBubble, swHideOverlayShot;
    private Switch swNotifOn, swKeepNotif;
    private Spinner spBubblePos;
    private EditText etBubbleX, etBubbleY;
    private TextView tvSelfTest;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        setContentView(R.layout.activity_settings);

        etApiKey = findViewById(R.id.etApiKey);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etModel = findViewById(R.id.etModel);
        etPrompt = findViewById(R.id.etPrompt);
        spOcr = findViewById(R.id.spOcr);
        spShot = findViewById(R.id.spShot);
        spClick = findViewById(R.id.spClick);
        spEffort = findViewById(R.id.spEffort);
        etCropTop = findViewById(R.id.etCropTop);
        etCropBottom = findViewById(R.id.etCropBottom);
        etVisionUrl = findViewById(R.id.etVisionUrl);
        etVisionKey = findViewById(R.id.etVisionKey);
        etVisionModel = findViewById(R.id.etVisionModel);
        etLabels = findViewById(R.id.etLabels);
        etNextKeywords = findViewById(R.id.etNextKeywords);
        swAutoClick = findViewById(R.id.swAutoClick);
        swAutoNext = findViewById(R.id.swAutoNext);
        swAutoLoop = findViewById(R.id.swAutoLoop);
        swAutoScroll = findViewById(R.id.swAutoScroll);
        swFallbackOcr = findViewById(R.id.swFallbackOcr);
        swFallbackA11y = findViewById(R.id.swFallbackA11y);
        etNextDelay = findViewById(R.id.etNextDelay);
        etClickGap = findViewById(R.id.etClickGap);
        etLoopDelay = findViewById(R.id.etLoopDelay);
        swFillCopy = findViewById(R.id.swFillCopy);
        swAutoFill = findViewById(R.id.swAutoFill);
        etFillWait = findViewById(R.id.etFillWait);
        spBubblePos = findViewById(R.id.spBubblePos);
        etBubbleX = findViewById(R.id.etBubbleX);
        etBubbleY = findViewById(R.id.etBubbleY);
        etNotifSec = findViewById(R.id.etNotifSec);
        etBubbleSec = findViewById(R.id.etBubbleSec);
        etAlpha = findViewById(R.id.etAlpha);
        etAutoHide = findViewById(R.id.etAutoHide);
        etTimeout = findViewById(R.id.etTimeout);
        swTts = findViewById(R.id.swTts);
        swTtsExplain = findViewById(R.id.swTtsExplain);
        swNotifOn = findViewById(R.id.swNotifOn);
        swKeepNotif = findViewById(R.id.swKeepNotif);
        swBubble = findViewById(R.id.swBubble);
        swHideOverlayShot = findViewById(R.id.swHideOverlayShot);
        tvSelfTest = findViewById(R.id.tvSelfTest);

        bindSpinner(spOcr, R.array.ocr_source_entries, Prefs.ocrSource());
        bindSpinner(spShot, R.array.shot_source_entries, Prefs.shotSource());
        bindSpinner(spClick, R.array.click_source_entries, Prefs.clickSource());
        bindSpinner(spBubblePos, R.array.bubble_pos_entries, Prefs.bubblePos());
        bindSpinner(spEffort, R.array.effort_entries, effortIndex(Prefs.reasoningEffort()));

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            save();
            toast("已保存");
            finish();
        });
        findViewById(R.id.btnReset).setOnClickListener(v -> resetToDefault());
        findViewById(R.id.btnSelfTest).setOnClickListener(v -> selfTest());

        // 水印里的链接：点一下去 B 站主页。没有浏览器就只提示，不让它崩。
        findViewById(R.id.tvAuthorLink).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://space.bilibili.com/363939282")));
            } catch (Exception e) {
                toast("没找到能打开网页的应用");
            }
        });

        load();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    /**
     * 思考程度：下拉的**位置就是值**（见 {@code effort_entries}）——
     * 0 不传、1 low、2 medium、3 high。认不出来的写法（比如服务端要求 "minimal"）
     * 就当没设，别硬塞一个它会 400 的值。
     */
    private static int effortIndex(String v) {
        if ("low".equals(v)) return 1;
        if ("medium".equals(v)) return 2;
        if ("high".equals(v)) return 3;
        return 0;
    }

    private static String effortValue(int index) {
        switch (index) {
            case 1: return "low";
            case 2: return "medium";
            case 3: return "high";
            default: return "";
        }
    }

    private void bindSpinner(Spinner sp, int arrayRes, int selected) {
        ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(
                this, arrayRes, android.R.layout.simple_spinner_item);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        if (selected >= 0 && selected < a.getCount()) sp.setSelection(selected);
    }

    private void load() {
        etApiKey.setText(Prefs.apiKey());
        etBaseUrl.setText(Prefs.baseUrl());
        etModel.setText(Prefs.model());
        etPrompt.setText(Prefs.prompt());
        etCropTop.setText(String.valueOf(Prefs.cropTop()));
        etCropBottom.setText(String.valueOf(Prefs.cropBottom()));
        etVisionUrl.setText(Prefs.visionUrl());
        etVisionKey.setText(Prefs.visionKey());
        etVisionModel.setText(Prefs.visionModel());
        etLabels.setText(Prefs.labels());
        etNextKeywords.setText(Prefs.nextKeywords().replace(",", "\n"));
        swAutoClick.setChecked(Prefs.autoClick());
        swAutoNext.setChecked(Prefs.autoNext());
        swAutoLoop.setChecked(Prefs.autoLoop());
        swAutoScroll.setChecked(Prefs.autoScroll());
        swFallbackOcr.setChecked(Prefs.fallbackOcr());
        swFallbackA11y.setChecked(Prefs.fallbackA11y());
        etNextDelay.setText(trimNum(Prefs.nextDelaySec()));
        etClickGap.setText(String.valueOf(Prefs.clickGapMs()));
        etLoopDelay.setText(trimNum(Prefs.loopDelaySec()));
        swFillCopy.setChecked(Prefs.fillCopy());
        swAutoFill.setChecked(Prefs.autoFill());
        etFillWait.setText(String.valueOf(Prefs.fillWaitSec()));
        etBubbleX.setText(String.valueOf(Prefs.bubbleX()));
        etBubbleY.setText(String.valueOf(Prefs.bubbleY()));
        etNotifSec.setText(String.valueOf(Prefs.notifSec()));
        etBubbleSec.setText(String.valueOf(Prefs.bubbleSec()));
        etAlpha.setText(String.valueOf(Prefs.floatAlpha()));
        etAutoHide.setText(String.valueOf(Prefs.autoHideSec()));
        etTimeout.setText(String.valueOf(Prefs.timeoutSec()));
        swTts.setChecked(Prefs.tts());
        swTtsExplain.setChecked(Prefs.ttsExplanation());
        swBubble.setChecked(Prefs.bubble());
        swNotifOn.setChecked(Prefs.notifyAnswer());
        swKeepNotif.setChecked(Prefs.keepServiceNotif());
        swHideOverlayShot.setChecked(Prefs.hideOverlayOnShot());
    }

    private static String trimNum(float f) {
        if (f == Math.round(f)) return String.valueOf((int) f);
        return String.valueOf(f);
    }

    private void resetToDefault() {
        etBaseUrl.setText(Prefs.DEF_BASE_URL);
        etModel.setText(Prefs.DEF_MODEL);
        etPrompt.setText(Prefs.DEF_PROMPT);
        etLabels.setText(Prefs.DEF_LABELS);
        etNextKeywords.setText(Prefs.DEF_NEXT_KEYWORDS.replace(",", "\n"));
        etCropTop.setText("3");
        etCropBottom.setText("0");
        etNextDelay.setText("1.2");
        etClickGap.setText("260");
        etNotifSec.setText("8");
        etBubbleSec.setText("6");
        etAlpha.setText("90");
        etAutoHide.setText("10");
        etTimeout.setText("60");
        spOcr.setSelection(Prefs.DEF_OCR_SOURCE);
        spShot.setSelection(0);
        spClick.setSelection(0);
        swAutoClick.setChecked(true);
        swAutoNext.setChecked(true);
        swAutoLoop.setChecked(true);
        swAutoScroll.setChecked(true);
        swFallbackOcr.setChecked(true);
        swFallbackA11y.setChecked(true);
        spEffort.setSelection(2);
        etLoopDelay.setText("2");
        swFillCopy.setChecked(true);
        swAutoFill.setChecked(true);
        etFillWait.setText("1");
        etBubbleX.setText("5");
        etBubbleY.setText("25");
        spBubblePos.setSelection(2);
        swTts.setChecked(false);
        swTtsExplain.setChecked(false);
        swBubble.setChecked(true);
        swNotifOn.setChecked(true);
        swKeepNotif.setChecked(false);
        swHideOverlayShot.setChecked(true);
        toast("已恢复默认值（还要点「保存」才生效）");
    }

    private void save() {
        Prefs.setApiKey(etApiKey.getText().toString());
        Prefs.setBaseUrl(etBaseUrl.getText().toString());
        Prefs.setModel(etModel.getText().toString());
        Prefs.setPrompt(etPrompt.getText().toString());
        Prefs.setOcrSource(spOcr.getSelectedItemPosition());
        Prefs.setShotSource(spShot.getSelectedItemPosition());
        Prefs.setClickSource(spClick.getSelectedItemPosition());
        Prefs.setCropTop(parseInt(etCropTop, 3));
        Prefs.setCropBottom(parseInt(etCropBottom, 0));
        Prefs.setVisionUrl(etVisionUrl.getText().toString());
        Prefs.setVisionKey(etVisionKey.getText().toString());
        Prefs.setVisionModel(etVisionModel.getText().toString());
        Prefs.setLabels(etLabels.getText().toString());
        Prefs.setNextKeywords(etNextKeywords.getText().toString());
        Prefs.setAutoClick(swAutoClick.isChecked());
        Prefs.setAutoNext(swAutoNext.isChecked());
        Prefs.setAutoLoop(swAutoLoop.isChecked());
        Prefs.setAutoScroll(swAutoScroll.isChecked());
        Prefs.setFallbackOcr(swFallbackOcr.isChecked());
        Prefs.setFallbackA11y(swFallbackA11y.isChecked());
        Prefs.setReasoningEffort(effortValue(spEffort.getSelectedItemPosition()));
        Prefs.setNextDelaySec(parseFloat(etNextDelay, 1.2f));
        Prefs.setClickGapMs(parseInt(etClickGap, 260));
        Prefs.setLoopDelaySec(parseFloat(etLoopDelay, 2f));
        Prefs.setFillCopy(swFillCopy.isChecked());
        Prefs.setAutoFill(swAutoFill.isChecked());
        Prefs.setFillWaitSec(parseInt(etFillWait, 1));
        Prefs.setBubblePos(spBubblePos.getSelectedItemPosition());
        Prefs.setBubbleX(parseInt(etBubbleX, 5));
        Prefs.setBubbleY(parseInt(etBubbleY, 25));
        Prefs.setNotifSec(parseInt(etNotifSec, 8));
        Prefs.setBubbleSec(parseInt(etBubbleSec, 6));
        Prefs.setFloatAlpha(parseInt(etAlpha, 90));
        Prefs.setAutoHideSec(parseInt(etAutoHide, 10));
        Prefs.setTimeoutSec(parseInt(etTimeout, 60));
        Prefs.setTts(swTts.isChecked());
        Prefs.setTtsExplanation(swTtsExplain.isChecked());
        Prefs.setBubble(swBubble.isChecked());
        Prefs.setNotifyAnswer(swNotifOn.isChecked());
        Prefs.setKeepServiceNotif(swKeepNotif.isChecked());
        Prefs.setHideOverlayOnShot(swHideOverlayShot.isChecked());
    }

    private int parseInt(EditText et, int def) {
        try {
            String s = et.getText().toString().trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private float parseFloat(EditText et, float def) {
        try {
            String s = et.getText().toString().trim();
            return s.isEmpty() ? def : Float.parseFloat(s);
        } catch (Throwable t) {
            return def;
        }
    }

    // ---------------- 自检 ----------------

    private void selfTest() {
        showSelfTest("自检中…");
        // 先把要用的输入框内容在主线程取出来，后台线程不碰 View
        final String labelsCsv = etLabels.getText().toString();
        final String nextCsv = etNextKeywords.getText().toString();
        save();

        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();

            // 0) 解析自检：写死的「模型回复」过一遍解析器。纯本地、不花额度，
            //    这两条正是踩过的坑（单选被点成多选、一屏多题全点到第一题上），先看这个。
            sb.append("解析自检（本地）：\n");
            parseChecks(sb);
            sb.append('\n');

            // 1) 无障碍
            QuizAccessibilityService svc = QuizAccessibilityService.get();
            sb.append("无障碍服务：").append(svc != null ? "已连接" : "未开启").append('\n');

            // 2) 取词
            String text = "";
            String src = "";
            if (svc != null) {
                text = svc.dumpText();
                if (!text.isEmpty()) src = "无障碍文本";
            }
            if (text.isEmpty() && ShizukuShell.granted()) {
                try {
                    String xml = ShizukuShell.str(
                            "uiautomator dump /sdcard/.qh_ui.xml >/dev/null 2>&1; cat /sdcard/.qh_ui.xml");
                    text = UiDump.textOf(UiDump.parse(xml));
                    if (!text.isEmpty()) src = "uiautomator";
                } catch (Throwable ignored) {
                }
            }
            sb.append("Shizuku：").append(ShizukuShell.granted() ? "已授权" : "未授权").append('\n');
            sb.append("取词来源：").append(src.isEmpty() ? "（没读到）" : src).append('\n');
            sb.append("读到的文字（前 300 字）：\n")
                    .append(text.isEmpty() ? "（空）" : head(text, 300)).append('\n');

            // 2b) 视觉大模型：默认的「视觉优先」每次答题先走它，所以顺手验一下这套
            //     key / 模型到底认不认图片 —— 不认的话助手会一直退到无障碍文本，
            //     在这里（而不是在考场上）先看出来。跟遥控口 VISION 是同一段代码。
            sb.append("\n视觉大模型（默认取词方式先走它）：\n");
            sb.append(head(FloatingService.debugVision(), 400)).append('\n');

            // 3) 样式匹配自检
            String allowed = AnswerParser.allowedLetters(labelsCsv);
            sb.append("\n选项标签：").append(allowed).append('\n');
            for (int i = 0; i < allowed.length() && i < 4; i++) {
                String L = String.valueOf(allowed.charAt(i));
                boolean hit = false;
                if (svc != null) hit = svc.boundsOfOption(L) != null;
                sb.append("  选项 ").append(L).append("：").append(hit ? "已定位" : "未找到").append('\n');
            }
            String[] kws = Prefs.splitList(nextCsv);
            boolean nextHit = false;
            if (svc != null && kws.length > 0) {
                java.util.List<String> kl = new java.util.ArrayList<>();
                for (String k : kws) kl.add(k);
                nextHit = svc.boundsOfKeyword(kl) != null;
            }
            sb.append("  「下一题」按钮：").append(nextHit ? "已定位" : "未找到").append('\n');

            // 4) API
            sb.append("\n正在测试 API…\n");
            LlmClient.Reply r = LlmClient.ping();
            if (r.ok) {
                sb.append("API：正常（").append(r.costMs).append("ms）返回：").append(head(r.content, 60));
            } else {
                sb.append("API：失败 → ").append(r.error);
                runOnUiThread(() -> showSelfTest(sb.toString().trim()));
                return;
            }

            // 5) 拿真提示词各问一道，看模型认不认这套约定。
            // 光测「通不通」不够：选择题要是被模型回了个 fill，整条链会误判成填空题、
            // 每次都停在那儿等你去点球，一路连答就断了。这一步就是专门盯这个的。
            runOnUiThread(() -> showSelfTest(sb.append("\n正在试选择题…").toString()));
            LlmClient.Reply choice = LlmClient.ask(
                    "中国铁路官方购票网站的域名是？\nA. 12306.cn\nB. taobao.com\nC. weibo.com");
            sb.append("\n选择题 → ");
            if (!choice.ok) {
                sb.append("失败：").append(choice.error);
            } else {
                AnswerParser.Parsed p = AnswerParser.parse(choice.content, labelsCsv);
                sb.append("answer=").append(p.answer.isEmpty() ? "(空)" : p.answer)
                        .append("  fill=").append(p.fill.isEmpty() ? "(空，对)" : "「" + head(p.fill, 30) + "」← 不该有");
            }

            // 5b) 一屏两题：这一屏同时有单选和多选，正是「串题」最容易发生的样子。
            //     期望 2 条、按顺序、单选那条一个字母、多选那条两个字母。
            runOnUiThread(() -> showSelfTest(sb.append("\n正在试一屏两题…").toString()));
            LlmClient.Reply two = LlmClient.ask(
                    "1. 中国铁路官方购票网站的域名是？（单选题）\nA. 12306.cn\nB. taobao.com\nC. weibo.com\n"
                            + "2. 下列哪些是中国的直辖市？（多选题）\nA. 北京\nB. 广州\nC. 上海\nD. 成都");
            sb.append("\n一屏两题 → ");
            if (!two.ok) {
                sb.append("失败：").append(two.error);
            } else {
                AnswerParser.Parsed p = AnswerParser.parse(two.content, labelsCsv,
                        "1. 中国铁路官方购票网站的域名是？（单选题）\n"
                                + "2. 下列哪些是中国的直辖市？（多选题）");
                sb.append(AnswerParser.desc(p)).append("（应为 2条 A|AC）");
            }

            // 6) 填空题：正文该进 fill，且收拾完是纯文本、只有 1. 2. 3. 编号
            runOnUiThread(() -> showSelfTest(sb.append("\n正在试填空题…").toString()));
            LlmClient.Reply fill = LlmClient.ask(
                    "请写出「中国古代四大发明」里的两项，每项用一句话说明它的作用。");
            sb.append("\n填空题 → ");
            if (!fill.ok) {
                sb.append("失败：").append(fill.error);
            } else {
                AnswerParser.Parsed p = AnswerParser.parse(fill.content, labelsCsv);
                if (p.fill.isEmpty()) {
                    sb.append("没给 fill（模型没照约定，原文：" + head(fill.content, 80) + "）");
                } else {
                    sb.append("正文 ").append(p.fill.length()).append(" 字：\n").append(p.fill);
                }
            }

            String out = sb.toString();
            runOnUiThread(() -> showSelfTest(out));
        });
    }

    /**
     * 解析自检：拿几段写死的「模型回复」过一遍 {@link AnswerParser}，看
     * ①单选题有没有被截成一个字母、②一屏多题的答案有没有按顺序保住。
     * 纯本地，不花 API 额度，没网也能跑 —— 换提示词、换模型之后先跑这个。
     */
    private static void parseChecks(StringBuilder sb) {
        for (String line : AnswerParser.selfCheck()) {
            sb.append("  ").append(line).append('\n');
        }
    }

    /** 自检结果框平时藏着，免得没跑过就留一个空方块在那里。 */
    private void showSelfTest(String s) {
        tvSelfTest.setText(s);
        tvSelfTest.setVisibility(View.VISIBLE);
    }

    private static String head(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
