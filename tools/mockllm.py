#!/usr/bin/env python3
"""假的大模型接口，专给「读屏到点答案之间屏幕变了」这条守卫做验证用。

为什么要它：真接口的答复时间忽快忽慢（2～20 秒），想卡在「请求正在飞」的那几秒里
按 Home 切走，纯靠手速是撞运气。这个假接口先把请求拖住固定的 N 秒，再回一段写死的
JSON 答案 —— 时间窗口稳稳的，答案内容也完全可控。

用法（配合 adb reverse，让手机能访问电脑的本地端口）：

    python -I mockllm.py 8799 8            # 拖 8 秒，默认答案
    adb reverse tcp:8799 tcp:8799
    # 把 App 的接口地址指过来（改完记得改回去）：
    adb shell am broadcast -a com.moling.quizhelper.PREF \\
        -n com.moling.quizhelper/.DumpReceiver --es key baseUrl --es value http://127.0.0.1:8799/v1

第三个参数可以直接换成别的答案 JSON，比如想看多题对位就传多点条目。

It's a test double only — run it, hit it, throw it away. Not part of the APK.
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8799
DELAY = float(sys.argv[2]) if len(sys.argv) > 2 else 8.0
CONTENT = sys.argv[3] if len(sys.argv) > 3 else (
    '{"items":[{"n":1,"q":"中国的首都是？（单选题）","answer":"A",'
    '"multi":false,"explanation":"mock 固定答案"}]}'
)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(n)                      # 请求体不要了，只按固定节奏回
        time.sleep(DELAY)
        body = json.dumps({
            "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": CONTENT},
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
    print(f"mock LLM on 127.0.0.1:{PORT}, delay {DELAY}s", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
