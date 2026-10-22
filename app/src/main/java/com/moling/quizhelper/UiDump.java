package com.moling.quizhelper;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code uiautomator dump} 产生的 XML。
 * 无障碍服务没开、或者目标界面不向无障碍暴露节点时，用 Shizuku 跑 uiautomator 兜底拿控件坐标。
 */
public final class UiDump {

    private UiDump() {
    }

    public static class Node {
        public String text = "";
        public String desc = "";
        public boolean clickable;
        public Rect rect;
    }

    private static final Pattern P_NODE = Pattern.compile("<node\\b[^>]*>");
    private static final Pattern P_BOUNDS = Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");

    public static List<Node> parse(String xml) {
        List<Node> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;
        Matcher m = P_NODE.matcher(xml);
        while (m.find()) {
            String tag = m.group();
            Node n = new Node();
            n.text = attr(tag, "text");
            n.desc = attr(tag, "content-desc");
            n.clickable = "true".equals(attr(tag, "clickable"));
            Matcher b = P_BOUNDS.matcher(tag);
            if (b.find()) {
                try {
                    n.rect = new Rect(Integer.parseInt(b.group(1)), Integer.parseInt(b.group(2)),
                            Integer.parseInt(b.group(3)), Integer.parseInt(b.group(4)));
                } catch (Exception ignored) {
                }
            }
            out.add(n);
        }
        return out;
    }

    /** 把控件树里的文字拼成一整段，作为截图 OCR 的替代品。 */
    public static String textOf(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            String s = n.text != null && !n.text.trim().isEmpty() ? n.text.trim() : n.desc;
            if (s == null || s.trim().isEmpty()) continue;
            sb.append(s.trim()).append('\n');
        }
        return sb.toString().trim();
    }

    public static Rect findLabel(List<Node> nodes, String label) {
        // 可点击的节点优先：文字标签（TextView）常常不可点，点它旁边的容器才有效
        Rect r = findLabel(nodes, label, true);
        return r != null ? r : findLabel(nodes, label, false);
    }

    private static Rect findLabel(List<Node> nodes, String label, boolean clickableOnly) {
        for (Node n : nodes) {
            if (n.rect == null || n.rect.width() <= 0) continue;
            if (clickableOnly && !n.clickable) continue;
            if (OcrEngine.matchesLabel(n.text, label) || OcrEngine.matchesLabel(n.desc, label)) {
                return n.rect;
            }
        }
        return null;
    }

    public static Rect findKeyword(List<Node> nodes, List<String> keywords) {
        Rect r = findKeyword(nodes, keywords, true);
        return r != null ? r : findKeyword(nodes, keywords, false);
    }

    private static Rect findKeyword(List<Node> nodes, List<String> keywords, boolean clickableOnly) {
        for (String kw : keywords) {
            if (kw.isEmpty()) continue;
            for (Node n : nodes) {
                if (n.rect == null || n.rect.width() <= 0) continue;
                if (clickableOnly && !n.clickable) continue;
                if (contains(n.text, kw) || contains(n.desc, kw)) return n.rect;
            }
        }
        return null;
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.contains(kw);
    }

    /**
     * 取属性值。前面必须跟空白，否则 {@code clickable="true"} 会错误地命中
     * {@code long-clickable="true"} 的后半截。
     */
    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : "";
    }
}
