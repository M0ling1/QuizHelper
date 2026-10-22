package com.moling.quizhelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 全屏看日志：把两份日志摊满整屏。
 * <p>
 * 两份各有各的用处，这里都能看：
 * <ul>
 *   <li><b>问答日志</b>（{@link LogStore}，就是主界面那个列表）—— 一条一条的「问了什么、答了什么、
 *       点了没有」。主界面那份要一行行点开看，这里一次全摊开。</li>
 *   <li><b>运行日志</b>（{@code files/asklog.txt}）—— App 跑的过程逐行记的：「连答：换到新的一屏」
 *       「屏幕上没读到文字」「无障碍服务没连上」这类。查「为什么卡了」看的就是这一份，
 *       以前只能在电脑上 {@code adb shell cat} 拉出来看。</li>
 * </ul>
 * 允许横屏、字体可放大、正文可选中：出来的都是长行，竖屏一行要折三次根本没法读。
 */
public class LogFullActivity extends AppCompatActivity {

    /** 问答日志 / 运行日志。 */
    private static final int SRC_QA = 0;
    private static final int SRC_RUN = 1;

    /**
     * 运行日志最多显示最后这么多行。文件本身封顶 512KB，全塞进一个 TextView 会卡成幻灯片；
     * 排查问题看的本来就是「刚刚怎么了」，尾巴足够了 —— 屏幕上滑两千行也没人看得下去。
     */
    private static final int RUN_TAIL_LINES = 1200;

    /** 问答日志最多铺这么多字符，理由同上。 */
    private static final int QA_MAX_CHARS = 100_000;

    /** 字号档位（sp），按「字号」循环。默认中间那档。 */
    private static final float[] SIZES = {13f, 16f, 20f};

    private int src = SRC_QA;
    private int sizeIdx = 1;

    private ScrollView scroll;
    private TextView body;
    private TextView title;
    private TextView info;
    private TextView btnSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_full);

        scroll = findViewById(R.id.scrollFull);
        body = findViewById(R.id.tvFullBody);
        title = findViewById(R.id.tvFullTitle);
        info = findViewById(R.id.tvFullInfo);
        btnSwitch = findViewById(R.id.btnFullSwitch);

        findViewById(R.id.btnFullClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnFullCopy).setOnClickListener(v -> copyAll());
        btnSwitch.setOnClickListener(v -> {
            src = src == SRC_QA ? SRC_RUN : SRC_QA;
            render();
        });
        findViewById(R.id.btnFullSize).setOnClickListener(v -> {
            sizeIdx = (sizeIdx + 1) % SIZES.length;
            body.setTextSize(SIZES[sizeIdx]);
            toast("字号 " + (int) SIZES[sizeIdx]);
        });

        body.setTextSize(SIZES[sizeIdx]);
        render();
    }

    private void render() {
        boolean qa = src == SRC_QA;
        title.setText(qa ? "问答日志" : "运行日志");
        btnSwitch.setText(qa ? "看运行日志" : "看问答日志");
        if (qa) renderQa();
        else renderRun();
        // 问答日志是最新一条在最上面 —— 停在顶部；运行日志最新的在最后 —— 滚到底。
        scroll.post(() -> scroll.fullScroll(qa ? View.FOCUS_UP : View.FOCUS_DOWN));
    }

    private void renderQa() {
        List<LogRecord> all = LogStore.load(this);
        if (all.isEmpty()) {
            info.setText("");
            body.setText("（还没有记录。点一次悬浮球识别屏幕，这里就有了。）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (LogRecord r : all) {
            String one = recordText(r);
            if (n > 0 && sb.length() + one.length() > QA_MAX_CHARS) break;
            sb.append(one);
            n++;
        }
        info.setText(n < all.size()
                ? "显示最近 " + n + " 条 / 共 " + all.size() + " 条"
                : "共 " + all.size() + " 条 · 最新在上面");
        body.setText(sb.toString());
    }

    /**
     * 一条记录摊平成文本。字段和日志详情页一样，只是不套那层卡片壳 ——
     * 这里一次要看几十条，标题占的每一行都是白占。
     */
    private static String recordText(LogRecord r) {
        boolean isFill = r.fill != null && !r.fill.isEmpty();
        String ans = r.answer == null || r.answer.isEmpty() ? (isFill ? "填空" : "—") : r.answer;
        StringBuilder sb = new StringBuilder();
        sb.append("── ").append(r.timeText());
        if (r.source != null && !r.source.isEmpty()) sb.append(" · ").append(r.source);
        if (r.costMs > 0) sb.append(" · ").append(r.costMs).append("ms");
        if (r.clicked >= 0) sb.append(" · 已点 ").append(r.clicked).append(" 项");
        sb.append('\n');
        sb.append("答案：").append(ans).append('\n');
        if (r.error != null && !r.error.isEmpty()) sb.append("错误：").append(r.error).append('\n');
        if (isFill) sb.append("填空正文：\n").append(r.fill).append('\n');
        sb.append("解析：\n").append(r.explanation).append('\n');
        sb.append("题目：\n").append(r.question).append("\n\n");
        return sb.toString();
    }

    private void renderRun() {
        File f = new File(getExternalFilesDir(null), "asklog.txt");
        if (!f.exists()) {
            info.setText("");
            body.setText("（还没有运行日志。这份是 App 跑起来之后逐行记的，启动悬浮窗、答一次题就有了。）");
            return;
        }
        Deque<String> tail = new ArrayDeque<>();
        int lines = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines++;
                tail.addLast(line);
                if (tail.size() > RUN_TAIL_LINES) tail.removeFirst();
            }
        } catch (Throwable t) {
            info.setText("");
            body.setText("运行日志读不出来：" + t);
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (lines > tail.size()) {
            sb.append("（文件里共 ").append(lines).append(" 行，这里只显示最后 ")
                    .append(tail.size()).append(" 行）\n\n");
        }
        for (String l : tail) sb.append(l).append('\n');
        info.setText("最后 " + tail.size() + " 行 · 最新在下面");
        body.setText(sb.toString());
    }

    private void copyAll() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("quizhelper-log", body.getText()));
            toast("已复制（" + body.getText().length() + " 字）");
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
