package com.moling.quizhelper;

/** PlainText 的桌面自测。跑法见 tools/plaintest.bat。 */
public class PtTest {

    static int fail = 0;

    static void show(String name, String in) {
        String out = PlainText.clean(in);
        System.out.println("=== " + name + " ===");
        System.out.println("输入：" + in.replace("\n", "\\n"));
        System.out.println("输出：");
        System.out.println(out);
        System.out.println();
    }

    static void check(String name, String in, String want) {
        String got = PlainText.clean(in);
        if (!got.equals(want)) {
            fail++;
            System.out.println("!! " + name + " 不符预期");
            System.out.println("   期望：" + want.replace("\n", "\\n"));
            System.out.println("   实际：" + got.replace("\n", "\\n"));
        } else {
            System.out.println("ok " + name);
        }
    }

    public static void main(String[] a) {
        // 1) 模型最常见的花样：markdown 分段 + --- 分隔线 + 加粗
        show("markdown 大杂烩",
                "**答案：**唐朝\n\n---\n\n### 解析\n- 618 年建立\n- 都城长安\n\n---\n\n### 补充\n`贞观之治` 出现在 **李世民** 时期");

        // 2) 已经是 1. 2. 3. 的，重编号后应保持干净
        show("本来就编号了", "1. 第一段内容\n2. 第二段内容\n3. 第三段内容");

        // 3) 单段：不该加编号
        show("单段", "答案是唐朝，因为 618 年李渊建立了唐朝。");

        // 4) 正文里的数字不能被当成编号
        show("正文数字", "1.5 米等于 150 厘米。\n这是第二句话。");

        // 5) 纯分隔线伺候
        show("只有分隔线", "---\n***\n___\n");

        // 6) 代码块围栏
        show("代码块", "```json\n{\"a\":1}\n```\n后面的说明");

        // 7) 全角括号编号 / 顿号编号
        show("全角编号", "（1）甲\n（2）乙\n（3）丙");
        check("全角编号结果", "（1）甲\n（2）乙\n（3）丙", "1. 甲\n\n2. 乙\n\n3. 丙");

        // 8) 带编号但只有一行编号没内容
        check("空段丢掉", "1. 甲\n\n2.\n\n3. 丙", "1. 甲\n\n2. 丙");

        // 9) 空输入 / null
        check("空串", "", "");
        check("null", null, "");

        // 10) 引用 + 标题叠加
        check("引用标题", "> ## 结论\n> 是乙", "结论\n是乙");

        // 11) 表格线不该被吃掉内容，表头得跟数据行待在同一段里
        check("表格结果", "| 名称 | 值 |\n| --- | --- |\n| 甲 | 1 |", "名称 值\n甲 1");
        check("表头不单独占号", "前言\n\n| a | b |\n| --- | --- |\n| 丙 | 丁 |",
                "1. 前言\n\n2. a b\n丙 丁");

        // 12) 行内代码与删除线
        check("行内装饰", "这是`代码`和~~删除~~和**粗**", "这是代码和删除和粗");

        // 13) 破折号分隔线（中文全角）
        check("全角分隔线", "甲\n———\n乙", "1. 甲\n\n2. 乙");

        System.out.println(fail == 0 ? "全部通过" : (fail + " 条不符预期"));
        if (fail > 0) System.exit(1);
    }
}
