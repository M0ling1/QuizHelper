# QuizHelper 开发说明

面向要改代码、重新构建或排查读屏问题的人。使用说明见仓库根目录的 [README](../README.md)。

## 工程结构

```
app/src/main/java/com/moling/quizhelper/
  MainActivity.java              主页、权限引导、启动悬浮窗
  SettingsActivity.java          设置页
  FloatingService.java           悬浮球窗口、状态、拖动/吸边/收起
  QuizAccessibilityService.java  读屏、取词、定位选项、点击、下一题
  OcrEngine.java                 ML Kit 中文离线 OCR
  LlmClient.java                 OpenAI 兼容接口（纯 HttpURLConnection）
  ShizukuShell.java              Shizuku 提权：screencap / input tap / uiautomator
  UiDump.java                    解析 uiautomator dump 的 XML
  AnswerParser.java              从模型输出里解析 JSON 答案
  DumpReceiver.java              adb 调试钩子：只读屏、落盘
  Prefs.java                     所有设置项和默认值
```

## 重新构建

```bash
cd QuizHelper
JAVA_HOME=/path/to/jdk-17 \
  gradle assembleRelease --no-daemon
```

或者直接跑 `build.bat`（需要把里面的路径改成本机的）。

本仓库不自带 Gradle wrapper，需要本机装 Gradle 8.7+。

### 构建环境（国内网络）

`dl.google.com` 和 `repo1.maven.org` 可能不通，所以：

- Gradle 依赖仓库全部走阿里云镜像（见 `build.gradle`）
- Gradle 发行版和 Android SDK 组件可从腾讯云镜像下载（`mirrors.cloud.tencent.com`）
- 需要 Android SDK platform-35 + build-tools 34.0.0
- 必须用 **JDK 17**（`gradle.properties` 里的 `org.gradle.java.home` 默认指向 JDK 17，按本机路径修改）

构建时那一堆 `SSL peer shut down incorrectly` 的堆栈是 AGP 尝试联网拉 SDK 清单失败，
因为本地组件已齐全，是**无害警告**，可以忽略。

## 技术选型

| 需求 | 实现 |
|---|---|
| 截图 | Shizuku `screencap -p \| base64`（首选）/ MediaProjection 前台服务 |
| 取词 | 无障碍节点树 / `uiautomator dump` / ML Kit 中文 OCR |
| 找坐标 | 无障碍 `getBoundsInScreen` / 解析 UI XML / OCR 行包围盒 |
| 点击 | `AccessibilityNodeInfo.ACTION_CLICK` → `input tap` → `dispatchGesture` |
| 大模型 | OpenAI 兼容 `chat/completions`，纯 `HttpURLConnection`，零第三方网络库 |
| 免 root 提权 | Shizuku v13，走公开 AIDL `IShizukuService.newProcess`（`Shizuku.newProcess` 在 v13 变成私有） |
| 读屏排障 | `DumpReceiver` + `tree.txt`，见下面「调试钩子」 |

每一层都有降级路径，任意一层不可用不会导致整体挂掉。

## 取词策略

默认「视觉优先」：每次答题先把整张截图发给视觉大模型——题干和选项在一起，模型一眼看全。
图是黑的、拿不到截图、模型没从图里读出题 → 自动改用无障碍文本再读一遍。

为什么默认是视觉优先：WebView 那类页面的无障碍树常常只给「光杆选项」、整行漏字
（见下面「人卫专题」），拼出来的题缺胳膊少腿；截图里这些字是实打实画着的，多模态模型
一次就看全。代价是每次答题多一次带图的请求（比纯文本贵、慢 1~2 秒），以及遇到
`FLAG_SECURE` 的 App 时先撞一次黑图——所以「读不到就改用无障碍」是配套的。

视觉模型那一栏**留空就跟着主模型走**。主模型不认图片的话第一次请求就会报错，助手会把
这条记下来（日志里写「视觉这条路走不通…这次运行不再试它」），之后全走无障碍文本——
也因此不会每两秒白试一次。想用真正的多模态就填别家的接口
（比如硅基流动 `https://api.siliconflow.cn/v1` + `Qwen/Qwen2.5-VL-72B-Instruct`）。

其余几种模式：

| 模式 | 用途 |
|---|---|
| 自动 | 先试无障碍文本（最快最省）→ 本地 OCR → 视觉大模型 |
| 仅无障碍文本 | 目标 App 是原生控件（大多数题库/驾考/问卷 App），零截图零延迟 |
| 仅本地 OCR | 题目是图片/Canvas 画的，无障碍读不到。ML Kit 中文离线识别 |
| 仅视觉大模型 | 只发截图，读不到就停（不退回无障碍） |

