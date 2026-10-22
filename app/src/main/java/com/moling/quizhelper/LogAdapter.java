package com.moling.quizhelper;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

    public interface OnClick {
        void onClick(LogRecord r);
    }

    private final List<LogRecord> data = new ArrayList<>();
    private final OnClick onClick;

    public LogAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void setData(List<LogRecord> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LogRecord r = data.get(position);
        boolean isFill = r.fill != null && !r.fill.isEmpty();
        String ans = r.answer == null || r.answer.isEmpty() ? (isFill ? "填空" : "—") : r.answer;
        h.answer.setText(ans);

        // 牌子染个色：有答案正常色，没答出来是黄的，整条失败（取不到词/大模型挂了）是红的。
        // 扫日志的时候不用逐条读，看颜色就知道哪几条有问题。
        int color;
        if ("—".equals(ans) || "?".equals(ans)) {
            color = r.error != null && !r.error.isEmpty() ? R.color.err : R.color.warn;
        } else {
            color = R.color.brand;
        }
        h.answer.setTextColor(h.answer.getContext().getColor(color));

        StringBuilder meta = new StringBuilder();
        meta.append(r.timeText());
        if (r.source != null && !r.source.isEmpty()) meta.append(" · ").append(r.source);
        if (r.costMs > 0) meta.append(" · ").append(r.costMs).append("ms");
        if (r.clicked >= 0) meta.append(" · 已点 ").append(r.clicked).append(" 项");
        if (r.error != null && !r.error.isEmpty()) meta.append(" · ⚠ ").append(r.error);
        h.meta.setText(meta.toString());

        // 填空题直接显示正文：一眼就能看到当时复制走的是什么，比看解析有用
        String exp = r.explanation == null ? "" : r.explanation;
        if (isFill) exp = r.fill;
        h.exp.setText(exp.isEmpty() ? "（无解析）" : exp);
        h.question.setText(r.question == null || r.question.isEmpty() ? "" : r.question);

        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(r);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView answer, meta, exp, question;

        VH(View v) {
            super(v);
            answer = v.findViewById(R.id.tvAnswer);
            meta = v.findViewById(R.id.tvMeta);
            exp = v.findViewById(R.id.tvExp);
            question = v.findViewById(R.id.tvQuestion);
        }
    }
}
