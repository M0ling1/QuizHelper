# 答题助手 (QuizHelper)

悬浮球 → 读屏 → DeepSeek 作答 → 状态栏/气泡显示 → 自动点选项 → 自动点「下一题」。

构建产物：`app/build/outputs/apk/release/app-release.apk`（arm64-v8a，已签名）

---

## 一、安装

直接下载：[QuizHelper-1.0-arm64.apk](https://github.com/M0ling1/QuizHelper/releases/download/v1.0/QuizHelper-1.0-arm64.apk)（arm64-v8a，18 MB）

或者手机连上电脑后：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

或者把 APK 拷进手机，用文件管理器点击安装（需要允许「安装未知应用」）。

## 二、首次配置（按顺序做，缺一步就用不了）

打开 App → 点「权限/授权」，把这几项依次打开：

| 项目 | 说明 |
|---|---|
| ① 悬浮窗权限 | **必须**。不开就没有悬浮球 |
| ② 通知权限 | Android 13+ 必须，否则状态栏看不到答案 |
| ③ 无障碍服务 | **自动点击的核心**。设置 → 无障碍 → 已安装的服务 → 答题助手 → 开 |
| ④ Shizuku 授权 | 可选但强烈建议。免 root 拿到 shell 权限，能截图 + `input tap` 点击 |
| ⑤ 屏幕录制授权 | 不用 Shizuku 时的截图方式 |
| ⑥ 电池优化白名单 | 建议开，否则部分国产 ROM 会在后台杀掉服务 |

> 部分国产 ROM 额外要做的：设置 → 应用管理 → 答题助手 → 耗电管理 → 允许完全后台行为；
> 以及 最近任务里给答题助手下拉「锁定」。

然后进「设置」填 **API Key**（`sk-...`），保存。

回到主页点 **启动悬浮窗**。屏幕上会出现一个蓝色的「答」字球，可以拖动。

## 三、怎么用

1. 打开题库 App，停在题目页
2. 点一下悬浮球
3. 默认**先把这一屏的截图发给视觉大模型读题**（读不到就自动改用无障碍文本）；
   屏幕上半部分弹出气泡显示答案 + 解析；状态栏同时有一条通知（默认显示 8 秒）
4. 如果开了「自动点击答案」，它会自动点 A / C（多选按顺序点）
5. 如果开了「自动点下一题」，等 n 秒后自动找「下一题」按钮点掉

悬浮球是**圆的、半透明水晶/毛玻璃质感**：
- 拖动松手后自动吸附到最近的一侧边缘
- 静止 n 秒（默认 10 秒，设置里可改，填 0 就是不收起）后自动**收起**：缩小到 62%、淡掉球上的字

**四种颜色 = 四种状态**，一眼就知道现在该不该管它：

| 球 | 含义 | 短按一下 | 长按 |
|---|---|---|---|
| 🟢 绿「待」 | 待启动（刚启动 / 刚停） | **开始答题**（立刻读当前屏） | 暂停 → 灰 |
| 🔵 蓝「答」 | 正在答 | 重读当前屏幕 | 暂停 → 灰 |
| ⚪ 灰「停」 | 你按停的 | 先回到绿的「待启动」 | — |
| 🔴 红 | 出错停下（读不到屏 / 大模型失败 / 解析不出） | 先回到绿的「待启动」 | — |

**点了「启动悬浮窗」不会马上开答**：球先停在绿的「待启动」上，你切到题目那边、点一下球，它才开始读屏。这样启动那一下人还停在助手自己的界面上时，不会先对着无关的界面白问一次大模型、再照着答案点一遍。长按只暂停，不会把你正看的页面切走。

## 四、看日志

主页列表就是**问答日志**：时间、答案、来源、耗时、解析。点任意一条看完整题目原文，可以复制。

列表标题右边有个 **「全屏看」**，点进去是整屏的日志阅读器（深色、大字、能选中、能一次复制全部，还允许横屏 —— 长行竖屏要折三次没法读）：

- **问答日志**：就是主页那份列表，一次全摊开，不用一条条点开。
- **看运行日志**（按钮切换）：App 跑的过程逐行记的，「连答：换到新的一行」「屏幕上没读到文字」这类。查「为什么突然卡住了」看的就是这一份 —— 以前只能在电脑上 `adb shell cat .../files/asklog.txt` 拉出来看。

> 无障碍被系统关掉时（ColorOS 在 App 被强停/更新后会干这事），屏幕上会自动弹一张**引导卡**，上面有「去打开设置」按钮直接跳无障碍页。你把开关打开的那一刻卡片自己收起、球变回绿的「待启动」。

## 五、设置项说明

**取词方式**（默认「视觉优先」）

| 模式 | 什么时候用 |
|---|---|
| **视觉优先**（默认） | 每次答题**先把整张截图发给视觉大模型** —— 题干和选项在一起，模型一眼看全。图是黑的、拿不到截图、模型没从图里读出题 → **自动改用无障碍文本**再读一遍 |
| 自动 | 先试无障碍文本（最快最省）→ 本地 OCR → 视觉大模型 |
| 仅无障碍文本 | 目标 App 是原生控件（大多数题库/驾考/问卷 App），零截图零延迟 |
| 仅本地 OCR | 题目是图片/Canvas 画的，无障碍读不到。ML Kit 中文离线识别 |
| 仅视觉大模型 | 只发截图，读不到就停（不退回无障碍） |

> **为什么默认改成视觉优先**：WebView 那类页面的无障碍树常常只给「光杆选项」、整行漏字
> （见下面「人卫专题」），拼出来的题缺胳膊少腿；截图里这些字是实打实画着的，多模态模型
> 一次就看全。代价是每次答题多一次带图的请求（比纯文本贵、慢 1~2 秒），以及遇到
> `FLAG_SECURE` 的 App 时先撞一次黑图 —— 所以「读不到就改用无障碍」是配套的。
>
> 视觉模型那一栏**留空就跟着主模型走**。主模型不认图片的话第一次请求就会报错，助手会把
> 这条记下来（日志里写「视觉这条路走不通…这次运行不再试它」），之后全走无障碍文本 ——
> 也因此不会每两秒白试一次。想用真正的多模态就填别家的接口
> （比如硅基流动 `https://api.siliconflow.cn/v1` + `Qwen/Qwen2.5-VL-72B-Instruct`）。

**截图方式**：自动（Shizuku 优先）> 仅 Shizuku > 仅屏幕录制。

**顶部/底部裁剪 %**：把状态栏、App 标题栏裁掉，能显著提高 OCR 准确率。一般顶部填 6~10。

**选项标签**：默认 `A,B,C,D,E,F,G,H`。有些题库用 `①②③④` 或 `甲乙丙丁`，改这里。

**下一题关键词**：默认 `下一题,下一页,下一节,下一关,下一步,继续,确认,提交,Next,...`。
匹配规则是**包含匹配**，所以关键词别写太短（写「下一题」比写「下」好）。

**自动点下一题 + 下一题延迟**：多选/需要确认的题，延迟给大一点（2~3 秒）。

**悬浮球不透明度**：可调范围锁在 **80 ~ 100**。别想着调到更透明——
Android 12 起，不透明度低于 0.8 的悬浮窗会被系统判定成「不可信窗口」
（untrusted touch），触摸事件可能**根本不派发**给它。实测调到 0.36 时球是完全点不动的。
想要更「通透」的观感，请靠水晶渐变本身，而不是靠调低这个值。

**自动收起等待**：默认 10 秒。填 0 = 永不收起，球会一直保持原样待在边上。

**自检按钮**：一次跑完「无障碍通不通 → 能读到什么字 → **视觉大模型认不认图片** → 选项 A/B/C 能否定位 → 「下一题」按钮能否定位 → API 通不通」。
定位失败的话把结果贴出来调关键词。

## 六、常见问题

**点悬浮球提示「截图整块全黑：这个 App 设了 FLAG_SECURE 禁止截屏」**

有些 App（银行、考试、部分题库、带 DRM 的视频）会给自己窗口加 `FLAG_SECURE`。
系统会在 **SurfaceFlinger 层**把这一层内容从所有截图里剔除，拍出来是一整块黑。

**截图这条路是堵死的**，不是本 App 的实现问题：

| 通道 | 是否被拦 |
|---|---|
| MediaProjection（屏幕录制） | 拦 |
| Shizuku `screencap` | 拦 |
| `adb shell screencap` | 拦 |
| root / su | 拦（这层不在 App 权限体系里） |

**解法：默认的「视觉优先」自己就退过去了。** 拍到黑图时它会当场改读无障碍文本
（读控件树不走截图，完全不受 `FLAG_SECURE` 影响 —— 这也是为什么 TalkBack 在银行 App 里
照样能念出数字），日志里写「截图整块是黑的（这个 App 设了 FLAG_SECURE 禁止截屏），
改走无障碍文本接着读」。想省掉那一趟黑图、每次都不截图，就把「文本来源」设成
「仅无障碍文本」：连屏幕录制授权都不用给，点悬浮球也不会再弹「开始录制」那个框。

> ⚠️ 但**「无障碍也不拦」是不成立的**。原来这里写着「不拦 ✅」，被「人卫」打脸了，见下一节。

**点悬浮球提示「屏幕上没读到文字」**
- 先看自检里的「读到的文字」。空的话：
  - 无障碍没开 → 去开
  - 目标 App 用了 WebView/Unity 画界面 → 改用「本地 OCR」或「视觉大模型」
  - 没给屏幕录制/Shizuku 授权 → 去授权
  - 截图是黑的 → 见上面 `FLAG_SECURE` 那条

## 六之二、为什么有的 App 无障碍也读不到（人卫专题）

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
「树里到底有没有这个控件」的 —— 没有文字的节点在纯文本里是隐形的。

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

**这是 `AccessibilityNodeInfo.obtain()` 出来但从未被初始化过的节点** —— 服务端（人卫）
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

**结论：免 root 读不到。** 本题的取舍是「不 root 就算了」，那就在换别的题库 App 时再用。
真要攻，只有 root + LSPosed 摘 `FLAG_SECURE` 转 OCR 一条路。

**给未来新 App 的检查顺序**：① `tree.txt` 里有没有节点 → ② 节点有没有文字 →
③ 是不是空壳 `className: null` → ④ 空壳就放弃，不是空壳再调取词方式。

**点悬浮球提示「屏幕上没读到文字」**
- 先看自检里的「读到的文字」。空的话：
  - 无障碍没开 → 去开
  - 目标 App 用了 WebView/Unity 画界面 → 改用「本地 OCR」或「视觉大模型」
  - 没给屏幕录制/Shizuku 授权 → 去授权
  - 截图是黑的 → 见上面 `FLAG_SECURE` 那条
  - 节点都读到了但全是空壳 → 见上一节，没救

**能出答案但不自动点**
- 无障碍服务没开 → 自动点击完全依赖它
- 或者选项定位失败 → 自检里看「选项 A：已定位 / 未找到」，未找到就调「选项标签」
- 有些 App 的按钮 `clickable=false`，这时会自动降级成坐标点击（需要 Shizuku 或手势权限）

**自动点「下一题」没反应**
- 把按钮上的实际文字加进「下一题关键词」列表

**部分国产 ROM 过一段时间悬浮球消失**
- 电池优化白名单 + 允许完全后台 + 最近任务锁定，三件套都做

**答案不对**
- 题目文字被裁掉了一部分 → 调小「顶部裁剪」
- 系统提示词可以自己改，格式要求模型返回 `{"answer":"AC","explanation":"..."}`
- 模型换成 `deepseek-reasoner` 推理更准但更慢

## 七、重新构建

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

## 八、技术选型备忘

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

### 调试钩子

有个 `DumpReceiver`，可以用 adb 触发一次读屏、看无障碍到底能看见什么：

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
这种「身份未知」的情况只能放行，否则 adb 自己也用不了 —— 想收紧就把那段 `unknown` 分支删掉。

### 悬浮球踩过的三个坑（改代码前先看这里）

1. **不要用「滑出屏幕」做自动隐藏。**
   屏幕边缘 84px 是系统手势拦截区（logcat 里 `InputDispatcherExtImpl` 的
   `interception region`），留在边上的那一小条正好落在拦截区里，点它会被系统吃掉，
   球收不到任何事件。现在改成**原地缩小 + 淡掉文字**，窗口本身始终完整留在屏幕内。
2. **窗口 `alpha` 不能低于 0.8**，原因见上面「悬浮球不透明度」。
3. **悬浮窗的 `LayoutParams.y` 是相对「状态栏以下」的父框架的**，比屏幕绝对坐标少一个状态栏高度
   （这台机器上是 140px）。不扣掉它，球会被拖到屏幕外面去。`statusBarInset()` 就是在处理这个。
4. 缩放动画要作用在**内层** `floatBall` 上，不能作用在根布局：
   View 的点击判定会跟着 scale 一起缩，缩根布局等于把触摸范围也缩了。
   根布局 `floatRoot` 不带背景，只负责占满 58dp 接触摸。

### 毛玻璃

`setBlurBehindRadius()`（API 31+）是真模糊，用之前先查 `isCrossWindowBlurEnabled()`。
不支持模糊的设备靠 `bg_float_crystal.xml` 里的多层渐变兜底（半透明球体 + 左上高光 + 内外反光边），
所以两种设备上都是水晶观感。
注意公开 API 叫 `setBlurBehindRadius`，不是 `setBackgroundBlurRadius`（后者是 @hide）。