## FLAG_SECURE

有些 App（银行、考试、部分题库、带 DRM 的视频）会给自己窗口加 `FLAG_SECURE`。
系统会在 **SurfaceFlinger 层**把这一层内容从所有截图里剔除，拍出来是一整块黑。

| 通道 | 是否被拦 |
|---|---|
| MediaProjection（屏幕录制） | 拦 |
| Shizuku `screencap` | 拦 |
| `adb shell screencap` | 拦 |
| root / su | 拦（这层不在 App 权限体系里） |

读到黑图时默认的「视觉优先」会当场改读无障碍文本（读控件树不走截图，不受 `FLAG_SECURE`
影响——这也是为什么 TalkBack 在银行 App 里照样能念出数字），日志里会写
「截图整块是黑的（这个 App 设了 FLAG_SECURE 禁止截屏），改走无障碍文本接着读」。
想省掉那一趟黑图、每次都不截图，就把「文本来源」设成「仅无障碍文本」：连屏幕录制授权
都不用给，点悬浮球也不会再弹「开始录制」那个框。

## 调试钩子：DumpReceiver

可以用 adb 触发一次读屏、看无障碍到底能看见什么：

```bash
adb shell am broadcast -a com.moling.quizhelper.DUMP -n com.moling.quizhelper/.DumpReceiver
adb shell cat /sdcard/Android/data/com.moling.quizhelper/files/tree.txt
```

它的行为：

- 只读屏、落盘，**不会**触发 OCR、大模型、自动点击，所以在真人考试页上也能安全用
- 落盘内容：每个窗口（类名 / 包名 / 是否 active）+ 完整节点树（类名 / viewId / 子节点数 /
  文本 / 坐标 / `NOT_IMPORTANT` 标记）
- 遇到 `className: null` 的节点会自动追加它的 `toString()` 并试一次 `refresh()`
- 每次点悬浮球正常取词时也会顺手写一遍这个文件，所以平时不用专门触发

`DumpReceiver` 是 `exported` 的，做了来源校验（只认 `uid==2000/0` 或 `com.android.shell`）。
注意 adb 发过来的广播系统经常不带发送者身份（uid 返回 `-1`、包名 `null`），
这种「身份未知」的情况只能放行，否则 adb 自己也用不了——想收紧就把那段 `unknown` 分支删掉。

## 人卫专题：为什么有的 App 无障碍也读不到

「人卫」(`com.pmph.irenwei`) 的答题页是**两条路同时堵死**的，且**不需要 root 也解不开**。
别再往这上面花时间了，下面是完整的排查过程和结论。

**症状**：截图全黑（`FLAG_SECURE`），且无障碍读到的文字只有 `1/200`、`上一题`、`交卷`、`下一题`
——题目题干和 5 个选项一个字都没有。

**排查手法**（对任何读不到字的 App 都适用）：

```bash
adb shell am broadcast -a com.moling.quizhelper.DUMP -n com.moling.quizhelper/.DumpReceiver
adb shell cat /sdcard/Android/data/com.moling.quizhelper/files/tree.txt
```

这会不碰屏幕、不走 OCR、不触发自动点击地读一次屏，把**完整节点树**（类名 / viewId /
子节点数 / 文本 / 坐标 / 是否 important）落盘。光看 `dumpText()` 的文本输出是判断不出
「树里到底有没有这个控件」的——没有文字的节点在纯文本里是隐形的。

**看到的结果**：控件**都在**，坐标也对，但 item 的内容是空壳：

```
ListView id=do_subject_lv kids=6
  LinearLayout [53,494][1211,605]              ← 题干
    null kids=0 NOT_IMPORTANT [0,0][0,0]       ← 内容节点
  FrameLayout  [53,623][1211,763] clickable    ← 选项 A
    null kids=0 NOT_IMPORTANT [0,0][0,0]
```

那个空壳节点的 `toString()` 是这样的：

```
packageName: com.pmph.irenwei; className: null; text: null; enabled: false;
importantForAccessibility: false; visible: false; actions: null
```

**这是 `AccessibilityNodeInfo.obtain()` 出来但从未被初始化过的节点**——服务端（人卫）
根本没填它。`refresh()` 重取一次仍然是空的。列表**外面**的标题栏和底部按钮全都正常，
限制精确地只施加在 ListView 的 item 上。

**排除掉的可能**（都试过了）：

