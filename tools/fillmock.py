#!/usr/bin/env python3
"""填空题测试用的假大模型：按请求里读到的屏幕文字回不同的 items。

用法：

    python -I fillmock.py 8799
    adb reverse tcp:8799 tcp:8799
    adb shell am broadcast -a com.moling.quizhelper.PREF \\
        -n com.moling.quizhelper/.DumpReceiver --es key baseUrl --es value http://127.0.0.1:8799/v1

配套的测试页在 tools/quizpage/qh_fill_*.html。只给这几页用，
换成真实答题时记得把 baseUrl 改回去。

It's a test double only — not part of the APK.
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8799


def items(*rows):
    return json.dumps({"items": list(rows)}, ensure_ascii=False)


def content_for(text):
    # 2026-10-20 用户报的两种布局（选项组下面紧跟下一题题干）：
    #   qh_next_stem.html   A B C D 下面露出「2. 下列哪些是偶数？」（选项在屏幕外）
    #   qh_split_opts.html  B C D / 「2. 下列哪些是双数？」/ A
    # 模型照着**下一题的题干**回一条答案，程序不许把它点到上一题的选项上：
    # 应该往下滑，等下一题的选项露全了再点。
    if "双数" in text:
        return items(
            {"n": 2, "q": "下列哪些是双数？", "answer": "D", "multi": False,
             "explanation": "mock 半截选项-答下面那题"},
        )
    if "整除" in text:
        return items(
            {"n": 2, "q": "下列哪些能被 2 整除？", "answer": "B", "multi": False,
             "explanation": "mock 下一题题干-答下面那题"},
        )
    # 题面编号填空页 A：数字编号的大题 + 单选 + 屏幕外数字编号的大题 + 「下一题」
    # blanks 故意带题号，验证程序会把「1. 北京」清成「北京」再填。
    if "编号填空一" in text or "编号填空三" in text:
        return items(
            {"n": 1, "q": "编号填空一：中国的首都是1.________，最长的河流是2.________。",
             "answer": "", "fill": "1. 北京 2. 长江",
             "blanks": ["1. 北京", "2. 长江"], "explanation": "mock 编号A-大题1"},
            {"n": 2, "q": "编号填空二：1 + 1 等于几？", "answer": "B", "multi": False,
             "explanation": "mock 编号A-单选"},
            {"n": 3, "q": "编号填空三：水的化学式是1.________，二氧化碳的化学式是2.________，氧气的化学式是3.________。",
             "answer": "", "fill": "1. H2O 2. CO2 3. O2",
             "blanks": ["1. H2O", "2. CO2", "3. O2"], "explanation": "mock 编号A-大题3"},
        )
    # 题面编号填空页 B：数字编号大题 + 单选 + 屏幕外普通多空大题
    if "编号填空四" in text or "编号填空五" in text or "编号填空六" in text:
        return items(
            {"n": 1, "q": "编号填空四：2 × 3 = 1.________，4 + 4 = 2.________。",
             "answer": "", "fill": "1. 6 2. 8",
             "blanks": ["6", "8"], "explanation": "mock 编号B-大题1"},
            {"n": 2, "q": "编号填空五：下列哪个是偶数？", "answer": "B", "multi": False,
             "explanation": "mock 编号B-单选"},
            {"n": 3, "q": "编号填空六：中国的首都是____，最长的河流是____。",
             "answer": "", "fill": "1. 北京 2. 长江",
             "blanks": ["北京", "长江"], "explanation": "mock 编号B-普通大题"},
        )
    # 混合页 A：手填空大题（多空）+ 单选 + 屏幕外的多空大题 + 「下一题」
    if "混合测试一" in text or "混合测试三" in text:
        return items(
            {"n": 1, "q": "混合测试一", "answer": "", "fill": "1. 北京 2. 长江",
             "blanks": ["北京", "长江"], "explanation": "mock 混合A-大题1"},
            {"n": 2, "q": "混合测试二", "answer": "B", "multi": False,
             "explanation": "mock 混合A-单选"},
            {"n": 3, "q": "混合测试三", "answer": "", "fill": "1. H2O 2. CO2",
             "blanks": ["H2O", "CO2"], "explanation": "mock 混合A-大题3"},
        )
    # 混合页 B：手填空 + 单选（点「下一题」之后）
    if "混合测试四" in text:
        return items(
            {"n": 1, "q": "混合测试四", "answer": "", "fill": "6",
             "blanks": ["6"], "explanation": "mock 混合B-填空"},
            {"n": 2, "q": "混合测试五", "answer": "B", "multi": False,
             "explanation": "mock 混合B-单选"},
        )
    # 一页三道大题、每道好几个空（第三道在屏幕下面，要滑下去）
    if "最长的河流" in text or "二氧化碳的化学式" in text:
        return items(
            {"n": 1, "q": "中国的首都是", "answer": "", "fill": "1. 北京 2. 长江",
             "blanks": ["北京", "长江"], "explanation": "mock 大题1"},
            {"n": 2, "q": "1+1=", "answer": "", "fill": "1. 2 2. 9 3. 6",
             "blanks": ["2", "9", "6"], "explanation": "mock 大题2"},
            {"n": 3, "q": "水的化学式是", "answer": "", "fill": "1. H2O 2. CO2",
             "blanks": ["H2O", "CO2"], "explanation": "mock 大题3"},
        )
    # 两页翻页测试：填完第一页该点「下一题」
    if "第一页" in text:
        return items({"n": 1, "q": "第一页 中国的首都", "answer": "", "fill": "北京",
                      "blanks": ["北京"], "explanation": "mock 第一页"})
    if "第二页" in text:
        return items({"n": 1, "q": "第二页 2 3", "answer": "", "fill": "6",
                      "blanks": ["6"], "explanation": "mock 第二页"})
    # 长页第二屏：上面那题已经滚出去了
    if "下题" in text:
        return items({"n": 1, "q": "下题 2 3", "answer": "", "fill": "6",
                      "blanks": ["6"], "explanation": "mock 下题"})
    if "上题" in text and "下题" not in text:
        return items({"n": 1, "q": "上题 中国的首都", "answer": "", "fill": "北京",
                      "blanks": ["北京"], "explanation": "mock 上题"})
    # 混排页：填空 + 单选 + 填空
    if "2 × 3" in text:
        return items(
            {"n": 1, "q": "中国的首都是", "answer": "", "fill": "北京",
             "blanks": ["北京"], "explanation": "mock 第1题"},
            {"n": 2, "q": "1 + 1 等于几", "answer": "B", "multi": False,
             "explanation": "mock 第2题"},
            {"n": 3, "q": "2 × 3", "answer": "", "fill": "6",
             "blanks": ["6"], "explanation": "mock 第3题"},
        )
    # 一道大题三个空
    if "水的化学式" in text:
        return items({"n": 1, "q": "请按顺序填空", "answer": "",
                      "fill": "1. 北京\n2. 2\n3. H2O",
                      "blanks": ["北京", "2", "H2O"], "explanation": "mock 三个空"})
    return '{"items":[]}'


def text_of(raw):
    """把**所有**消息拼起来看：系统提示词里没有题干，用户那条里才有。"""
    parts = []
    try:
        o = json.loads(raw.decode("utf-8", "ignore"))
        for m in o.get("messages", []):
            c = m.get("content")
            if isinstance(c, str):
                parts.append(c)
            elif isinstance(c, list):
                parts.extend(x.get("text", "") for x in c if isinstance(x, dict))
    except Exception:
        pass
    return "\n".join(parts) if parts else raw.decode("utf-8", "ignore")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        text = text_of(self.rfile.read(n))
        content = content_for(text)
        print("--- request %d chars -> %s" % (len(text), content[:80]), flush=True)
        body = json.dumps({
            "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": content},
            }]
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"fill mock on 127.0.0.1:{PORT}", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
