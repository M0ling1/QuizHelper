package com.moling.quizhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 读屏 + 点按。所有方法在未连接时都安全返回 false/空串。 */
public class QuizAccessibilityService extends AccessibilityService {

    private static final String TAG = "QuizA11y";
    private static volatile QuizAccessibilityService inst;
    private static volatile String foregroundPkg = "";

    public static QuizAccessibilityService get() {
        return inst;
    }

    public static String currentPackage() {
        String live = activeAppPackage();
        return live.isEmpty() ? foregroundPkg : live;
    }

    /**
     * 眼下真正在前台的那扇**应用窗口**是哪个包。
     * <p>
     * 不能只信 {@link #foregroundPkg}：它是 WINDOW_STATE_CHANGED 事件攒下来的，而
     * 事件并不总来得及时 —— 实测用户切到别的 App 后它常常停在上一个值，甚至因为
     * 我们自己弹了个广播/界面而停在**本应用**的包名上（于是「这个窗口是不是压在前台
     * App 头上」永远判错，系统的状态栏被当成了通知横幅）。窗口列表是实时问的，信它。
     */
    public static String activeAppPackage() {
        QuizAccessibilityService s = inst;
        if (s == null) return "";
        try {
            List<AccessibilityWindowInfo> ws = s.getWindows();
            if (ws == null) return "";
            for (AccessibilityWindowInfo w : ws) {
                if (w == null) continue;
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (!w.isActive()) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                return r.getPackageName().toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 当前 App 头顶上还压着一个别的窗口吗（系统弹窗、展开的通知栏、别的应用浮窗……）。
     * <p>
     * 有的话这会儿最该做的是**什么都别干**：读屏读到的是弹窗里的字，点坐标点下去
     * 落在弹窗上，题目那行反而没点到。等它自己收回去再答。
     * <p>
     * 判据要卡得很死，否则全是误报 —— 实测第一版把**常驻的状态栏**也算进去了
     * （它类型是 TYPE_SYSTEM、包名是 com.android.systemui），于是每次答题都先干等
     * 十二秒才肯读屏。所以：
     * <ul>
     *   <li>输入法不算 —— 它在最底下，而且填填空题时就是要它出来；</li>
     *   <li>本应用自己的窗口不算（悬浮球、答案气泡）；</li>
     *   <li>属于前台那个 App 的窗口不算；</li>
     *   <li>系统窗口只在它**是活动窗口**时才算 —— 状态栏/导航栏/侧边栏常年在那儿
     *       且从不活动，而展开的通知栏、权限弹窗、来电界面都是活动窗口。</li>
     * </ul>
     * 注意：**顶部横幅通知**在窗口列表里发现不了（它是画在 systemui 的状态栏窗口里
     * 的，那扇窗口的边框不变），这条守卫对横幅无效 —— 真要挡横幅得让用户别在那个
     * App 上开通知横幅。别为了它把门槛放松，误报的代价是整个答题卡住。
     */
    public boolean overlayWindowUp() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            String mine = getPackageName();
            String app = activeAppPackage();
            for (AccessibilityWindowInfo w : ws) {
                if (w == null) continue;
                int t = w.getType();
                if (t == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                String pk = r.getPackageName().toString();
                if (pk.equals(mine)) continue;
                if (!app.isEmpty() && pk.equals(app)) continue;
                if (t == AccessibilityWindowInfo.TYPE_APPLICATION) return true;   // 别的 App 的窗口
                // 系统窗口：只认活动的那个（弹窗/展开的栏），常驻的状态栏不算
                if (w.isActive()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        Log.i(TAG, "无障碍服务已连接");
        // 告诉悬浮窗这边一声：用户多半是照着屏幕上那张引导卡去设置里刚打开的，
        // 该把卡收掉、把球放回绿的「待启动」（见 FloatingService.onA11yConnected）。
        FloatingService.onA11yConnected();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && event.getPackageName() != null) {
                foregroundPkg = event.getPackageName().toString();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------- 截屏 ----------------

    /**
     * 无障碍自己截一张屏（API 30+，**不用 Shizuku，也不用屏幕录制授权**）。
     * <p>
     * 学习通这类页面选项正文在无障碍树里读不到，只能靠截图 OCR —— 可 Shizuku 不是人人
     * 都装，屏幕录制的 MediaProjection 又会被系统随时掐掉（实测掐掉之后连着九十秒每秒
     * 报一次「拿不到截图」，答题整个停在那儿）。无障碍截屏是系统给的
     * （{@code canTakeScreenshot}），只要服务还连着就一直在，正好拿它兜底。
     * <p>
     * 同步等结果（最多等 timeoutMs），拿不到返回 null。调用方在答题线程里，等一会儿不卡界面。
     */
    public Bitmap takeScreenshotNow(long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] holder = new Bitmap[1];
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer buf = null;
                    try {
                        buf = r.getHardwareBuffer();
                        // wrapHardwareBuffer 出来的位图是硬件缓冲区的只读视图，缓冲区一关就废，
                        // 而且后面还要缩放、压 JPEG —— 必须 copy 一份自己的。
                        Bitmap b = Bitmap.wrapHardwareBuffer(buf, r.getColorSpace());
                        if (b != null) holder[0] = b.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (Throwable e) {
                        Log.w(TAG, "无障碍截屏结果解析失败", e);
                    } finally {
                        if (buf != null) {
                            try {
                                buf.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.w(TAG, "无障碍截屏失败: " + errorCode);
                    latch.countDown();
                }
            });
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            Log.w(TAG, "无障碍截屏异常", e);
            return null;
        }
        return holder[0];
    }

    // ---------------- 读屏 ----------------

    /**
     * 读当前窗口的文字。
     *
     * 系统 WebView（Chromium）的 DOM 虚拟节点树是**懒加载**的：WebView 节点本身一直在
     * 无障碍树里，但它的子节点（网页里的文字）要等 WebView 被「无障碍聚焦」之后才会生成。
     * TalkBack 就是这么读网页的 —— 进来先给焦点，网页才把 DOM 暴露出来。
     * uiautomator dump 不做这一步，所以对着 WebView 页面 dump 出来永远是空的。
     */
    public String dumpText() {
        AccessibilityNodeInfo root = rootOrNull();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        walk(root, sb, 0);
        String text = sb.toString().trim();

        // 万一这一屏的节点全报空矩形（页面没量好），上面那套「屏幕外不算」会把
        // 文字全滤光。真到这一步宁可把没滤的原文交出去 —— 空手问答不出题。
        if (text.isEmpty()) {
            StringBuilder raw = new StringBuilder();
            walkAll(root, raw, 0);
            text = raw.toString().trim();
        }

        int wv = countWebViews(root);
        boolean focused = false;

        // 读到的字太少 → 怀疑页面是 WebView 且 DOM 还没生成，补一次无障碍聚焦再读。
        // 还有第二种情况：字不算少，能过 40 字这条线，但**选项只读出光秃秃的标签** ——
        // 学习通的随堂练习正是这样：题干、A B C D E 都读得到，可每个选项的正文
        // （「X染色体长臂2区2带31亚带」）压根没生成，模型拿到一屏光杆字母，答不出来。
        // 这也是 Chromium 的懒加载：只暴露了一部分 DOM，同样得聚焦一次才会补齐。
        // 所以两条触发条件取或，不能只看字数。
        if (wv > 0 && (text.length() < 40 || optionsReadAsBareLabels(root))) {
            focused = focusWebViews(root);
            SystemClock.sleep(500);
            AccessibilityNodeInfo root2 = rootOrNull();
            if (root2 != null) {
                StringBuilder sb2 = new StringBuilder();
                walk(root2, sb2, 0);
                String text2 = sb2.toString().trim();
                if (text2.length() > text.length()) {
                    text = text2;
                    root = root2;
                }
            }
        }
        // 落盘，方便 adb 排查；失败不影响取词
        writeTreeDump(root, "webviews=" + wv + " a11yFocused=" + focused + " textLen=" + text.length());
        return text;
    }

    private int countWebViews(AccessibilityNodeInfo root) {
        int n = 0;
        for (AccessibilityNodeInfo x : collectAll(root)) {
            try {
                CharSequence cn = x.getClassName();
                if (cn != null && cn.toString().contains("WebView")) n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /**
     * 这一屏的根节点。先问「活动窗口」，问不到就从窗口列表里挑一个。
     * <p>
     * 学习通那个答题 WebView 上实测会出现「活动窗口读出来是 null」的时刻（我们自己的
     * 悬浮球/气泡被摸过一下、系统弹了通知横幅、页面正在换），这时候整棵无障碍树读出来
     * 就是空的 —— 指纹成了空串、选项一个都找不到，表现就是日志里连着好几拍
     * 「读不到屏幕上的字」，以及「答案=X 点了 0 个选项」。
     * 窗口列表这时还是好的，退回去挑一个「应用窗口、且不是我们自己的界面」顶上。
     */
    private AccessibilityNodeInfo rootOrNull() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) return r;
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    int type = w.getType();
                    // 输入法、状态栏/导航栏/我们自己的悬浮球都不算「屏幕上那道题」
                    if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    if (type == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    AccessibilityNodeInfo rr = w.getRoot();
                    if (rr == null) continue;
                    CharSequence p = rr.getPackageName();
                    if (p != null && p.toString().equals(getPackageName())) continue;
                    if (type == AccessibilityWindowInfo.TYPE_APPLICATION) return rr;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 只做一次干净的遍历就返回：不补无障碍聚焦、不落盘。
     * <p>
     * 给自动连答里「页面翻没翻」这种每秒比一次的高频比对用。{@link #dumpText()} 为了
     * 救 WebView 的懒加载 DOM 会睡 500ms 再读一遍，放在循环里等不起。
     */
    public String quickText() {
        AccessibilityNodeInfo root = rootOrNull();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        walk(root, sb, 0);
        String t = sb.toString().trim();
        if (t.isEmpty()) {
            StringBuilder raw = new StringBuilder();
            walkAll(root, raw, 0);
            t = raw.toString().trim();
        }
        return t;
    }

    /**
     * 整屏文字里**把选项那一坨挖掉**之后剩下的部分：标题、题干、按钮。
     * <p>
     * 专给「这一屏翻没翻」的指纹用。选项行在 Chromium 里是**懒加载**的：点一下选项会
     * 触发一次内容更新，顺手把之前没进无障碍树的选项行补进来 —— 实测学习通第 10 题点完
     * A、C、D 之后，整屏文字从「…BDE…」变成「…BCDE…」。指纹跟着变，连答就以为翻了页：
     * 同一道题重答一遍，答案里的字母**又点了一遍**，而 WebView 的选项点一下是取消 ——
     * 刚选上的那三个全被点掉（用户看到的就是「答完就没了」）。
     * 把选项挖掉，指纹就只跟题目本身有关，点选怎么变都不动它。
     */
    public String stemText() {
        AccessibilityNodeInfo root = rootOrNull();
        if (root == null) return "";
        // 这里靠 AccessibilityNodeInfo 自带的 equals/hashCode 认「同一个节点」（比的是
        // 窗口号 + 节点号，不是节点里的文字）—— 走着走着相等的那几个节点一定是同一行。
        Set<AccessibilityNodeInfo> skip = new java.util.HashSet<>();
        try {
            List<AccessibilityNodeInfo> buf = new ArrayList<>();
            for (List<AccessibilityNodeInfo> g : optionGroups()) {
                for (AccessibilityNodeInfo n : g) {
                    buf.clear();
                    collect(n, buf, 0);
                    skip.addAll(buf);
                }
            }
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        walkSkip(root, sb, 0, skip);
        String t = sb.toString().trim();
        if (t.isEmpty()) {          // 选项挖完就什么都不剩了，那这份指纹没法用，退回整屏
            StringBuilder all = new StringBuilder();
            walk(root, all, 0);
            t = all.toString().trim();
        }
        // 同一个词被拆成两个节点读了两遍 —— 实测「提交」有时候读成一个、有时候读成两个，
        // 一多一少就让指纹自己变来变去，连答又把同一道题当成翻页（见上面那段注释）。
        // 挨着的重复行压成一行，这种「节点数抖一下」就传不到指纹上。
        return dedupLines(t);
    }

    /** 把挨着的重复行压成一行：「提交\n提交」→「提交」。只给指纹用，见 {@link #stemText}。 */
    private static String dedupLines(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        String prev = null;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.equals(prev)) continue;
            out.append(s).append('\n');
            prev = s;
        }
        return out.toString().trim();
    }

    /** 跟 {@link #walk} 一样，但跳过 {@code skip} 里的那几棵子树。 */
    private void walkSkip(AccessibilityNodeInfo n, StringBuilder sb, int depth,
                          Set<AccessibilityNodeInfo> skip) {
        if (n == null || depth > 60) return;
        if (skip.contains(n)) return;
        try {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String s = t != null ? t.toString().trim() : "";
            if (s.isEmpty() && d != null) s = d.toString().trim();
            if (!s.isEmpty() && !offScreen(n)) sb.append(s).append('\n');
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) walkSkip(child, sb, depth + 1, skip);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这一屏的选项是不是「读不到正文」——能读到的只有光秃秃的标签。
     * <p>
     * 给上层判断「这份无障碍文字能不能拿去答题」用。学习通随堂练习就是这种页面：
     * 题干、A B C D E 都在，选项正文一个都读不到，拿这串字去问模型纯属白花一次请求，
     * 不如早点退到截图 OCR / 视觉大模型 —— 屏幕上那行字是实打实画着的，截图认得出。
     */
    public boolean optionsUnreadable() {
        // 第一条路最准：直接看**认出来的那些选项组**里每一行读到的是什么。
        try {
            if (optionsReadAsBareLabelsByGroups()) return true;
        } catch (Throwable ignored) {
        }
        try {
            AccessibilityNodeInfo root = rootOrNull();
            return root != null && countWebViews(root) > 0 && optionsReadAsBareLabels(root);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 认出来的每一组选项里，每一行读到的文字**就只剩标签自己**（「A」「B」「C」「D」），
     * 正文一个字都没有。
     * <p>
     * 学习通随堂练习的实测形状：一整行是个宽 1165 的 {@code View}，自己不可点、自己也没
     * 文字，唯一的子节点是宽 119 的 {@code TextView text="A"}；正文那截 DOM 压根没进无
     * 障碍树（uiautomator 同样读不到，验过）。老的那条判据要求「宽 ≥ 300 且自己可点」，
     * 这行两条都不满足，于是被判成「选项读得到」—— 于是一屏的指纹成了
     * {@code A=A|B=B|C=C|D=D|}，**十道题全长一个样**，连答永远以为页面没翻。
     * 所以这里改看选项组的文字：组里每一行的 {@code rest} 都是空的，那就是光杆选项。
     */
    private boolean optionsReadAsBareLabelsByGroups() {
        List<List<AccessibilityNodeInfo>> groups = optionGroups();
        if (groups.isEmpty()) return false;
        int rows = 0, bare = 0;
        for (List<AccessibilityNodeInfo> g : groups) {
            for (AccessibilityNodeInfo n : g) {
                OcrEngine.Head h = OcrEngine.parseHead(optionText(n));
                if (h.label == 0) continue;
                rows++;
                if (h.rest.trim().isEmpty()) bare++;      // 「A」「A.」—— 标签后面什么都没有
            }
        }
        return rows >= 2 && bare == rows;
    }

    /**
     * 这一屏的选项是不是只读出了光秃秃的标签（「A」「B」…），正文一个字都没有。
     * <p>
     * 学习通的随堂练习（那个 {@code id=submitTest} 的 WebView）就是这个症状：每个选项
     * 外层 {@code View} 宽 1165，里面只有一个 119 宽的 {@code TextView text="A"}，
     * 正文那截 DOM 根本没暴露出来。这种页面**光看文字长度发现不了** —— 题干加五个字母
     * 早就过了 40 字那条线 —— 得看选项行本身的形状：又宽、又只剩一个标签。
     *
     * @return 这样的「光杆选项行」有 2 行以上就算中招
     */
    private boolean optionsReadAsBareLabels(AccessibilityNodeInfo root) {
        int bare = 0;
        for (AccessibilityNodeInfo n : collectAll(root)) {
            try {
                if (!n.isClickable()) continue;
                Rect r = boundsOf(n);
                if (r == null || r.width() < 300) continue;
                String t = subtreeText(n).replace(" ", "");
                if (t.isEmpty() || t.length() > 2 || !labelish(t)) continue;
                if (++bare >= 2) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 把这一棵子树的文字拼起来（自己 + 所有子孙），用来判断「这一行到底有没有正文」。 */
    private static String subtreeText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        collectText(n, sb, 0);
        return sb.toString().trim();
    }

    private static void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 12) return;
        try {
            String t = textOf(n);
            if (!t.isEmpty()) sb.append(t);
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) collectText(n.getChild(i), sb, depth + 1);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只由「标签长相」的字符组成：A-Z ａ-ｚ 0-9、全角字母数字、圈码 ①-⑳、圈字母 Ⓐ-Ⓩ、
     * 片假名，外加 . ) 、 。 这几个尾巴。
     * <p>
     * 用来把「确定」「下一题」这种按钮摘出去 —— 它们虽然也只有两三个字，但不是 CJK 之外的
     * 标签长相，不该被当成「只读出标签的选项行」。
     */
    private static boolean labelish(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || (c >= 'Ａ' && c <= 'Ｚ') || (c >= 'ａ' && c <= 'ｚ') || (c >= '０' && c <= '９')
                    || (c >= '①' && c <= '⑳') || (c >= 'Ⓐ' && c <= 'Ⓩ')
                    || (c >= 'ア' && c <= 'ン')
                    || c == '.' || c == ')' || c == '）' || c == '、';
            if (!ok) return false;
        }
        return true;
    }

    /** 给树里所有 WebView 节点补一次无障碍聚焦。返回是否至少成功聚焦了一个。 */
    private boolean focusWebViews(AccessibilityNodeInfo root) {
        boolean any = false;
        for (AccessibilityNodeInfo n : collectAll(root)) {
            try {
                CharSequence cn = n.getClassName();
                if (cn == null || !cn.toString().contains("WebView")) continue;
                if (n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) any = true;
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            } catch (Throwable ignored) {
            }
        }
        return any;
    }

    private List<AccessibilityNodeInfo> collectAll(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out, 0);
        return out;
    }

    // ---------------- 诊断落盘 ----------------

    /**
     * 把完整节点树写成文本存到 App 外部目录（/sdcard/Android/data/包名/files/tree.txt）。
     * 光看 dumpText 是判断不出「树里到底有没有 WebView 节点」的 —— 没有文字的节点
     * 在纯文本输出里是隐形的。这里连类名、viewId、子节点数一起写出来。
     */
    private void writeTreeDump(AccessibilityNodeInfo root, String note) {
        if (root == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("pkg=").append(foregroundPkg).append("  ").append(note).append('\n');
        // 进程里往往有好几个窗口（ViewRootImpl 不止一个），把每个窗口的类名和所属包也列出来
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo wi : ws) {
                    try {
                        AccessibilityNodeInfo wr = wi.getRoot();
                        sb.append("window type=").append(wi.getType())
                                .append(" layer=").append(wi.getLayer())
                                .append(" active=").append(wi.isActive())
                                .append(" focused=").append(wi.isFocused())
                                .append(" root=").append(wr == null ? "null" : wr.getClassName())
                                .append(" pkg=").append(wr == null ? "?" : wr.getPackageName())
                                .append('\n');
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sb.append("----------------\n");
        tree(root, sb, 0);
        File dir = null;
        try {
            dir = getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (dir == null) return;
        File f = new File(dir, "tree.txt");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private void tree(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 60) return;
        try {
            for (int i = 0; i < depth; i++) sb.append("  ");
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            sb.append(n.getClassName())
                    .append(" id=").append(n.getViewIdResourceName())
                    .append(" kids=").append(n.getChildCount());
            if (n.isClickable()) sb.append(" clickable");
            if (n.isFocusable()) sb.append(" focusable");
            if (!n.isImportantForAccessibility()) sb.append(" NOT_IMPORTANT");
            sb.append(' ').append(r.toShortString());
            String t = safe(n.getText()), d = safe(n.getContentDescription());
            if (!t.isEmpty()) sb.append(" text=\"").append(t).append('"');
            if (!d.isEmpty()) sb.append(" desc=\"").append(d).append('"');
            sb.append('\n');
            // 类名为 null 的是没被填充的虚拟节点（AccessibilityNodeProvider 的空壳）。
            // 把它整条 toString 打出来，再试一次 refresh()——refresh 会重新向目标 App
            // 要一次这个节点的数据，如果它只是「取早了」就能救回来。
            if (n.getClassName() == null) {
                sb.append("      RAW ").append(n.toString().replace('\n', ' ')).append('\n');
                try {
                    boolean ok = n.refresh();
                    Rect r2 = new Rect();
                    n.getBoundsInScreen(r2);
                    sb.append("      REFRESH ok=").append(ok)
                            .append(" cls=").append(n.getClassName())
                            .append(" kids=").append(n.getChildCount())
                            .append(" text=").append(safe(n.getText()))
                            .append(" desc=").append(safe(n.getContentDescription()))
                            .append(" bounds=").append(r2.toShortString())
                            .append('\n');
                } catch (Throwable e) {
                    sb.append("      REFRESH 失败 ").append(e).append('\n');
                }
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) tree(n.getChild(i), sb, depth + 1);
        } catch (Throwable ignored) {
        }
    }

    private static String safe(CharSequence cs) {
        if (cs == null) return "";
        return cs.toString().replace('\n', ' ').trim();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        walk(n, sb, depth, true);
    }

    /**
     * @param onScreenOnly 只收屏幕里的字。滚动页面（一屏多题几乎都是）的无障碍树里，
     *                     上下没露出来的那几题也在，读进来会一起丢给大模型 —— 模型答了
     *                     屏幕外的题，程序这边又找不到对应的选项组，答案就会串位。
     */
    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth, boolean onScreenOnly) {
        if (n == null || depth > 60) return;
        try {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String s = t != null ? t.toString().trim() : "";
            if (s.isEmpty() && d != null) s = d.toString().trim();
            if (!s.isEmpty() && (!onScreenOnly || !offScreen(n))) sb.append(s).append('\n');
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) walk(child, sb, depth + 1, onScreenOnly);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 不过滤屏幕外、照单全收地读一遍（只在整屏文字被滤光时当兜底用）。 */
    private void walkAll(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        walk(n, sb, depth, false);
    }

    /**
     * 这个节点是不是在屏幕外（滚动列表里上下没露出来的那些）。
     * <p>
     * 实机上看得很清楚：没露出来的行报出来的是一根**压在屏幕边缘的零高矩形**
     * （1264×2780 的屏上，屏幕下方那几题全是 {@code [42,2725][1225,2725]}），
     * 所以「宽或高为 0」就是屏幕外 —— 但 {@code [0,0][0,0]} 是个例外：
     * 那是矩形压根没填过（节点还没量好），不是「不在屏幕上」，这种得留下，
     * 否则整页文字会被丢干净。
     */
    private boolean offScreen(AccessibilityNodeInfo n) {
        Rect r = boundsOf(n);
        if (r == null) return false;
        if (r.left == 0 && r.top == 0 && r.right == 0 && r.bottom == 0) return false;
        if (r.width() <= 0 || r.height() <= 0) return true;
        // 节点矩形是**全屏坐标**（0..2780），资源里的 heightPixels 在真机上已经扣掉
        // 状态栏（实测少 140px，有的 ROM 连导航栏也扣）。拿它当底边，屏幕最下面
        // 那 140px 里的字全被当成「屏幕外」扔掉 —— 用户报的「A B C D 下面紧跟
        // 2.下一题题干」正是压在那一截里：读屏文本里没有下一题题干，
        // screenMoved 就误判成翻页，每轮都白问一次。
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
        } catch (Throwable t) {
            dm = getResources().getDisplayMetrics();
        }
        int w = dm.widthPixels, h = dm.heightPixels;
        int cx = r.centerX(), cy = r.centerY();
        return cx < 0 || cx > w || cy < 0 || cy > h;
    }

    // ---------------- 找节点 ----------------

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 60) return;
        try {
            out.add(n);
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) collect(child, out, depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private List<AccessibilityNodeInfo> allNodes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) collect(root, out, 0);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 选项行的文字：行节点自己没文字时，用它子树里那点文字。
     * <p>
     * 学习通随堂练习的选项行是个宽 {@code View}，自己的 {@code text} 和
     * {@code content-desc} 都是空的，唯一的子节点才是 {@code text="A"}。只看
     * {@link #textOf} 的话这一行等于「没有文字」，{@link #optionBars()} 一条都挑不出来
     * —— 选项组数成了 0，连答就不认这是题目页，「下一题」永远不点；答案也算不出组号，
     * 点不到任何一行。
     */
    private static String optionText(AccessibilityNodeInfo n) {
        String s = textOf(n);
        if (!s.isEmpty()) return s;
        return subtreeText(n).replaceAll("[\\s\\u00A0]+", "").trim();
    }

    private static String textOf(AccessibilityNodeInfo n) {
        try {
            CharSequence t = n.getText();
            if (t != null && t.toString().trim().length() > 0) return t.toString().trim();
            CharSequence d = n.getContentDescription();
            if (d != null) return d.toString().trim();
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static Rect boundsOf(AccessibilityNodeInfo n) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找“A. xxx”“A、xxx”“A克局跑分”“B1240nm”这种选项节点。
     * <p>
     * 分四档挑，越靠前越可信：带分隔符且可点 &gt; 带分隔符 &gt; 黏着且可点 &gt; 黏着。
     * 「可点」这条很关键：题目正文和选项在文字上可能都像以「A」开头，但选项能点、题干不能。
     * 同一档里取**最短**的 —— 选项总比题干短。
     */
    private AccessibilityNodeInfo findOption(String label) {
        AccessibilityNodeInfo strict = null, strictClick = null;
        AccessibilityNodeInfo loose = null, looseClick = null;
        int looseLen = Integer.MAX_VALUE, looseClickLen = Integer.MAX_VALUE;

        for (AccessibilityNodeInfo n : allNodes()) {
            String s = textOf(n);
            if (s.isEmpty()) continue;
            boolean click = clickableOrAncestor(n);

            if (OcrEngine.matchesLabel(s, label)) {
                if (click) {
                    if (strictClick == null) strictClick = n;
                } else if (strict == null) {
                    strict = n;
                }
            } else if (OcrEngine.matchesLabelLoose(s, label)) {
                if (click && s.length() < looseClickLen) {
                    looseClick = n;
                    looseClickLen = s.length();
                }
                if (s.length() < looseLen) {
                    loose = n;
                    looseLen = s.length();
                }
            }
        }
        if (strictClick != null) return strictClick;
        if (strict != null) return strict;
        if (looseClick != null) return looseClick;
        if (loose != null) return loose;

        // 文字认不出来（选项内容是「X」「BC」这种纯大写字母，两个匹配器都不敢认），
        // 先按「选项列」里每行自己带的标签找 —— 列里的行肯定是选项，parseHead 这时就可信了。
        AccessibilityNodeInfo inColumn = columnByLabel().get(upper(label));
        if (inColumn != null) return inColumn;

        // 真认不出来，退回按布局数第几个
        return optionByPosition(label);
    }

    private static String upper(String label) {
        if (label == null || label.isEmpty()) return "";
        return String.valueOf(Character.toUpperCase(label.charAt(0)));
    }

    /**
     * 把「选项列」每行按它**自己文字里**的标签认出来，做成 标签 → 节点 的表。
     * <p>
     * 这一条专治纯字母选项（「AX」= 标签 A + 内容 X）：全局匹配不敢认它，
     * 但只要确认这行属于选项列，行首那个字母就一定是标签，不用再猜。
     * <p>
     * 列里可能混进题干 —— 题干以「A quick…」这种字母打头时也会解析出一个标签。
     * 所以分三档往里放，先放的赢：自己就能点 &gt; 只有祖先能点 &gt; 完全不能点。
     * 选项条一般自己就能点，题干只是被套在能点的容器里，这样题干抢不走标签。
     */
    private Map<String, AccessibilityNodeInfo> columnByLabel() {
        List<AccessibilityNodeInfo> rows = optionRows();
        Map<String, AccessibilityNodeInfo> map = new LinkedHashMap<>();
        for (int pass = 0; pass < 3; pass++) {
            Map<String, Integer> len = new LinkedHashMap<>();
            for (AccessibilityNodeInfo n : rows) {
                int tier = selfClickable(n) ? 0 : (clickableOrAncestor(n) ? 1 : 2);
                if (tier != pass) continue;
                String s = optionText(n);
                OcrEngine.Head h = OcrEngine.parseHead(s);
                if (h.label == 0) continue;
                String key = String.valueOf(h.label);
                if (map.containsKey(key)) continue;          // 更高档的已经占住了这个标签
                Integer old = len.get(key);
                // 同一档里撞标签：取短的那条 —— 选项永远比题干短。
                if (old == null || s.length() < old) {
                    map.put(key, n);
                    len.put(key, s.length());
                }
            }
        }
        return map;
    }

    /** 一列选项最少/最多可能几个。多选题最多到 H，所以上限 8。 */
    private static final int MIN_OPTS = 2;
    private static final int MAX_OPTS = 8;

    /**
     * 兜底：纯按几何布局认选项。
     * <p>
     * 答题页的选项几乎永远长一个样 —— 一列**等宽、等高、整行可点**的条，纵向排开。
     * 文字认不出标签时（标签被拆成单独节点、字体认不出、图片按钮……），
     * 就靠这个把它们捞出来，按标签在设置里的次序取第 N 个。
     * <p>
     * 注意不能要求「条数 == 设置里的标签个数」—— 默认标签是 A~H 八个，
     * 而绝大多数题只有四个选项，那样就永远对不上。只要求它是一列整齐的条。
     */
    private AccessibilityNodeInfo optionByPosition(String label) {
        String allowed = AnswerParser.allowedLetters(Prefs.labels());
        int idx = allowed.indexOf(Character.toUpperCase(label.charAt(0)));
        if (idx < 0) return null;
        // 列已经被按标签认出来了，就照标签给；里面没有这个标签，那是真没有，
        // 别再按序号硬点一个（序号里混着题干，一硬点就是错一行）。
        Map<String, AccessibilityNodeInfo> byLabel = columnByLabel();
        if (byLabel.size() >= MIN_OPTS) return byLabel.get(upper(label));
        // 标签根本没出现在行文字里（被拆成独立节点之类），只好照旧按序号硬取。
        List<AccessibilityNodeInfo> rows = optionRows();
        return idx < rows.size() ? rows.get(idx) : null;
    }

    /** 找出一列「像选项的条」。找不到就返回空。 */
    private List<AccessibilityNodeInfo> optionRows() {
        return longestRun(optionBars());
    }

    /** 一列选项里的一行：标签 + 整行在屏幕上的矩形 + 这一行自己读到的文字（常常是空的）。 */
    public static final class OptRow {
        public final char label;
        public final Rect rect;
        public final String text;
        /**
         * 第几组选项（一屏多题时从 0 数起）。
         * <p>
         * 上层的「按行对齐」要按组分开算：跨组的两个相邻行标签是断的（第 1 组的 D 接着
         * 第 2 组的 A），不分组就会把「断号」当成漏了选项，凭空插出 B、C 来。
         */
        public final int group;
        OptRow(char label, Rect rect, String text, int group) {
            this.label = label;
            this.rect = rect;
            this.text = text;
            this.group = group;
        }
    }

    /**
     * 无障碍认出来的那些选项行（标签 + 矩形 + 行自己读到的文字），按屏幕从上到下。
     * <p>
     * 学习通这类页面上，无障碍只给得出「A 在哪、B 在哪」，正文一个字都没有 ——
     * 正好拿来当**位置锚**，把截图 OCR 认出来的正文按纵向塞回各自那一行
     * （见 FloatingService 里那条对齐）。
     */
    public List<OptRow> optRows() {
        List<OptRow> out = new ArrayList<>();
        try {
            int gi = 0;
            for (List<AccessibilityNodeInfo> g : optionGroups()) {
                for (AccessibilityNodeInfo n : g) {
                    OcrEngine.Head h = OcrEngine.parseHead(optionText(n));
                    if (h.label == 0) continue;
                    Rect r = boundsOf(n);
                    if (r == null || r.width() <= 0 || r.height() <= 0) continue;
                    out.add(new OptRow(h.label, r, h.rest.trim(), gi));
                }
                gi++;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 调试用：{@link #optionBars()} 每一步各剩几条 —— 认不出选项时靠它定位卡在哪一步。 */
    String barsDebug() {
        try {
            int minW = getResources().getDisplayMetrics().widthPixels * 2 / 5;
            StringBuilder sb = new StringBuilder("optionBars 诊断（minW=" + minW + "）：\n");
            int wide = 0, shaped = 0, onscreen = 0, withText = 0;
            for (AccessibilityNodeInfo n : allNodes()) {
                Rect r = boundsOf(n);
                if (r == null || r.width() < minW) continue;
                wide++;
                if (r.height() < 60 || r.height() > 500) {
                    if (wide <= 6) sb.append("  [宽不够高] ").append(r.toShortString())
                            .append(' ').append(n.getClassName()).append('\n');
                    continue;
                }
                shaped++;
                if (offScreen(n)) continue;
                onscreen++;
                String s = optionText(n);
                if (s.isEmpty() || s.length() > 60) {
                    if (shaped <= 8) sb.append("  [没文字] ").append(r.toShortString())
                            .append(' ').append(n.getClassName())
                            .append(" textOf=").append(textOf(n)).append('\n');
                    continue;
                }
                withText++;
                sb.append("  ").append(r.toShortString())
                        .append(" selfClick=").append(selfClickable(n))
                        .append(" ancClick=").append(clickableOrAncestor(n))
                        .append(" text=").append(s).append('\n');
            }
            sb.append("  节点总数=").append(allNodes().size())
                    .append(" 宽够=").append(wide).append(" 高合适=").append(shaped)
                    .append(" 在屏内=").append(onscreen).append(" 有字=").append(withText).append('\n');
            sb.append("  optionBars=").append(optionBars().size())
                    .append(" optionRows=").append(optionRows().size())
                    .append(" labelBars=").append(labelBars().size())
                    .append(" groups=").append(optionGroups().size()).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "optionBars 诊断炸了：" + t + "\n";
        }
    }

    /**
     * 所有「像选项的条」：整行宽、高度像一条、去重，按上边沿从上到下排好。
     * <p>
     * 只做筛选不做切段 —— 一屏一题时切段的是 {@link #longestRun}，一屏多题时是
     * {@link #optionGroups}。两条路都要这份原始名单，所以拆出来。
     */
    private List<AccessibilityNodeInfo> optionBars() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int minW = screenW * 2 / 5;          // 整行宽的才算，排除图标按钮

        List<AccessibilityNodeInfo> all = new ArrayList<>();
        List<AccessibilityNodeInfo> clicky = new ArrayList<>();
        List<AccessibilityNodeInfo> selfC = new ArrayList<>();
        List<Rect> seen = new ArrayList<>();
        for (AccessibilityNodeInfo n : allNodes()) {
            Rect r = boundsOf(n);
            if (r == null || r.width() < minW) continue;
            if (r.height() < 60 || r.height() > 500) continue;
            if (offScreen(n)) continue;          // 屏幕外那几题不算，见 offScreen()
            // 形状先过关再读文字：行自己没文字时得去数子树，那一步不便宜，
            // 别对页面上每一个宽节点都做一遍（见 optionText）。
            String s = optionText(n);
            if (s.isEmpty() || s.length() > 60) continue;
            // 同一块区域可能同时有容器和文字两个节点，按矩形去重
            boolean dup = false;
            for (Rect x : seen) {
                if (x.left == r.left && x.top == r.top && x.right == r.right && x.bottom == r.bottom) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            seen.add(r);
            all.add(n);
            if (selfClickable(n)) selfC.add(n);
            if (clickableOrAncestor(n)) clicky.add(n);
        }
        // 题干那行常常和选项一样宽、一样高（第 88 题就是 1053×147 一模一样），
        // 只有「可点」能把它和选项分开 —— 选项整行能点，题干不能。
        // 但反过来不成立：学习通的选项行**自己不可点、祖先也不可点**（整页只有一个
        // 可点的根容器，隔着八九层），于是「可点」那一档里只剩工具栏和标题 ——
        // 老代码直接拿它当候选，真选项反而被整列筛掉（实测 optionBars=1，
        // 认出来的是工具栏那一行），题目页就被当成「没有选项」。
        // 所以三档依次往下试，谁能聚出「一列整齐的条」就认谁。
        for (List<AccessibilityNodeInfo> cand : java.util.Arrays.asList(selfC, clicky, all)) {
            List<AccessibilityNodeInfo> cluster = widestCluster(cand);
            if (cluster.size() >= MIN_OPTS) return cluster;
        }
        return new ArrayList<>();
    }

    /**
     * 按宽度聚簇：和「最宽的那条」相差 20% 以内才算同一列，结果按上边沿从上到下排好。
     * <p>
     * 一列选项总是等宽的，工具栏、标题栏那些宽窄不一的条靠这一步摘出去。
     *
     * @return 聚出来的那一列（可能为空，也可能只有一条，由调用方判够不够）
     */
    private static List<AccessibilityNodeInfo> widestCluster(List<AccessibilityNodeInfo> cand) {
        if (cand.size() < MIN_OPTS) return new ArrayList<>();
        java.util.Collections.sort(cand, (a, b) -> boundsOf(b).width() - boundsOf(a).width());
        int base = boundsOf(cand.get(0)).width();
        List<AccessibilityNodeInfo> cluster = new ArrayList<>();
        for (AccessibilityNodeInfo n : cand) {
            Rect r = boundsOf(n);
            if (r == null) continue;
            if (r.width() * 100 >= base * 80) cluster.add(n);
        }
        java.util.Collections.sort(cluster, (a, b) -> boundsOf(a).top - boundsOf(b).top);
        return cluster;
    }

    /** 从「像选项的条」里切出最长的一段连续行 —— 一屏只有一题时，这就是那列选项。 */
    private static List<AccessibilityNodeInfo> longestRun(List<AccessibilityNodeInfo> bars) {
        List<AccessibilityNodeInfo> best = new ArrayList<>(), run = new ArrayList<>();
        for (AccessibilityNodeInfo n : bars) {
            if (run.isEmpty()) {
                run.add(n);
                continue;
            }
            Rect prev = boundsOf(run.get(run.size() - 1));
            Rect cur = boundsOf(n);
            boolean close = cur.top - prev.bottom <= prev.height() * 3 / 2;
            boolean sameSize = Math.abs(cur.height() - prev.height()) * 100 <= prev.height() * 40;
            if (close && sameSize && cur.top >= prev.bottom) {
                run.add(n);
            } else {
                if (run.size() > best.size()) best = new ArrayList<>(run);
                run = new ArrayList<>();
                run.add(n);
            }
        }
        if (run.size() > best.size()) best = new ArrayList<>(run);

        if (best.size() < MIN_OPTS || best.size() > MAX_OPTS) return new ArrayList<>();
        return best;
    }

    /**
     * 一屏多题（竖排版）时，按「标签一路往后走」把选项条切成一组一组的，从上到下。
     * <p>
     * 一道题的选项是 A B C D，下一题又从 A 开始；一屏里还可能夹着上一题滚剩的半截
     * 选项（B C D）和下一题只露出的一条 A。切分靠两件事：
     * <ul>
     *   <li>标签回到第一个（A / ① / ア）—— 下一题开始了；</li>
     *   <li>标签**往前退**（D 后面跟 A、C 后面跟 B）—— 一道题里不可能这么印，
     *       只能是两道题的分界。</li>
     * </ul>
     * 这是**唯一**能把第 2 题的答案点在第 2 题上的依据：标签全局重名，光按标签找
     * 永远会找到第一题那一行（用户报的「几条答案全点在第一题上」就是这么来的）。
     * <p>
     * 认不出标签的行（标题、广告条）不进任何一组。一段只有一条也要留着：
     * 「B C D / 2.下一题题干 / A」这种一屏，A 那条就是下一题仅露出来的一行，
     * 丢掉它这一屏就成了「一组选项都没有」，接着走单题老路，答案会被全屏乱点。
     */
    private List<List<AccessibilityNodeInfo>> optionGroups() {
        List<List<AccessibilityNodeInfo>> g = splitByLabelRun(optionBars());
        if (g.size() >= 2) return g;
        // 整行宽的条一条都挑不出来（标签和选项正文被拆成了两个节点），
        // 退一步按「纯标签小节点」那一列分组。
        List<List<AccessibilityNodeInfo>> g2 = splitByLabelRun(labelBars());
        if (g2.size() >= 2) return g2;
        // 两边都切不出「两组以上」，但只要有**一组**，那就是**一屏一题** —— 也得分出来，
        // 不能当成「这屏没有选项」。学习通的随堂练习正是这样：选项条自己不带任何文字
        // （正文那截 DOM 压根没进无障碍树），optionBars() 一条都挑不出来，只有这一列
        // 光杆标签 A B C D E 认得出。以前这里返回空，于是「认到 0 组选项」→ 答案对不上位
        // → 一个都不点，表现就是「选项读不到、也点不上」。
        if (!g.isEmpty()) return g;
        return g2;
    }

    /** 按「标签一路往后走」把一列节点切成一组一组的，从上到下。 */
    private List<List<AccessibilityNodeInfo>> splitByLabelRun(List<AccessibilityNodeInfo> bars) {
        String allowed = AnswerParser.allowedLetters(Prefs.labels());
        List<List<AccessibilityNodeInfo>> runs = new ArrayList<>();
        List<AccessibilityNodeInfo> cur = new ArrayList<>();
        char prev = 0;
        for (AccessibilityNodeInfo n : bars) {
            OcrEngine.Head h = OcrEngine.parseHead(optionText(n));
            if (h.label == 0) continue;
            if (!cur.isEmpty() && labelSequenceBreak(prev, h.label, allowed)) {
                if (cur.size() <= MAX_OPTS) runs.add(cur);
                cur = new ArrayList<>();
                prev = 0;
            }
            cur.add(n);
            prev = h.label;
        }
        if (!cur.isEmpty() && cur.size() <= MAX_OPTS) runs.add(cur);
        return trimLeadingRuns(runs, allowed);
    }

    /**
     * 这个标签相对于上一条，是不是「新的一道题又开始了」。
     * <p>
     * 一道题里选项只会 A→B→C→D 往后走；标签**重复**（又来个 A）或者**往前退**
     * （D 后面跟 A、C 后面跟 B）都只能是两道题的分界。往前跳一格（C 直接到 E）
     * 不算断：那多半是 OCR 把中间那条认丢了或者认错了，硬切反而把一道题劈成两半。
     */
    private static boolean labelSequenceBreak(char prev, char label, String allowed) {
        if (prev == 0) return false;
        if (allowed != null && !allowed.isEmpty() && label == allowed.charAt(0)) {
            return true;                    // 标签回到第一个 = 下一题
        }
        int pi = allowed == null ? -1 : allowed.indexOf(prev);
        int ci = allowed == null ? -1 : allowed.indexOf(label);
        if (pi >= 0 && ci >= 0) return ci <= pi;     // 标签表里的：倒退或重复
        return label <= prev;                        // 表外的（圈码/假名等）按字符序
    }

    /**
     * 砍掉开头那段「上一题的尾巴」。
     * <p>
     * 屏幕是从半道截出来的：上一题只剩最后几条（「C. O2 / D. NaCl」「B. 上海 / C. 广州 /
     * D. 深圳」）露在屏幕顶上，接着是下一题的题干和选项。实测的标签序列是
     * {@code C D A B C D} 或 {@code D A B C D}，前面那段没从 A 起头 —— 它的 A、B
     * 早就滚出屏幕了。
     * <p>
     * 只砍**第一段**、而且只在后面还有一段确实从第一个标签起头时才砍。不能像老代码
     * 那样「第一个 A 之前全丢」：{@code B C D / 2.下一题题干 / A} 这种一屏，第一个 A
     * 是下一题的，把前面的 B C D 全丢掉就只剩一条 A，整屏被当成「没有选项」。
     */
    private List<List<AccessibilityNodeInfo>> trimLeadingRuns(
            List<List<AccessibilityNodeInfo>> runs, String allowed) {
        if (runs.size() <= 1 || allowed == null || allowed.isEmpty()) return runs;
        char first = allowed.charAt(0);
        OcrEngine.Head h0 = OcrEngine.parseHead(optionText(runs.get(0).get(0)));
        if (h0.label == first) return runs;          // 本来就是从 A 起头，没尾巴可砍
        for (int i = 1; i < runs.size(); i++) {
            OcrEngine.Head h = OcrEngine.parseHead(optionText(runs.get(i).get(0)));
            if (h.label == first) {
                // 前面那几段都是上一题滚剩的尾巴，从这一组开始才是新题
                return new ArrayList<>(runs.subList(i, runs.size()));
            }
        }
        return runs;      // 后面也没有从 A 起头的：原样留着，让可见性判断去拦
    }

    /**
     * 兜底第二层：标签和选项正文是**两个节点**的页面（学习通随堂练习就是
     * 「A」一个节点、「客观事物及其规律」另一个节点）。
     * <p>
     * 这种页面上 {@link #optionBars} 一条都挑不出来：标签只有字母宽，正文又常常
     * 不够整行宽，于是分组无从谈起。但纯标签节点是整齐的一列 A B C D A B C D……，
     * 照样能切出「哪几个标签属于哪一题」。
     */
    private List<AccessibilityNodeInfo> labelBars() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        String allowed = AnswerParser.allowedLetters(Prefs.labels());
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<Rect> seen = new ArrayList<>();
        for (AccessibilityNodeInfo n : allNodes()) {
            String s = textOf(n).trim();
            if (s.isEmpty() || s.length() > 4) continue;          // 「A」「A.」「A、」
            OcrEngine.Head h = OcrEngine.parseHead(s);
            if (h.label == 0 || allowed.indexOf(h.label) < 0) continue;
            if (h.rest.trim().length() > 1) continue;             // 后面挂着内容 = 整行选项，不是纯标签
            Rect r = boundsOf(n);
            if (r == null || r.width() > screenW / 4) continue;    // 太宽的不是标签
            if (r.height() < 30 || r.height() > 300) continue;
            if (offScreen(n)) continue;                           // 屏幕外那几题不算
            // 正文里孤零零一个大写字母（比如「维生素 A」拆出来的）不能算：
            // 它点不动，选项标签一定点得动。
            if (!clickableOrAncestor(n)) continue;
            boolean dup = false;
            for (Rect x : seen) {
                if (x.left == r.left && x.top == r.top && x.right == r.right && x.bottom == r.bottom) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            seen.add(r);
            out.add(n);
        }
        java.util.Collections.sort(out, (a, b) -> boundsOf(a).top - boundsOf(b).top);
        return out;
    }

    /** 页面上认出了几组选项。&gt;=2 就说明这是一屏多题。 */
    public int optionGroupCount() {
        try {
            return optionGroups().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 屏幕最上面那行选项是不是「上一题的尾巴」—— 不是从标签表的第一个（A / ①）起头。
     * <p>
     * 是的话这屏上半截还压着上一题的选项（它的题干和 A、B 已经滚出去）。单题老路
     * 是「全屏找第一个字母」，答案里的 C、D 正好撞上那几行，就会点到上一题上 ——
     * 所以这种屏必须走按组点的那套。
     */
    public boolean hasLeadingOptionTail() {
        try {
            String allowed = AnswerParser.allowedLetters(Prefs.labels());
            if (allowed.isEmpty()) return false;
            List<AccessibilityNodeInfo> bars = optionBars();
            if (bars.isEmpty()) bars = labelBars();
            for (AccessibilityNodeInfo n : bars) {
                OcrEngine.Head h = OcrEngine.parseHead(optionText(n));
                if (h.label == 0) continue;
                return h.label != allowed.charAt(0);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 屏幕上有没有**至少一行**像选项的条（不要求凑够一组）。
     * <p>
     * 用来拦住「一组都没认全」时的单题老路：屏幕上明明有选项行、只是一组凑不齐
     * （比如下一题只露出一个 A），就绝不能退回「全屏找第一个字母」去点 ——
     * 那样点到哪道题上全看运气。
     */
    public boolean hasAnyOptionRow() {
        try {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int minW = screenW * 2 / 5;
            for (AccessibilityNodeInfo n : allNodes()) {
                Rect r = boundsOf(n);
                if (r == null || r.width() < minW) continue;
                if (r.height() < 60 || r.height() > 500) continue;
                if (offScreen(n)) continue;
                String s = optionText(n);
                if (s.isEmpty() || s.length() > 60) continue;
                if (OcrEngine.parseHead(s).label != 0) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 调试用：每组选项都有哪些标签，一组一条，形如 {@code "1: A,B,C,D"}。
     * <p>
     * 一屏多题点错题全靠分组，光看 {@link #optionGroupCount()} 的个数分不出
     * 「切分位置对不对」，所以把标签也吐出来复盘。
     */
    public List<String> optionGroupLabels() {
        List<String> out = new ArrayList<>();
        try {
            List<List<AccessibilityNodeInfo>> groups = optionGroups();
            for (int i = 0; i < groups.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (AccessibilityNodeInfo n : groups.get(i)) {
                    OcrEngine.Head h = OcrEngine.parseHead(optionText(n));
                    if (h.label == 0) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(h.label);
                }
                out.add((i + 1) + ": " + sb);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 某一组选项里，标签对应哪一行。 */
    private AccessibilityNodeInfo findOptionInGroup(String label, int group) {
        List<List<AccessibilityNodeInfo>> groups = optionGroups();
        if (group < 0 || group >= groups.size()) return null;
        String want = upper(label);
        for (AccessibilityNodeInfo n : groups.get(group)) {
            OcrEngine.Head h = OcrEngine.parseHead(optionText(n));
            if (h.label != 0 && String.valueOf(h.label).equals(want)) return n;
        }
        return null;
    }

    /** 这个节点自己能不能点（不算祖先）。选项条通常自己就能点，题干只是被套在能点的容器里。 */
    private static boolean selfClickable(AccessibilityNodeInfo n) {
        try {
            return n.isClickable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自己或往上八层祖先里有没有能点的。 */
    private boolean clickableOrAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            try {
                if (cur.isClickable()) return true;
                cur = cur.getParent();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** 选项在屏幕上的位置，找不到返回 null。 */
    public Rect boundsOfOption(String label) {
        return boundsOfOption(label, -1);
    }

    /**
     * 选项在屏幕上的位置。{@code group >= 0} 时只在那**一组**选项里找
     * —— 一屏多题时用，不然第 2 题的「A」会找到第 1 题那一行。
     */
    public Rect boundsOfOption(String label, int group) {
        AccessibilityNodeInfo n = group >= 0 ? findOptionInGroup(label, group) : findOption(label);
        Rect r = n == null ? null : clickTarget(n);
        // 虚拟节点有时报出一个反过来的矩形（右边界比左边界还小），
        // 照着它点坐标会点到屏幕外，看着就像「点了没反应」。这种当没找到。
        if (r == null || r.width() <= 0 || r.height() <= 0) return null;
        // 落在屏幕外的行也别点：点下去什么都不会发生，却会被记成「点中了」
        if (offScreen(n)) return null;
        return r;
    }

    /**
     * 当前这一屏所有选项的文字指纹，用来判断「题目换没换」。
     * <p>
     * 不能拿整屏文字算指纹 —— 答题页上通常挂着倒计时（「倒计时 1:55:31」），
     * 每读一次都不一样，那样永远判不出「题目没变」。选项文字才是稳定的。
     * 一个选项都没找到时返回空串，调用方自己回退。
     */
    public String optionSignature(String labels) {
        if (labels == null) return "";
        // 一屏多题：每一组的文字都得进指纹，否则只算了第一题的选项，
        // 「第 2 题换没换」判断不出来。
        List<List<AccessibilityNodeInfo>> groups = optionGroups();
        if (groups.size() >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int g = 0; g < groups.size(); g++) {
                sb.append('#').append(g).append(':');
                for (AccessibilityNodeInfo n : groups.get(g)) {
                    sb.append(stripSelected(optionText(n))).append('|');
                }
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.length(); i++) {
            AccessibilityNodeInfo n = findOption(String.valueOf(labels.charAt(i)));
            if (n == null) continue;
            sb.append(labels.charAt(i)).append('=').append(stripSelected(optionText(n))).append('|');
        }
        return sb.toString();
    }

    /**
     * 「选中」可能长什么样：勾、实心圆点、实心方块…（出现在**结尾**才算）。
     * <p>
     * 空心的一律不算 —— {@code ☐ ○ □ ☆} 是「没选」的方框/圆圈，很多页面默认就挂着它，
     * 把它当成选中态会让该点的选项一个都不点。
     */
    private static final String SELECT_GLYPH = "✓✔√☑☒●◉■★";

    /** 这行选项自己带着「已选」标记吗。只看**结尾**（含末尾的括号写法）。 */
    public static boolean hasSelectedMark(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (SELECT_GLYPH.indexOf(t.charAt(t.length() - 1)) >= 0) return true;
        // 只认「已选」那一类明确的词。光一个「选中」不收 —— 选项正文本身就可能
        // 写成「A. 选中全部」，把它当成选中态会让这个选项永远点不上。
        return t.endsWith("已选") || t.endsWith("已选中")
                || t.endsWith("(已选)") || t.endsWith("（已选）")
                || t.endsWith("(选中)") || t.endsWith("（选中）");
    }

    /**
     * 把结尾的选中标记剥掉，只留选项正文。
     * <p>
     * 用在「这一屏换没换」的指纹上。有些页面点中之后会把文字改掉
     * （「A. 狗」→「A. 狗 ✓」），指纹跟着变，连答就会以为页面翻页了：
     * 白问一次大模型不说，还会把刚点上的选项**再点一遍** —— WebView 的选项是切换的，
     * 再点一下正好取消。剥掉标记，指纹就只跟题目本身有关了。
     */
    public static String stripSelected(String s) {
        if (s == null) return "";
        String t = s.trim();
        int n = t.length();
        while (n > 0) {
            char c = t.charAt(n - 1);
            if (Character.isWhitespace(c) || SELECT_GLYPH.indexOf(c) >= 0) n--;
            else break;
        }
        String u = t.substring(0, n).trim();
        for (String w : new String[]{"已选", "已选中", "(已选)", "（已选）", "(选中)", "（选中）"}) {
            if (u.endsWith(w)) u = u.substring(0, u.length() - w.length()).trim();
        }
        // 「A. 狗（」这种剥完剩半拉括号的，一并去掉
        while (u.endsWith("(") || u.endsWith("（") || u.endsWith("[") || u.endsWith("【")) {
            u = u.substring(0, u.length() - 1).trim();
        }
        return u;
    }

    /**
     * 选项**位置**指纹：只记每行选项的上下边界。
     * <p>
     * 和 {@link #optionSignature(String)} 正好互补：那个看的是「文字变没变」，用来判题目
     * 换没换；这个看的是「位置变没变」，用来判**页面还在不在滚**。滚动不改文字，文字指纹
     * 看不出来 —— 但位置一变就是还在动。
     * <p>
     * 为什么要知道这个：滚动/翻页的惯性没停时读到的矩形，等手势真落到屏幕上早就滑走了，
     * 那一下就是「点了没反应」。点之前等指纹连续两次一样，才叫站住了。
     */
    public String layoutSignature(String labels) {
        if (labels == null) return "";
        StringBuilder sb = new StringBuilder();
        List<List<AccessibilityNodeInfo>> groups = optionGroups();
        if (groups.size() >= 2) {
            for (List<AccessibilityNodeInfo> g : groups) {
                for (AccessibilityNodeInfo n : g) {
                    Rect r = boundsOf(n);
                    if (r != null) sb.append(r.top).append(',').append(r.bottom).append(';');
                }
                sb.append('#');
            }
            return sb.toString();
        }
        for (int i = 0; i < labels.length(); i++) {
            AccessibilityNodeInfo n = findOption(String.valueOf(labels.charAt(i)));
            if (n == null) continue;
            Rect r = boundsOf(n);
            if (r != null) sb.append(i).append(':').append(r.top).append(',').append(r.bottom).append(';');
        }
        return sb.toString();
    }

    public Rect boundsOfKeyword(List<String> keywords) {
        AccessibilityNodeInfo n = findKeywordNode(keywords);
        return n == null ? null : boundsOf(n);
    }

    /**
     * 点「下一题」之前把它挪到屏幕中段：网页的翻页按钮常常贴着底边，
     * 下面是浏览器工具栏 / 系统手势条。按节点中心落触摸就会点到工具栏上，
     * 页面死活不翻（实测 Via 里「下一题」中心 y=2570，工具栏从 2556 开始）。
     * <p>
     * 先让节点自己滚进可视区（{@code ACTION_SHOW_ON_SCREEN}），还压着底边就再往上滑一小段。
     *
     * @return 关键词还在不在（在就值得接着点）
     */
    public boolean revealKeyword(List<String> keywords) {
        AccessibilityNodeInfo n = findKeywordNode(keywords);
        if (n == null) return false;
        int h = getResources().getDisplayMetrics().heightPixels;
        Rect r = boundsOf(n);
        if (r != null && r.height() > 0 && r.centerY() <= h * 0.88f) return true;
        try {
            n.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        } catch (Throwable ignored) {
        }
        sleepQuiet(350);
        n = findKeywordNode(keywords);
        r = n == null ? null : boundsOf(n);
        if (r == null) return false;
        if (r.height() > 0 && r.centerY() <= h * 0.88f) return true;
        scrollSmall();
        sleepQuiet(420);
        return true;
    }

    /**
     * 这组选项是不是**整组都完整露在屏幕里**。没露全就不许点 —— 用户明确要求：
     * 选项没出全禁止选择。
     * <p>
     * 除了每行自己的矩形要在屏幕内，还要看这组下面紧接着的那一行是不是「下一个标签」：
     * 只露出 A、B，屏幕下沿还压着 C、D 时，optionBars() 会把中心点出屏的 C、D 筛掉，
     * 光看组里剩的两行会以为「这题就两个选项」。这里去树里找组下方最近的一行带标签的
     * 选项条，标签正好接在最后一行后面（B 后面是 C），就说明整组没露完。
     */
    public static final int OPT_FULL = 0;        // 整组都在屏幕里
    public static final int OPT_CUT_BOTTOM = 1;  // 下面还压着选项（要往下滑）
    public static final int OPT_CUT_TOP = 2;     // 上面还有选项（要往上滑）

    public boolean optionGroupFullyVisible(int group) {
        return optionGroupVisibility(group) == OPT_FULL;
    }

    /**
     * 看这组选项露全了没有，没露全时还告诉调用方是上面缺还是下面缺 ——
     * 两种缺法要往相反方向滑，滑反了只会更糟。
     */
    public int optionGroupVisibility(int group) {
        try {
            List<List<AccessibilityNodeInfo>> gs = optionGroups();
            if (group < 0 || group >= gs.size()) return OPT_CUT_BOTTOM;
            List<AccessibilityNodeInfo> g = gs.get(group);
            if (g.size() < MIN_OPTS) return OPT_CUT_BOTTOM;
            int h = getResources().getDisplayMetrics().heightPixels;
            Rect first = null, last = null;
            char firstLabel = 0, lastLabel = 0;
            for (AccessibilityNodeInfo n : g) {
                Rect r = boundsOf(n);
                if (r == null || r.height() <= 0) return OPT_CUT_BOTTOM;
                if (r.top < 0) return OPT_CUT_TOP;            // 上面被切
                if (r.bottom > h) return OPT_CUT_BOTTOM;      // 下面被切
                try {
                    if (!n.isVisibleToUser()) return OPT_CUT_BOTTOM;
                } catch (Throwable ignored) {
                }
                OcrEngine.Head hd = OcrEngine.parseHead(optionText(n));
                if (first == null) {
                    first = r;
                    firstLabel = hd.label;
                }
                last = r;
                if (hd.label != 0) lastLabel = hd.label;
            }
            // 一道题的选项永远从标签表的第一个（A / ① / ア）开始印。这一组的头一行
            // 是 B、C、D，说明上面的 A 已经在屏幕外了 —— 没看全，别答，往上滑。
            // 「B C D / 下一题题干 / A」这种一屏里，B C D 就是上一题被切掉开头的那组，
            // 靠这一条拦住，不会把它的答案当成新题去点。
            String allowed = AnswerParser.allowedLetters(Prefs.labels());
            if (firstLabel != 0 && !allowed.isEmpty() && firstLabel != allowed.charAt(0)) {
                Log.i(TAG, "optGroup " + group + " 行数=" + g.size() + " 第一行=" + firstLabel
                        + "（不是 " + allowed.charAt(0) + "）→ 上面没露全");
                return OPT_CUT_TOP;
            }
            if (last == null || lastLabel == 0) return OPT_FULL;    // 认不出标签就不硬判
            // 下面还接着一行「下一个标签」：A、B 下面压着 C。
            char expect = nextOptionLabel(lastLabel);
            if (expect != 0) {
                OcrEngine.Head below = nearestLabeledRowBelow(last);
                if (below != null && below.label == expect) {
                    Log.i(TAG, "optGroup " + group + " 行数=" + g.size() + " 最后=" + lastLabel
                            + " 下方还有 " + below.label + " → 下面没露全");
                    return OPT_CUT_BOTTOM;
                }
            }
            // 上面已经滚过去一行「上一个标签」：屏幕从 B 开始，A 在屏幕上方。
            char prev = prevOptionLabel(firstLabel);
            if (prev != 0) {
                OcrEngine.Head above = nearestLabeledRowAbove(first);
                if (above != null && above.label == prev) {
                    Log.i(TAG, "optGroup " + group + " 行数=" + g.size() + " 第一行=" + firstLabel
                            + " 上方还有 " + above.label + " → 上面没露全");
                    return OPT_CUT_TOP;
                }
            }
            Log.i(TAG, "optGroup " + group + " 行数=" + g.size() + " 最后=" + lastLabel
                    + " 上下都没有邻接的选项行 → 完整");
            return OPT_FULL;
        } catch (Throwable t) {
            return OPT_CUT_BOTTOM;
        }
    }

    /** 组的上方最近一行带标签的选项条（包括被压到屏幕顶、高度为 0 的节点）。 */
    private OcrEngine.Head nearestLabeledRowAbove(Rect first) {
        OcrEngine.Head best = null;
        int bestBottom = Integer.MIN_VALUE;
        int w = getResources().getDisplayMetrics().widthPixels;
        int minW = w * 2 / 5;
        for (AccessibilityNodeInfo n : allNodes()) {
            Rect r = boundsOf(n);
            if (r == null || r.width() < minW) continue;
            // 只看第一行上面：上方的行（含贴顶压成零高的）bottom 不会超过 first.top+8
            if (r.bottom > first.top + 8) continue;
            if (r.bottom < first.top - first.height() * 4) continue; // 离太远
            if (Math.abs(r.left - first.left) > first.width() / 4) continue;
            OcrEngine.Head hd = OcrEngine.parseHead(optionText(n));
            if (hd == null || hd.label == 0) continue;
            if (r.bottom > bestBottom) {
                bestBottom = r.bottom;
                best = hd;
            }
        }
        return best;
    }

    /** 组的下方最近一行带标签的选项条（屏幕内、半截、以及 WebView 压到底边的零高节点都算）。 */
    private OcrEngine.Head nearestLabeledRowBelow(Rect last) {
        OcrEngine.Head best = null;
        int bestTop = Integer.MAX_VALUE;
        int w = getResources().getDisplayMetrics().widthPixels;
        int minW = w * 2 / 5;
        for (AccessibilityNodeInfo n : allNodes()) {
            Rect r = boundsOf(n);
            if (r == null || r.width() < minW) continue;
            // 「组下面」不能拿 last.bottom 卡：最后一行常常正好贴着屏幕下沿，
            // 被裁得 bottom=屏高；再往下的行 WebView 会压成贴底零高（top≈bottom≈屏高
            // 或略高一点），按 bottom-8 卡就把它们全漏掉了。以下半行为界就够分开
            // 最后一行自己的节点和它下面那行。
            if (r.top < last.top + Math.max(1, last.height() / 2)) continue;
            if (r.top > last.bottom + last.height() * 4) continue; // 离太远的不是同一题
            if (Math.abs(r.left - last.left) > last.width() / 4) continue;
            OcrEngine.Head hd = OcrEngine.parseHead(optionText(n));
            if (hd == null || hd.label == 0) continue;
            if (r.top < bestTop) {
                bestTop = r.top;
                best = hd;
            }
        }
        return best;
    }

    /** 选项标签的上一个：B→A、Ｅ→Ｄ、②→①；已经是第一个（A/①）或认不出就返回 0。 */
    private static char prevOptionLabel(char c) {
        if (c > 'A' && c <= 'Z') return (char) (c - 1);
        if (c > 'a' && c <= 'z') return (char) (c - 1);
        if (c > 'Ａ' && c <= 'Ｚ') return (char) (c - 1);
        if (c > '①' && c <= '⑩') return (char) (c - 1);
        return 0;
    }

    /** 选项标签的下一个：A→B、Ｄ→Ｅ、①→②；认不出序列就返回 0（不判）。 */
    private static char nextOptionLabel(char c) {
        if (c >= 'A' && c < 'Z') return (char) (c + 1);
        if (c >= 'a' && c < 'z') return (char) (c + 1);
        if (c >= 'Ａ' && c < 'Ｚ') return (char) (c + 1);
        if (c >= '①' && c < '⑩') return (char) (c + 1);
        return 0;
    }

    /** 往下滑一小段（约屏高的 17%），只用来把贴底边的按钮/选项挪出来，不整屏翻。 */
    public boolean scrollDownSmall() {
        scrollSmall();
        return true;
    }

    /** 往上滑一小段（同样 17%）：选项被切在屏幕上方时，把上面几行带回来。 */
    public boolean scrollUpSmall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            Point p = screenSize();
            if (p.x <= 0 || p.y <= 0) return false;
            int cx = p.x / 2;
            int y1 = (int) (p.y * 0.55f);
            int y2 = (int) (p.y * 0.72f);
            Path path = new Path();
            path.moveTo(cx, y1);
            path.lineTo(cx, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 240);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(g, null, null);
            Log.i(TAG, "scrollUpSmall " + y1 + "→" + y2);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "scrollUpSmall", t);
            return false;
        }
    }

    private void scrollSmall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            Point p = screenSize();
            if (p.x <= 0 || p.y <= 0) return;
            int cx = p.x / 2;
            int y1 = (int) (p.y * 0.72f);
            int y2 = (int) (p.y * 0.55f);
            Path path = new Path();
            path.moveTo(cx, y1);
            path.lineTo(cx, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 240);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(g, null, null);
            Log.i(TAG, "scrollSmall " + y1 + "→" + y2);
        } catch (Throwable t) {
            Log.w(TAG, "scrollSmall", t);
        }
    }

    private AccessibilityNodeInfo findKeywordNode(List<String> keywords) {
        List<AccessibilityNodeInfo> nodes = allNodes();
        // 先精确匹配，再包含匹配；精确匹配更可能是按钮本身
        for (String kw : keywords) {
            if (kw.isEmpty()) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (textOf(n).equalsIgnoreCase(kw)) return n;
            }
        }
        for (String kw : keywords) {
            if (kw.isEmpty()) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (textOf(n).contains(kw)) return n;
            }
        }
        return null;
    }

    // ---------------- 点击 ----------------

    private boolean clickNodeOrAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            try {
                if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                cur = cur.getParent();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    public boolean clickOption(String label) {
        return clickOption(label, -1);
    }

    /** 点某一组选项里的某个标签（一屏多题）。{@code group < 0} 就是全屏找。 */
    public boolean clickOption(String label, int group) {
        AccessibilityNodeInfo n = group >= 0 ? findOptionInGroup(label, group) : findOption(label);
        if (n == null) return false;
        // 已经是选中态就别再点：WebView 里的 div 选项点一下是**切换**，再点一次就取消了。
        // 连答的重试、或者是上一轮已经点过的字母，都会走到这儿 —— 挡住这一下，
        // 才不会出现「ACD 来回选、最后随机剩几个」。
        if (hasSelectedMark(textOf(n))) {
            Log.i(TAG, "选项 " + label + "#" + group + " 本来就带着选中标记，跳过不点："
                    + textOf(n));
            return true;
        }
        // 找到的那一「行」自己常常点不动，真正挂着点击事件的往往是里面那个小节点
        // （学习通那种「A」和正文各占一个节点的页面），或者反过来挂在外层那一行上
        // （mark.via 注入翻译浮层时，选项文字本身报的是「不可点」）。两头都找一下。
        AccessibilityNodeInfo hit = clickableAround(n);
        // 网页里的选项：落一个**合成触摸**，别用 ACTION_CLICK。
        // Chromium 对虚拟节点的 ACTION_CLICK 会**返回 true 却什么也不做** ——
        // 实测一屏 6 个选项全被静默吞掉：日志写着「答案=ABCD 点了 4 个」，
        // 截图里却一个都没选上。合成触摸走的是和手指同一条输入通路，同一次
        // 实验里点一下就中。原生控件不吃这套，ACTION_CLICK 反而更稳，所以只对
        // WebView 里的节点改走手势。
        // 判据看的是**找到的选项节点**在不在 WebView 里，不看 hit：hit 有没有、
        // 落子子孙还是落到外层，都改变不了「这是网页里的选项」这个事实。
        if (mayTap() && inWebView(n)) {
            Rect r = clickTarget(n);
            if (r != null && r.width() > 0 && r.height() > 0) {
                // 落行宽的 60% 处：躲开左边贴着标签的圆圈，也躲开右边贴边站的悬浮球
                boolean ok = tapAt(r.left + r.width() * 3 / 5, r.centerY());
                Log.i(TAG, "网页选项 " + label + "#" + group + " 走合成触摸 "
                        + r.toShortString() + " = " + ok);
                if (ok) return true;
            }
            Log.w(TAG, "网页选项 " + label + "#" + group + " 算不出落点，退回 ACTION_CLICK");
        } else {
            Log.i(TAG, "选项 " + label + "#" + group + " 走 ACTION_CLICK（"
                    + (hit == null ? "没找到能点的节点" : inWebView(n) ? "网页" : "原生") + "）");
        }
        if (hit != null && performClick(hit)) return true;
        return clickNodeOrAncestor(n);
    }

    /** 用户设置里允不允许落合成手势（0 自动 / 3 仅手势。1「仅无障碍」不允许）。 */
    private static boolean mayTap() {
        int p;
        try {
            p = Prefs.clickSource();
        } catch (Throwable t) {
            return true;
        }
        return p == 0 || p == 3;
    }

    /**
     * 这个节点是不是网页里的（WebView 的虚拟节点）。往上找几层，类名里带 WebView 就算。
     * 网页和原生控件的点击方式不一样，得分开对待。
     */
    public static boolean inWebView(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x = n;
        for (int i = 0; x != null && i < 12; i++) {
            try {
                CharSequence cn = x.getClassName();
                if (cn != null && cn.toString().contains("WebView")) return true;
                x = x.getParent();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /**
     * 某个选项现在是不是「已选中」。null = 目标 App 压根没报这个状态（WebView 常见），
     * 那就没法判断 —— 调用方别拿 null 当「没选中」。
     * <p>
     * 用来认「{@code ACTION_CLICK} 返回 true 但什么都没发生」：点之前没选、点完还没选，
     * 那就是白点了，该改用手势再点一次。
     */
    public Boolean optionSelected(String label, int group) {
        AccessibilityNodeInfo n = group >= 0 ? findOptionInGroup(label, group) : findOption(label);
        if (n == null) return null;
        try {
            return n.isChecked() || n.isSelected();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean performClick(AccessibilityNodeInfo n) {
        try {
            return n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 找到的选项行里，真正该去点的那个节点：自己能动就点自己，否则往里找第一个能点的子孙。
     * <p>
     * 不往**上**找：祖先常常是整张卡片、整个列表，点它会落到别的地方去。
     */
    private static AccessibilityNodeInfo clickableSelfOrDescendant(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return null;
        try {
            if (n.isClickable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = clickableSelfOrDescendant(n.getChild(i), depth + 1);
                if (c != null) return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 找到的选项行里，真正该去点的那个节点。先看自己/子孙，再往上找一层能点的祖先。
     * <p>
     * 往**上**找是有代价的：祖先可能是整张卡片、整个列表，点它会落到别处去。所以
     * 只往上走 {@code 3} 层，并且要求它「没比这一行大太多」—— 面积超过这一行的
     * {@code 4} 倍就当它是容器，不要了；只有「高度一样、只是更宽」的才算同一行，
     * 放行（学习通的选项就是标签窄、外层行宽）。这样既救得了「点击事件挂在外层那一行」
     * （mark.via 注入翻译浮层后，选项文字自己报「不可点」），又不会一路点到整页。
     */
    private static AccessibilityNodeInfo clickableAround(AccessibilityNodeInfo n) {
        if (n == null) return null;
        AccessibilityNodeInfo byDesc = clickableSelfOrDescendant(n, 0);
        if (byDesc != null) return byDesc;
        Rect base = boundsOf(n);
        long baseArea = base == null ? -1 : (long) base.width() * base.height();
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 3 && cur != null; i++) {
            try {
                cur = cur.getParent();
                if (cur == null) break;
                if (!cur.isClickable()) continue;
                if (baseArea <= 0) return cur;
                Rect r = boundsOf(cur);
                if (r == null) continue;
                // 要么跟这一行差不多大（面积不超 4 倍），要么是「同一行、只是更宽」——
                // 学习通那种选项就是：标签「A」宽 119，外面那行 View 宽 1165、高一样，
                // 光按面积算会被当成整块容器否掉，可它才是真正该点的那一行。
                if ((long) r.width() * r.height() <= baseArea * 4
                        || (r.height() <= base.height() * 3 / 2 + 8 && r.width() > base.width())) {
                    return cur;
                }
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * 点这个选项时坐标该落在哪儿：能点的那个自己/子孙/近祖的位置。
     * <p>
     * 之前一律取「找到的那一行」的矩形，于是碰到「行不可点、只有里面的标签可点」
     * 的页面（学习通随堂练习），手势就会落在整行 3/5 处的空白上 —— 点在行里，
     * 却没点在能点的元素上，表现就是「点了没反应」。
     */
    private static Rect clickTarget(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo hit = clickableAround(n);
        Rect r = boundsOf(hit == null ? n : hit);
        return r != null ? r : boundsOf(n);
    }

    public boolean clickKeyword(List<String> keywords) {
        AccessibilityNodeInfo n = findKeywordNode(keywords);
        if (n == null) return false;
        // 网页里的按钮跟网页里的选项一个毛病：Chromium 对虚拟节点的 ACTION_CLICK
        // 会**返回 true 却什么也不做**。学习通的「下一题」正好就是 WebView 里的节点，
        // 点不动的表现是「答案点了、页就是不翻」，连答于是反复补点。落一下合成触摸。
        if (mayTap() && inWebView(n)) {
            Rect r = clickTarget(n);
            if (r != null && r.width() > 0 && r.height() > 0) {
                boolean ok = tapAt(r.centerX(), r.centerY());
                Log.i(TAG, "关键词按钮走合成触摸 " + r.toShortString() + " = " + ok);
                if (ok) return true;
            }
            Log.w(TAG, "关键词按钮算不出落点，退回 ACTION_CLICK");
        }
        return clickNodeOrAncestor(n);
    }

    /** 直接在某点落下手势，绕过控件树。 */
    public boolean tapAt(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 45);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(g, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "tapAt", t);
            return false;
        }
    }

    // ---------------- 填空题：找输入框 / 往里填字 ----------------

    /**
     * 屏幕上**看得见、还空着**的输入框，按「从上到下、同一行从左到右」排好。
     * <p>
     * 排序就是答题顺序：模型给的空答案也是按题面先后给的，两边顺序一对，
     * 第 k 个空就填进第 k 个框。已经有字的框一律跳过 —— 用户自己填过的不覆盖；
     * 往下滑过一屏之后再读，上一屏填过的框也不在这个列表里，不会重复填。
     * <p>
     * 只认屏幕中心点落在屏幕内的框：半截在屏幕外的（滚动到一半的大题）先不填，
     * 等连答往下滑、它完整露出来再填 ——「界面外的不回答」。
     */
    public List<AccessibilityNodeInfo> emptyFields() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : visibleFields()) {
            if (textOf(n).isEmpty()) out.add(n);                 // 空着的才算
        }
        return out;
    }

    /**
     * 屏幕上看得见的**全部**输入框（空的和已经填过字的都算），顺序同 {@link #emptyFields()}。
     * <p>
     * 填完一屏之后连答会重读同一屏：这时输入框里已经有字了，只按「空框」对答案
     * 会以为一个都没填进去，于是误判成「没填完」而不去点下一题。带上已有文字，
     * 就能认出「这个空里就是刚才填的那个答案」，顺序也不会因为一部分框已经有字而错位。
     */
    public List<AccessibilityNodeInfo> visibleFields() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            AccessibilityNodeInfo root = rootOrNull();
            if (root == null) return out;
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            for (AccessibilityNodeInfo n : collectAll(root)) {
                if (!editableNode(n)) continue;
                Rect r = boundsOf(n);
                if (r == null || r.width() < 20 || r.height() < 12) continue;
                if (r.centerX() < 0 || r.centerX() > w) continue;
                if (r.centerY() < 0 || r.centerY() > h) continue;
                try {
                    if (!n.isVisibleToUser()) continue;
                } catch (Throwable ignored) {
                }
                out.add(n);
            }
            // 行内空（「甲__乙__」）中心点高度差几像素，先按 40px 一行分桶，再按左边界排
            out.sort((a, b) -> {
                Rect ra = boundsOf(a), rb = boundsOf(b);
                int ya = ra == null ? 0 : ra.centerY() / 40;
                int yb = rb == null ? 0 : rb.centerY() / 40;
                if (ya != yb) return ya - yb;
                int xa = ra == null ? 0 : ra.left;
                int xb = rb == null ? 0 : rb.left;
                return xa - xb;
            });
        } catch (Throwable t) {
            Log.w(TAG, "visibleFields", t);
        }
        return out;
    }

    private static boolean editableNode(AccessibilityNodeInfo n) {
        try {
            if (n.isEditable()) return true;
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cls = n.getClassName();
            String s = cls == null ? "" : cls.toString();
            return s.contains("EditText") || s.endsWith(".EditText");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 屏幕**下方**是不是还压着空着的输入框（滚出屏幕的输入框在无障碍树里还在）。
     * <p>
     * 填空题长页面靠它决定「这一屏填完了要不要往下滑」：横排长卷子的下半截、
     * 一道大题被屏幕砍掉的那几个空，都在屏幕下边躺着。实测那些节点报的是贴着底边
     * 的零高矩形，所以「中心点在屏幕下方」或者「零高且顶边已经到底了」都算。
     * 屏幕上方（已经划过去）的不算 —— 往下滑只会看到更多，不会回到上面。
     */
    public boolean hasEmptyFieldsBelow() {
        try {
            AccessibilityNodeInfo root = rootOrNull();
            if (root == null) return false;
            int h;
            try {
                DisplayMetrics dm = new DisplayMetrics();
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.getDefaultDisplay().getRealMetrics(dm);
                h = dm.heightPixels;
            } catch (Throwable t) {
                h = getResources().getDisplayMetrics().heightPixels;
            }
            for (AccessibilityNodeInfo n : collectAll(root)) {
                if (!editableNode(n)) continue;
                if (!textOf(n).isEmpty()) continue;
                Rect r = boundsOf(n);
                if (r == null || r.width() <= 0) continue;
                if (r.centerY() > h) return true;
                if (r.height() <= 0 && r.top >= h - 2) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "hasEmptyFieldsBelow", t);
        }
        return false;
    }

    /** 输入法键盘是不是正盖在屏幕上。 */
    public boolean isImeVisible() {
        try {
            // 窗口坐标是全屏坐标（0..2780），资源里的 heightPixels 在部分 ROM 上
            // 已经扣掉了状态栏；两个体系混着算，底边永远对不上。
            int h;
            try {
                DisplayMetrics dm = new DisplayMetrics();
                android.view.WindowManager wm =
                        (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                wm.getDefaultDisplay().getRealMetrics(dm);
                h = dm.heightPixels;
            } catch (Throwable t) {
                h = getResources().getDisplayMetrics().heightPixels;
            }
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                Rect b = new Rect();
                try {
                    w.getBoundsInScreen(b);
                } catch (Throwable ignored) {
                }
                // 窗口已经收起来但还留在列表里（或者只剩个壳）时不能算数：
                // 这时候按返回，收不到键盘，退掉的就是网页本身。
                // 但也不能只认 active/focused：搜狗弹出「来自剪贴板」那种悬浮条时，
                // 焦点在它身上，输入法窗口报 active=false focused=false —— 真机实测
                // 键盘明明占了下半屏，却死活认为「没有键盘」，于是不收、随后滚屏
                // 的手势落在键盘上，把答案改成了别的字。
                // 用窗口位置兜底：键盘露出来时贴着屏幕下沿、占屏高 1/5 以上；
                // 收起来时窗口被压在屏幕下方（实测 [0,2630][1264,3674]，底边超出屏幕）。
                boolean shown = b.height() > h / 5
                        && b.top >= h / 3
                        && b.bottom >= h - h / 40
                        && b.bottom <= h + h / 40;
                if (w.isActive() || w.isFocused() || shown) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 收掉输入法键盘。WebView 收到 {@code ACTION_SET_TEXT} 之后常常会把光标焦点给输入框，
     * 键盘自己弹出来盖住下半屏 —— 视觉读屏（ocrSource=4）拍到的就是「页面只剩上半截」，
     * 后面的选项和空都被当成看不全，连答还会反复等「弹窗压着」。
     * <p>
     * 只在确认键盘真的在的时候按一次返回：键盘在的时候返回键只收键盘，不会把页面退出去；
     * 没等它弹出来就按，退掉的就是网页本身了。所以先最多等 {@code waitMs} 毫秒。
     *
     * @return 是否真的收掉了一个键盘
     */
    public boolean hideIme(long waitMs) {
        boolean seen = false;
        for (long waited = 0; waited <= waitMs; waited += 150) {
            if (isImeVisible()) {
                seen = true;
                break;
            }
            if (waited < waitMs) sleepQuiet(150);
        }
        Log.i(TAG, "hideIme seen=" + seen + " wait=" + waitMs);
        if (!seen) return false;
        // 窗口列表有时会晚一拍才把「键盘已经收了」反映出来。这里再确认一次：
        // 键盘要是正好自己收了，这一下返回就会落到网页上 —— 实测会把整页刷新掉，
        // 填好的空、点上的选项全没了。宁可这次不收，等下一轮再说。
        sleepQuiet(140);
        if (!isImeVisible()) return false;
        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Throwable t) {
            Log.w(TAG, "hideIme", t);
            return false;
        }
        for (int i = 0; i < 5; i++) {
            sleepQuiet(120);
            if (!isImeVisible()) return true;
        }
        return false;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 输入框的屏幕坐标，日志里标「填的是哪一个」用。 */
    public static Rect boundsOfNode(AccessibilityNodeInfo n) {
        return boundsOf(n);
    }

    /** 输入框里当前显示的文字（已填过的空用来认出「就是刚才那个答案」）。 */
    public static String textOfField(AccessibilityNodeInfo n) {
        return textOf(n);
    }

    /**
     * 把文字填进一个输入框。
     * <p>
     * 先走 {@code ACTION_SET_TEXT}（不弹输入法、不动用户的光标）；WebView 里有相当一部分
     * 虚拟节点会「回 true 却什么也没改」，所以 refresh 后读一下确认。没读回来再走
     * 「聚焦 + 粘贴」那条兜底 —— 学习通那种页面两条路总有一条能进去。
     *
     * @return 是否认为已经填进去了
     */
    public boolean setFieldText(AccessibilityNodeInfo n, String text) {
        if (n == null || text == null || text.isEmpty()) return false;
        boolean claimed = false;
        try {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            claimed = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            if (claimed) {
                try {
                    n.refresh();
                } catch (Throwable ignored) {
                }
                if (!textOf(n).isEmpty()) return true;
            }
            Log.i(TAG, "setFieldText ACTION_SET_TEXT=" + claimed + "，走粘贴兜底");
        } catch (Throwable t) {
            Log.w(TAG, "setFieldText/set", t);
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("答案", text));
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean pasted = n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            Log.i(TAG, "setFieldText 粘贴=" + pasted);
            return pasted || claimed;
        } catch (Throwable t) {
            Log.w(TAG, "setFieldText/paste", t);
            return claimed;
        }
    }

    /**
     * 这一屏底下是不是还压着内容 —— 竖排的长卷子（一页七题那种）翻屏用。
     * <p>
     * 判据是「有文字、但高度被压成 0」的节点。WebView 会把整个 DOM 都塞进无障碍树，
     * 屏幕外的那些文本节点边界会被压平在可视区下沿（实测 {@code [35,2725][1232,2725]}），
     * 高度为 0 就说明它此刻没露出来。有两行以上这种节点，就值得往下滑一屏。
     * <p>
     * 为什么不看 {@code isScrollable()}：WebView 的滚动条节点常常报 false，
     * 单看它会把能滑的页面判成滑不动。
     */
    public boolean hasMoreBelow() {
        try {
            AccessibilityNodeInfo root = rootOrNull();
            if (root == null) return false;
            int flat = 0;
            for (AccessibilityNodeInfo n : collectAll(root)) {
                if (textOf(n).isEmpty()) continue;
                Rect r = boundsOf(n);
                if (r == null) continue;
                if (r.height() <= 0 && r.width() > 200 && ++flat >= 2) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "hasMoreBelow", t);
        }
        return false;
    }

    /**
     * 往下滑一屏。先试无障碍自己的 {@code ACTION_SCROLL_FORWARD}（不打扰用户、
     * 也不受触摸派发限制），那条路走不通再落一次合成手势从下往上划。
     *
     * @return 有没有真的滑出去
     */
    public boolean scrollDown() {
        try {
            AccessibilityNodeInfo root = rootOrNull();
            if (root != null) {
                for (AccessibilityNodeInfo n : collectAll(root)) {
                    try {
                        if (!n.isScrollable()) continue;
                        if (n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                            Log.i(TAG, "scrollDown 走 ACTION_SCROLL_FORWARD " + n.getClassName());
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scrollDown/fwd", t);
        }
        // 手势兜底：从屏幕下方三分之一划到上方三分之一
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            Point p = screenSize();
            if (p.x <= 0 || p.y <= 0) return false;
            int cx = p.x / 2;
            int y1 = (int) (p.y * 0.62f);
            int y2 = (int) (p.y * 0.28f);
            Path path = new Path();
            path.moveTo(cx, y1);
            path.lineTo(cx, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 260);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            boolean ok = dispatchGesture(g, null, null);
            Log.i(TAG, "scrollDown 走合成手势 " + y1 + "→" + y2 + " = " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "scrollDown/gesture", t);
            return false;
        }
    }

    /** 屏幕真实像素尺寸（拿不到就退回 0，由调用方判空）。 */
    private Point screenSize() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return new Point();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect r = wm.getCurrentWindowMetrics().getBounds();
                return new Point(r.width(), r.height());
            }
            Display d = wm.getDefaultDisplay();
            Point p = new Point();
            d.getRealSize(p);
            return p;
        } catch (Throwable t) {
            return new Point();
        }
    }
}
