package com.moling.quizhelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LogDetailActivity extends AppCompatActivity {

    public static final String EXTRA_LINE = "line";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_detail);

        String line = getIntent().getStringExtra(EXTRA_LINE);
        LogRecord r = line == null ? null : LogRecord.fromJson(line);

        TextView head = findViewById(R.id.tvHead);
        TextView body = findViewById(R.id.tvBody);

        if (r == null) {
            head.setText("记录已损坏");
            body.setText(line);
            findViewById(R.id.btnCopy).setOnClickListener(v -> copy(line));
            return;
        }

        boolean isFill = r.fill != null && !r.fill.isEmpty();
        head.setText("答案：" + (r.answer.isEmpty() ? (isFill ? "填空" : "—") : r.answer));
        StringBuilder sb = new StringBuilder();
        sb.append("时间：").append(r.timeText()).append('\n');
        sb.append("来源：").append(r.source).append('\n');
        sb.append("耗时：").append(r.costMs).append(" ms\n");
        if (r.clicked >= 0) sb.append("已自动点击选项数：").append(r.clicked).append('\n');
        if (!r.error.isEmpty()) sb.append("错误：").append(r.error).append('\n');
        // 填空题的正文就是答案，放最前面 —— 它同时还被复制进了剪贴板，
        // 万一剪贴板被后面别的东西盖掉了，这里还能捞回来。
        if (isFill) {
            sb.append("\n填空正文（当时已复制到剪贴板）：\n").append(r.fill).append('\n');
        }
        sb.append("\n解析：\n").append(r.explanation).append('\n');
        sb.append("\n题目原文：\n").append(r.question);

        body.setText(sb.toString());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copy(sb.toString()));
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("quizhelper", s));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
