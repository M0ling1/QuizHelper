package com.moling.quizhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 把大模型吐出来的一段话收拾成「纯 txt」。
 *
 * <p>填空题的正文是要粘到别处去的，粘过去的东西不能带 markdown：不能有 ``` 代码块、
 * 不能有 ** 加粗、不能有 --- 这种分隔线。要分段就只用 {@code 1. 2. 3.} 编号分段。
 *
 * <p>这个类**故意不引用任何 android.***，就是为了能在电脑上单独 javac 编译、
 * 拿样例跑一遍 —— 这种字符串处理最容易写错，在真机上试一次要一分钟，在这里一秒钟。
 */
public final class PlainText {

    private PlainText() {
    }

    /** 收拾成纯文本。空输入返回空串。 */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replace("​", "").replace("‌", "")
                .replace("‍", "").replace("﻿", "");

        // 逐行过一遍：丢掉围栏行、把分隔线变成空行、剥掉 markdown 装饰，空行留着当分段依据
        StringBuilder body = new StringBuilder();
        String[] lines = s.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                body.append('\n');
                continue;
            }
            if (t.startsWith("```")) continue;          // 围栏那一行丢掉，里面的内容留着
            // 表格的 | --- | --- | 要吃掉而不是当空行：当空行会把表头跟数据行拆成两段，
            // 表头白占一个编号，数据行也看不懂在说什么。
            if (isTableSeparator(t)) continue;
            if (isSeparator(t)) {                        // --- *** ___ 之类，一律当空行
                body.append('\n');
                continue;
            }
            t = stripMarks(t);
            if (t.isEmpty()) continue;
            body.append(t).append('\n');
        }

        List<String> blocks = splitBlocks(body.toString());
        if (blocks.isEmpty()) return "";
        if (blocks.size() == 1) return stripNumber(blocks.get(0));

        // 多段：从头编号。编号是「分段」的唯一记号 —— 用户明确不要 --- 那类分隔线。
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String b : blocks) {
            String text = stripNumber(b).trim();
            if (text.isEmpty()) continue;               // 光有编号没内容，别白占一个号
            if (n > 0) out.append("\n\n");
            n++;
            out.append(n).append(". ").append(text);
        }
        return out.toString();
    }

    /** 按空行切段，段内的单换行保留；行首带编号的行自己也是一段的开头。 */
    static List<String> splitBlocks(String body) {
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (cur.length() > 0) {
                    blocks.add(cur.toString().trim());
                    cur.setLength(0);
                }
                continue;
            }
            // 模型经常把 1. 2. 3. 连着写、中间不留空行，那就得靠自己认编号来分段，
            // 否则整坨会挤成一段，只把第一个编号剥掉，剩下 "2." "3." 留在正文里。
            if (cur.length() > 0 && leadingNumber(t) >= 0) {
                blocks.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(t);
        }
        if (cur.length() > 0) blocks.add(cur.toString().trim());
        return blocks;
    }

    /** markdown 表格的分隔行（| --- | --- |）。它跟水平线不一样：水平线是分段，它只是表格的零件。 */
    static boolean isTableSeparator(String line) {
        return line.indexOf('|') >= 0 && isSeparator(line);
    }

    /** 整行只有一种分隔符号，至少三个：--- *** ___ === ～～～，外加 markdown 表格那条 |---|。 */
    static boolean isSeparator(String line) {
        String t = line.trim();
        if (t.length() < 3) return false;
        char first = t.charAt(0);
        if ("-*_=~—－＊＿＝～".indexOf(first) < 0) {
            // 表格的分隔行长这样：| --- | --- |
            if (first != '|' || t.indexOf('-') < 0) return false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != '|' && c != '-' && c != ':' && !Character.isWhitespace(c)) return false;
            }
            return true;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != first && !Character.isWhitespace(t.charAt(i))) return false;
        }
        return true;
    }

    /** 剥掉行首的 markdown 装饰（# 标题、&gt; 引用、- 列表）和行内的加粗/斜体/反引号。 */
    static String stripMarks(String line) {
        String t = line;
        while (!t.isEmpty()) {
            char c = t.charAt(0);
            if (c == '#' || c == '>') {
                t = t.substring(1).trim();
                continue;
            }
            break;
        }
        // 列表符号后面必须跟空白才算，免得把「-5度」的负号吃掉
        if (t.length() > 1) {
            char c = t.charAt(0);
            if ("-*+•·‣▪".indexOf(c) >= 0 && Character.isWhitespace(t.charAt(1))) {
                t = t.substring(1).trim();
            }
        }
        t = t.replace("**", "").replace("__", "")
                .replace("`", "").replace("~~", "").trim();
        // markdown 表格行：竖线换成空格，粘到别处才读得懂（分隔行在 isSeparator 那步已经丢了）
        if (t.startsWith("|") && t.length() > 2) {
            t = t.substring(1, t.endsWith("|") ? t.length() - 1 : t.length());
            t = t.replace("|", " ").replaceAll("[ \\t]{2,}", " ").trim();
        }
        return t;
    }

    /**
     * 行首的编号（"1." "2、" "（3）" "(4)" "[5]"），返回那个数字；不像编号返回 -1。
     * <p>
     * 「1.5 米」「（1+1=2」这种是正文里的数字，不是分段编号，必须放过去。
     */
    static int leadingNumber(String line) {
        String t = line.trim();
        int i = 0;
        boolean bracketed = false;
        if (i < t.length() && "([【（［".indexOf(t.charAt(i)) >= 0) {
            bracketed = true;
            i++;
        }
        int start = i;
        while (i < t.length() && t.charAt(i) >= '0' && t.charAt(i) <= '9') i++;
        if (i == start || i - start > 2) return -1;      // 「2024年」这种不算编号
        int n;
        try {
            n = Integer.parseInt(t.substring(start, i));
        } catch (Throwable e) {
            return -1;
        }
        if (i >= t.length()) return bracketed ? n : -1;  // 只有「(3)」也行，只有「3」不行
        char c = t.charAt(i);
        if (bracketed) return ")]】）］".indexOf(c) >= 0 ? n : -1;
        if (".、．。:：)]】）］".indexOf(c) < 0) return -1;
        if (i + 1 < t.length() && Character.isDigit(t.charAt(i + 1))) return -1;   // "1.5"
        return n;
    }

    /** 去掉行首那个编号，剩下的正文；没有编号就原样返回。 */
    static String stripNumber(String block) {
        if (leadingNumber(block) < 0) return block.trim();
        String t = block.trim();
        int i = 0;
        if ("([【（［".indexOf(t.charAt(0)) >= 0) i++;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i < t.length()) i++;                          // 吃掉右括号或点号
        return t.substring(Math.min(i, t.length())).trim();
    }
}
