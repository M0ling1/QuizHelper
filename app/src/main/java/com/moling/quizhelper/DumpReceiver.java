package com.moling.quizhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 遥控口：用 adb 就能触发读屏 / 答题 / 停下 / 改设置，全程不用碰屏幕。
 *
 * <pre>
 * # 读一次屏，把完整节点树写到 files/tree.txt（不走 OCR、大模型、自动点击）
 * # 顺带把「认出几组选项、每组有哪些标签」写进 files/groups.txt —— 一屏多题切分对不对看这个
 * adb shell am broadcast -a com.moling.quizhelper.DUMP -n com.moling.quizhelper/.DumpReceiver
 * adb shell cat /sdcard/Android/data/com.moling.quizhelper/files/tree.txt
 * adb shell cat /sdcard/Android/data/com.moling.quizhelper/files/groups.txt
 *
 * # 完整走一遍「读屏 → 大模型 → 显示 → 自动点击 → 自动连答」，等于点一下悬浮球
 * adb shell am broadcast -a com.moling.quizhelper.ASK -n com.moling.quizhelper/.DumpReceiver
 *
 * # 看每次作答的复盘（题目原文 / 模型原始回复 / 解析出的答案 / 点中几个）
 * adb shell cat /sdcard/Android/data/com.moling.quizhelper/files/asklog.txt
 *
 * # 关掉悬浮窗服务
 * adb shell am broadcast -a com.moling.quizhelper.STOP -n com.moling.quizhelper/.DumpReceiver
 *
 * # 分开试「无障碍点击」和「手势点坐标」这两条路，结果写进 files/pref.log
 * adb shell am broadcast -a com.moling.quizhelper.TAP -n com.moling.quizhelper/.DumpReceiver --es label A
 * adb shell am broadcast -a com.moling.quizhelper.TAP -n com.moling.quizhelper/.DumpReceiver --ei x 631 --ei y 952
 *
 * # 启动悬浮球（不用去点界面；要截图还得另走录屏授权）
 * adb shell am broadcast -a com.moling.quizhelper.START -n com.moling.quizhelper/.DumpReceiver
 *
 * # 悬浮球现在是什么状态（运行中 / 已暂停）
 * adb shell am broadcast -a com.moling.quizhelper.STATE -n com.moling.quizhelper/.DumpReceiver
 *
 * # 解析自检（纯本地，不花额度）：单选截断、一屏多题按序，结果进 files/pref.log
 * adb shell am broadcast -a com.moling.quizhelper.CHECK -n com.moling.quizhelper/.DumpReceiver
 *
 * # 截屏三条路各自通不通（Shizuku / 屏幕录制 / 无障碍截屏），结果进 files/pref.log
 * adb shell am broadcast -a com.moling.quizhelper.SHOT -n com.moling.quizhelper/.DumpReceiver
 *
 * # 暂停 / 恢复回答（长按悬浮球 = 暂停；短按 = 绿球开答 / 灰球回绿 / 蓝球重读）
 * adb shell am broadcast -a com.moling.quizhelper.PAUSE -n com.moling.quizhelper/.DumpReceiver
 * adb shell am broadcast -a com.moling.quizhelper.RESUME -n com.moling.quizhelper/.DumpReceiver
 *
 * # 替用户在悬浮球上短按一下（锁屏时 input tap 点不到球）：走的是同一个 tapBall
 * adb shell am broadcast -a com.moling.quizhelper.TAPBALL -n com.moling.quizhelper/.DumpReceiver
 *
 * # 验填空题那段：收拾纯文本 + 写剪贴板（不带 text 就是读回剪贴板）
 * adb shell am broadcast -a com.moling.quizhelper.TEXT -n com.moling.quizhelper/.DumpReceiver --es text '**甲**\n\n---\n\n- 乙'
 *
 * # 改设置（值都是字符串，内部再转）；不给 value 就是「读一下现在是多少」
 * adb shell am broadcast -a com.moling.quizhelper.PREF -n com.moling.quizhelper/.DumpReceiver \
 *     --es key autoLoop --es value true
 * adb shell am broadcast -a com.moling.quizhelper.PREF -n com.moling.quizhelper/.DumpReceiver \
 *     --es key model
 * </pre>
 */
public class DumpReceiver extends BroadcastReceiver {

    private static final String TAG = "QuizA11y";