| 猜测 | 结论 |
|---|---|
| 布局里 `importantForAccessibility="no"` | 否。`aapt2 dump xmltree` 看了 `do_subject_vp_item` / `exam_daily_adapter_question_*_option`，没有任何该属性 |
| 是 `WebView` 的 DOM 树没生成，需要 `ACTION_ACCESSIBILITY_FOCUS` | 试了（`focusWebViews()`），无效。树里连 `WebView` 类名的节点都没有 |
| 节点 id 失效（和 ListView 重排赛跑） | 否。连读 5 次结果完全一致，不是竞态 |
| 点一下页面能让 WebView 初始化 | 否。在题干区轻点后重读，没有任何变化 |
| 从 OkHttp 缓存里捞题目 | 否。`Android/data/…/cache/responses/` 里只有 `getUserByToken`、`checkFaceInfoIsCompletely`、`getQuestionModelAndQuestionScore` 三个接口，全是账号/分值元数据，没有一道题；请求头里也没有鉴权 token |
| 抓包（MITM） | 不可行。人卫的 `network_security_config.xml` 没有 `<trust-anchors>` 覆盖，只信系统 CA，而用户装的 CA 进不了系统存储（要 root） |
| root + LSPosed 摘掉 `FLAG_SECURE` | 没试，用户明确不考虑 root。就算摘了，无障碍这层仍然是空的 |

**结论：免 root 读不到。**

**给未来新 App 的检查顺序**：① `tree.txt` 里有没有节点 → ② 节点有没有文字 →
③ 是不是空壳 `className: null` → ④ 空壳就放弃，不是空壳再调取词方式。

## 悬浮球踩过的坑（改代码前先看这里）

1. **不要用「滑出屏幕」做自动隐藏。**
   屏幕边缘 84px 是系统手势拦截区（logcat 里 `InputDispatcherExtImpl` 的
   `interception region`），留在边上的那一小条正好落在拦截区里，点它会被系统吃掉，
   球收不到任何事件。现在改成**原地缩小 + 淡掉文字**，窗口本身始终完整留在屏幕内。
2. **窗口 `alpha` 不能低于 0.8**，原因见下面「悬浮球不透明度」。
3. **悬浮窗的 `LayoutParams.y` 是相对「状态栏以下」的父框架的**，比屏幕绝对坐标少一个
   状态栏高度（实测约 140px）。不扣掉它，球会被拖到屏幕外面去。`statusBarInset()` 就是在处理这个。
4. 缩放动画要作用在**内层** `floatBall` 上，不能作用在根布局：
   View 的点击判定会跟着 scale 一起缩，缩根布局等于把触摸范围也缩了。
   根布局 `floatRoot` 不带背景，只负责占满 58dp 接触摸。

**悬浮球不透明度**可调范围锁在 **80 ~ 100**。Android 12 起，不透明度低于 0.8 的悬浮窗
会被系统判定成「不可信窗口」（untrusted touch），触摸事件可能**根本不派发**给它。
实测调到 0.36 时球是完全点不动的。想要更「通透」的观感，请靠水晶渐变本身，而不是调低这个值。

**自动收起等待**默认 10 秒，填 0 = 永不收起。

## 毛玻璃

`setBlurBehindRadius()`（API 31+）是真模糊，用之前先查 `isCrossWindowBlurEnabled()`。
不支持模糊的设备靠 `bg_float_crystal.xml` 里的多层渐变兜底（半透明球体 + 左上高光 + 内外反光边），
所以两种设备上都是水晶观感。
注意公开 API 叫 `setBlurBehindRadius`，不是 `setBackgroundBlurRadius`（后者是 @hide）。

## 读屏相关的取词细节

- 节点矩形是**全屏坐标**，计算屏幕边界要用 `WindowManager.getDefaultDisplay().getRealMetrics()`。
  用 `getResources().getDisplayMetrics()` 会扣掉状态栏，屏幕底部的节点（比如紧跟在选项下面的
  下一题题干）会被误判成离屏，导致读屏文本里丢字、`screenMoved()` 误判翻页。
- 选项按标签顺序切段（标签倒退或回到 allowed[0] 断组）；丢弃不以 A 开头的上一题尾巴；
  保留单条组；**选项没有露全时绝不点击**，先下滑再判断。
- 同一屏里出现「上一题选项 + 下一题题干」时，用 `screenMoved()` 校验前台包名与模型抄回的
  题干是否仍在当前屏幕；`stemBelowOptions()` 识别「模型答的是选项下方只露出题干的下一题」，
  先下滑再操作。