    /** 允许触发这些遥控口的发送方。adb shell 是 com.android.shell。 */
    private static final Set<String> ALLOW = new HashSet<>(Arrays.asList(
            "com.android.shell", "android", "com.moling.quizhelper"));

    /** 允许从 adb 改的设置项。故意用白名单，别让这个口子能改任意 key。 */
    private static final Set<String> PREF_KEYS = new HashSet<>(Arrays.asList(
            "autoLoop", "loopDelay", "autoNext", "autoClick", "nextDelay",
            "bubble", "bubblePos", "bubbleX", "bubbleY", "bubbleSec", "notifSec",
            "ocrSource", "shotSource", "clickSource", "labels", "nextKeywords",
            "model", "baseUrl", "tts", "cropTop", "cropBottom",
            // 视觉那两栏只给读/改 URL 和模型名：key 不给 —— 那玩意儿一读就落到
            // pref.log 里，而 pref.log 是明文躺在外部存储上的。
            "visionUrl", "visionModel",
            "fillCopy", "fillWait", "autoFill",
            "reasoningEffort", "fallbackOcr", "fallbackA11y", "autoScroll",
            "ttsExplanation", "notifyAnswer", "keepServiceNotif",
            // 清掉「这道题点过哪些字母」的记忆（从头再答同一份卷子时用）
            "lastClicks",
            // 只读几项：核对「打包进 APK 的默认值」是不是真按用户现在的来
            "prompt", "timeout", "floatAlpha", "autoHideSec", "hideOnShot",
            "logRaw", "floatX", "floatY", "apiKeySet", "visionKeySet"));

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!trusted()) return;
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case "com.moling.quizhelper.DUMP":
                dump();
                break;
            case "com.moling.quizhelper.ASK":
                ask(context);
                break;
            case "com.moling.quizhelper.STOP":
                FloatingService.stop(context);
                break;
            case "com.moling.quizhelper.PREF":
                setPref(context, intent.getStringExtra("key"), intent.getStringExtra("value"));
                break;
            case "com.moling.quizhelper.TAP":
                tap(intent);
                break;
            case "com.moling.quizhelper.PAUSE":
                pause();
                break;
            case "com.moling.quizhelper.RESUME":
                resume();
                break;
            case "com.moling.quizhelper.TEXT":
                text(context, intent);
                break;
            case "com.moling.quizhelper.START":
                FloatingService.start(context, 0, null);
                say("START 已发出");
                break;
            case "com.moling.quizhelper.STATE":
                say("STATE 运行中=" + FloatingService.isRunning()
                        + " 待启动=" + FloatingService.isArmed()
                        + " 已暂停=" + FloatingService.isPaused());
                break;
            case "com.moling.quizhelper.CHECK":
                check();
                break;
            case "com.moling.quizhelper.SETPREF":
                setPref(intent);
                break;
            case "com.moling.quizhelper.SHOT":
                shot();
                break;
            case "com.moling.quizhelper.OCRDUMP":
                ocrDump();
                break;
            case "com.moling.quizhelper.VISION":
                vision();
                break;
            case "com.moling.quizhelper.HEAL":
                heal();
                break;
            case "com.moling.quizhelper.TAPBALL":
                tapBall();
                break;
            default:
                break;
        }
    }

    /**
     * 来源校验。坑：adb 用 `am broadcast` 发过来的广播，系统经常不带发送者身份，
     * getSentFromUid() 返回 -1、getSentFromPackage() 返回 null ——
     * 这种「身份未知」的情况只能放行，否则 adb 自己也用不了。
     */
    private boolean trusted() {
        int uid = getSentFromUid();
        String from = getSentFromPackage();
        boolean unknown = uid == -1 && from == null;
        boolean ok = uid == 2000 || uid == 0 || ALLOW.contains(from);
        if (!ok && !unknown) {
            Log.w(TAG, "遥控口：拒绝 uid=" + uid + " pkg=" + from);
            return false;
        }
        return true;
    }

    private void dump() {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                QuizAccessibilityService svc = QuizAccessibilityService.get();
                if (svc == null) {
                    Log.w(TAG, "DUMP: 无障碍服务未连接");
                } else {
                    String text = svc.dumpText();
                    // 一屏多题是不是切对了组，光看文字看不出来 —— 落一份分组的标签
                    // 到 files/groups.txt，adb 拉回来看：「1: A,B,C,D」「2: A,B,C,D」。
                    StringBuilder sb = new StringBuilder();
                    sb.append("text len=").append(text.length()).append('\n');
                    sb.append("groups=").append(svc.optionGroupCount()).append('\n');
                    for (String g : svc.optionGroupLabels()) sb.append(g).append('\n');
                    sb.append("signature=")
                            .append(svc.optionSignature(AnswerParser.allowedLetters(Prefs.labels())))
                            .append('\n');
                    // 指纹算出来是什么、空在哪一步 —— 「同一屏反复重问」全靠这几行定位
                    sb.append(FloatingService.debugKeyInfo());
                    writeFile("groups.txt", sb.toString());
                    Log.i(TAG, "DUMP: len=" + text.length() + "  " + sb);
                }
            } catch (Throwable t) {
                Log.w(TAG, "DUMP failed", t);
            } finally {
                finish(pr);
            }
        }, "qh-dump").start();
    }

    /** 覆写 files/&lt;name&gt;，给 adb 拉回来看。 */
    private void writeFile(String name, String content) {
        try {
            File dir = new File("/sdcard/Android/data/com.moling.quizhelper/files");
            if (!dir.exists()) return;
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(new File(dir, name)), StandardCharsets.UTF_8)) {
                w.write(content);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把这一屏的 OCR 原始行、无障碍选项行、按行对齐的成品一起写进 files/ocr.txt。
     * 选项读不全时靠它定位是 OCR 漏认标签还是无障碍漏了整行。
     */
    private void ocrDump() {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String s = FloatingService.debugOcrDump();
                writeFile("ocr.txt", s);
                Log.i(TAG, "OCRDUMP len=" + s.length());
            } catch (Throwable t) {
                Log.w(TAG, "OCRDUMP failed", t);
            } finally {
                finish(pr);
            }
        }, "qh-ocrdump").start();
    }

    /**
     * 把这一屏的截图直接发给视觉大模型问一次，结果写进 files/vision.txt。
     * 验「手上的 key / 模型到底认不认图片」用。
     */
    private void vision() {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String s = FloatingService.debugVision();
                writeFile("vision.txt", s);
                Log.i(TAG, "VISION len=" + s.length());
            } catch (Throwable t) {
                Log.w(TAG, "VISION failed", t);
            } finally {
                finish(pr);
            }
        }, "qh-vision").start();
    }

    /**
     * 调试口：手动跑一遍「无障碍掉线自救」，把每一步的结果写进 files/pref.log。
     * <pre>
     * adb shell am broadcast -a com.moling.quizhelper.HEAL -n com.moling.quizhelper/.DumpReceiver
     * </pre>
     */
    private void heal() {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                say("HEAL\n" + FloatingService.debugHeal());
            } catch (Throwable t) {
                say("HEAL 炸了：" + t);
            } finally {
                finish(pr);
            }
        }, "qh-heal").start();
    }

    /** 截屏三条路分开探一遍，结果写进 files/pref.log 和 logcat。 */
    private void shot() {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            say("SHOT\n" + FloatingService.probeScreenshot());
            finish(pr);
        }, "qh-shot").start();
    }

    private void ask(Context context) {
        try {
            FloatingService.startAsk(context);
            say("ASK 已发出");
        } catch (Throwable t) {
            // 服务没在跑的话 startForegroundService 起不来（缺 MediaProjection 的 code/data），
            // 这种情况得先在 App 里点「启动悬浮窗」。
            Log.w(TAG, "ASK failed", t);
            say("ASK 失败：" + t + "（先确认悬浮窗已经启动）");
        }
    }

    /**
     * 调试口：把「无障碍点击」和「手势点坐标」两条路分开试，看哪条真管用。
     * <pre>
     * # 按标签走无障碍点击，顺便报一下它算出来的位置
     * adb shell am broadcast -a com.moling.quizhelper.TAP -n com.moling.quizhelper/.DumpReceiver --es label A
     * # 指定组（一屏多题）：只找第 2 组选项里的 A，--ei group 1（0 起）
     * adb shell am broadcast -a com.moling.quizhelper.TAP -n com.moling.quizhelper/.DumpReceiver \
     *     --es label A --ei group 1
     * # 直接在某点落一个手势
     * adb shell am broadcast -a com.moling.quizhelper.TAP -n com.moling.quizhelper/.DumpReceiver --ei x 631 --ei y 952
     * </pre>
     */
    private void tap(Intent intent) {
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null) {
            say("TAP：无障碍服务未连接");
            return;
        }
        String label = intent.getStringExtra("label");
        if (label != null && !label.isEmpty()) {
            // --ei group 1 表示「只找第 2 组选项里的这个标签」（一屏多题时对位用）
            int group = intent.getIntExtra("group", -1);
            String at = group >= 0 ? "#组" + (group + 1) : "";
            Rect r = svc.boundsOfOption(label, group);
            boolean ok = svc.clickOption(label, group);
            say("TAP clickOption(" + label + at + ") = " + ok
                    + " bounds=" + (r == null ? "null" : r.toShortString()));
            return;
        }
        int x = intent.getIntExtra("x", -1);
        int y = intent.getIntExtra("y", -1);
        if (x < 0 || y < 0) {
            say("TAP：缺 x / y");
            return;
        }
        say("TAP tapAt(" + x + "," + y + ") = " + svc.tapAt(x, y));
    }

    /**
     * 调试口：验「填空题正文收拾成纯文本 + 写剪贴板」这条路，不用真等一道填空题。
     * <pre>
     * # 带 text：收拾 + 写剪贴板 + 把结果写进 pref.log
     * adb shell am broadcast -a com.moling.quizhelper.TEXT -n com.moling.quizhelper/.DumpReceiver \
     *     --es text '**甲**\n\n---\n\n- 乙'
     * # 不带 text：把剪贴板现在的内容读回来写日志（验上一句到底写进去没有）
     * adb shell am broadcast -a com.moling.quizhelper.TEXT -n com.moling.quizhelper/.DumpReceiver
     * </pre>
     */
    /**
     * adb 直接改一条设置，只为调试用（比如把 baseUrl 指到一个没人监听的端口，
     * 看大模型失败重试那条路是不是真按预期走）。
     * <pre>
     * adb shell am broadcast -a com.moling.quizhelper.SETPREF -n com.moling.quizhelper/.DumpReceiver \
     *     --es k baseUrl --es v http://127.0.0.1:9
     * </pre>
     * 只认预置的几个 key —— 开放成「随便写哪个键」容易在改设置页字段时对不上号。
     */
    private void setPref(Intent intent) {
        String k = intent.getStringExtra("k"), v = intent.getStringExtra("v");
        if (k == null || v == null) {
            say("SETPREF：缺 k / v");
            return;
        }
        switch (k) {
            case "baseUrl":     Prefs.setBaseUrl(v); break;
            case "model":       Prefs.setModel(v); break;
            case "visionUrl":   Prefs.setVisionUrl(v); break;
            case "visionModel": Prefs.setVisionModel(v); break;
            case "nextKeywords":Prefs.setNextKeywords(v); break;
            case "ocrSource":   Prefs.setOcrSource(Integer.parseInt(v.trim())); break;
            case "timeoutSec":  Prefs.setTimeoutSec(Integer.parseInt(v.trim())); break;
            case "autoLoop":    Prefs.setAutoLoop("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim())); break;
            default:
                say("SETPREF：不认识的 key " + k);
                return;
        }
        say("SETPREF " + k + " = " + v);
    }

    private void text(Context context, Intent intent) {
        String raw = intent.getStringExtra("text");
        if (raw == null) {
            // 读回剪贴板：Android 10 起后台 App 读剪贴板会被系统拦掉，
            // 这里只是为了调试，读不到就如实说读不到。
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
                    say("TEXT 剪贴板：空");
                    return;
                }
                CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
                say("TEXT 剪贴板内容：\n" + s);
            } catch (Throwable t) {
                say("TEXT 读剪贴板失败：" + t);
            }
            return;
        }
        String cleaned = PlainText.clean(raw);
        FloatingService.copyToClipboard(context, cleaned);
        say("TEXT 原始：\n" + raw + "\nTEXT 收拾后(" + cleaned.length() + " 字)：\n" + cleaned);
    }

    /**
     * 解析自检的遥控口：跑一遍 {@link AnswerParser#selfCheck()}（纯本地、不花额度、
     * 跟屏幕内容无关），结果一行一条写进 files/pref.log 和 logcat。
     * <p>
     * 有它才能不开设置页面就验「单选题有没有被截成一个字母、一屏多题的答案有没有按顺序保住」。
     */
    private void check() {
        StringBuilder sb = new StringBuilder("CHECK\n");
        int bad = 0;
        for (String line : AnswerParser.selfCheck()) {
            if (line.startsWith("!!")) bad++;
            sb.append("  ").append(line).append('\n');
        }
        sb.append("CHECK 结果：").append(bad == 0 ? "全部通过" : (bad + " 条不对"));
        say(sb.toString());
    }

    private void pause() {
        FloatingService.pauseForDebug();
        say("PAUSE 已请求");
    }

    private void resume() {
        FloatingService.resumeForDebug();
        say("RESUME 已请求");
    }

    /** 替用户在悬浮球上短按一下（锁屏时 input tap 点不到球）。见 FloatingService.tapBallForDebug。 */
    private void tapBall() {
        FloatingService.tapBallForDebug();
        say("TAPBALL 已请求（球上短按一下）");
    }

    private void setPref(Context context, String key, String value) {
        if (key == null || !PREF_KEYS.contains(key)) {
            say("PREF 拒绝：" + key);
            return;
        }
        Prefs.init(context);
        if (value == null) {
            // 不给 value 就是「读一下现在是多少」，省得为了看一眼设置去翻界面
            say("PREF " + key + " = " + readPref(key));
            return;
        }
        try {
            switch (key) {
                case "autoLoop": Prefs.setAutoLoop(bool(value)); break;
                case "autoNext": Prefs.setAutoNext(bool(value)); break;
                case "autoClick": Prefs.setAutoClick(bool(value)); break;
                case "bubble": Prefs.setBubble(bool(value)); break;
                case "tts": Prefs.setTts(bool(value)); break;
                case "loopDelay": Prefs.setLoopDelaySec(Float.parseFloat(value)); break;
                case "nextDelay": Prefs.setNextDelaySec(Float.parseFloat(value)); break;
                case "bubblePos": Prefs.setBubblePos(Integer.parseInt(value)); break;
                case "bubbleX": Prefs.setBubbleX(Integer.parseInt(value)); break;
                case "bubbleY": Prefs.setBubbleY(Integer.parseInt(value)); break;
                case "bubbleSec": Prefs.setBubbleSec(Integer.parseInt(value)); break;
                case "notifSec": Prefs.setNotifSec(Integer.parseInt(value)); break;
                case "ocrSource": Prefs.setOcrSource(Integer.parseInt(value)); break;
                case "shotSource": Prefs.setShotSource(Integer.parseInt(value)); break;
                case "clickSource": Prefs.setClickSource(Integer.parseInt(value)); break;
                case "cropTop": Prefs.setCropTop(Integer.parseInt(value)); break;
                case "cropBottom": Prefs.setCropBottom(Integer.parseInt(value)); break;
                case "fillCopy": Prefs.setFillCopy(bool(value)); break;
                case "autoFill": Prefs.setAutoFill(bool(value)); break;
                // am broadcast 传不了空串（--es 后面必须有参数），用 clear 表示清空
                case "lastClicks": Prefs.setLastClicks("clear".equals(value) ? "" : value); break;
                case "fillWait": Prefs.setFillWaitSec(Integer.parseInt(value)); break;
                case "reasoningEffort": Prefs.setReasoningEffort(value); break;
                case "fallbackOcr": Prefs.setFallbackOcr(bool(value)); break;
                case "fallbackA11y": Prefs.setFallbackA11y(bool(value)); break;
                case "autoScroll": Prefs.setAutoScroll(bool(value)); break;
                case "ttsExplanation": Prefs.setTtsExplanation(bool(value)); break;
                case "notifyAnswer": Prefs.setNotifyAnswer(bool(value)); break;
                case "keepServiceNotif": Prefs.setKeepServiceNotif(bool(value)); break;
                case "labels": Prefs.setLabels(value); break;
                case "nextKeywords": Prefs.setNextKeywords(value); break;
                case "model": Prefs.setModel(value); break;
                case "baseUrl": Prefs.setBaseUrl(value); break;
                case "visionUrl": Prefs.setVisionUrl(value); break;
                case "visionModel": Prefs.setVisionModel(value); break;
                default: break;
            }
            say("PREF " + key + " = " + value);
        } catch (Throwable t) {
            say("PREF 失败：" + t);
        }
    }

    /** 读设置项的当前值（只读，用来核对 adb 改了没有）。 */
    private String readPref(String key) {
        try {
            switch (key) {
                case "autoLoop": return String.valueOf(Prefs.autoLoop());
                case "autoNext": return String.valueOf(Prefs.autoNext());
                case "autoClick": return String.valueOf(Prefs.autoClick());
                case "bubble": return String.valueOf(Prefs.bubble());
                case "tts": return String.valueOf(Prefs.tts());
                case "loopDelay": return String.valueOf(Prefs.loopDelaySec());
                case "nextDelay": return String.valueOf(Prefs.nextDelaySec());
                case "bubblePos": return String.valueOf(Prefs.bubblePos());
                case "bubbleX": return String.valueOf(Prefs.bubbleX());
                case "bubbleY": return String.valueOf(Prefs.bubbleY());
                case "bubbleSec": return String.valueOf(Prefs.bubbleSec());
                case "notifSec": return String.valueOf(Prefs.notifSec());
                case "ocrSource": return String.valueOf(Prefs.ocrSource());
                case "shotSource": return String.valueOf(Prefs.shotSource());
                case "clickSource": return String.valueOf(Prefs.clickSource());
                case "cropTop": return String.valueOf(Prefs.cropTop());
                case "cropBottom": return String.valueOf(Prefs.cropBottom());
                case "fillCopy": return String.valueOf(Prefs.fillCopy());
                case "autoFill": return String.valueOf(Prefs.autoFill());
                case "lastClicks": return Prefs.lastClicks();
                case "fillWait": return String.valueOf(Prefs.fillWaitSec());
                case "reasoningEffort": return Prefs.reasoningEffort();
                case "fallbackOcr": return String.valueOf(Prefs.fallbackOcr());
                case "fallbackA11y": return String.valueOf(Prefs.fallbackA11y());
                case "autoScroll": return String.valueOf(Prefs.autoScroll());
                case "ttsExplanation": return String.valueOf(Prefs.ttsExplanation());
                case "notifyAnswer": return String.valueOf(Prefs.notifyAnswer());
                case "keepServiceNotif": return String.valueOf(Prefs.keepServiceNotif());
                case "labels": return Prefs.labels();
                case "nextKeywords": return Prefs.nextKeywords();
                case "model": return Prefs.model();
                case "baseUrl": return Prefs.baseUrl();
                case "visionUrl": return Prefs.visionUrl().isEmpty()
                        ? "（留空 → 跟着主模型 " + Prefs.effVisionUrl() + "）" : Prefs.visionUrl();
                case "visionModel": return Prefs.visionModel().isEmpty()
                        ? "（留空 → 跟着主模型 " + Prefs.effVisionModel() + "）" : Prefs.visionModel();
                // 这些没在设置页上单列，但打包默认值时也要照着用户现在的来
                case "prompt": return Prefs.prompt();
                case "timeout": return String.valueOf(Prefs.timeoutSec());
                case "floatAlpha": return String.valueOf(Prefs.floatAlpha());
                case "autoHideSec": return String.valueOf(Prefs.autoHideSec());
                case "hideOnShot": return String.valueOf(Prefs.hideOverlayOnShot());
                case "logRaw": return String.valueOf(Prefs.logRaw());
                case "floatX": return String.valueOf(Prefs.floatX());
                case "floatY": return String.valueOf(Prefs.floatY());
                // 密钥只报「有没有填」，绝不回显内容
                case "apiKeySet": return String.valueOf(!Prefs.apiKey().isEmpty());
                case "visionKeySet": return String.valueOf(!Prefs.visionKey().isEmpty());
                default: return "?";
            }
        } catch (Throwable t) {
            return "读取失败：" + t;
        }
    }

    private static boolean bool(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    /** 写一行到 files/pref.log 并打 logcat，方便确认 adb 那头发成功了。 */
    private void say(String s) {
        Log.i(TAG, s);
        try {
            File dir = new File("/sdcard/Android/data/com.moling.quizhelper/files");
            if (!dir.exists()) return;
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "pref.log"), true), StandardCharsets.UTF_8)) {
                w.write(s + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void finish(PendingResult pr) {
        try {
            pr.finish();
        } catch (Throwable ignored) {
        }
    }
}
