package com.moling.quizhelper;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻前台服务：悬浮球 + 截图 + OCR + 请求大模型 + 通知/播报 + 自动点击。
 */
public class FloatingService extends Service {

    private static final String TAG = "FloatingService";

    public static final String ACTION_START = "com.moling.quizhelper.START";
    public static final String ACTION_STOP = "com.moling.quizhelper.STOP";
    public static final String ACTION_ASK = "com.moling.quizhelper.ASK";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private static final int NOTIF_SERVICE = 0x9101;
    private static final int NOTIF_ANSWER = 0x9102;
    /** 「无障碍被系统关掉了，点这里去打开」那条通知（见 notifyA11yOff）。 */
    private static final int NOTIF_A11Y = 0x9103;
    private static final String CH_SERVICE = "svc";
    private static final String CH_ANSWER = "answer";

    private static volatile boolean running = false;
    /**
     * 没在答。暂停期间：自动连答不跑、点球也不答，只有长按悬浮球还能操作。
     * <p>
     * 静态的，好让主界面也能读到当前状态。
     */
    private static volatile boolean paused = false;
    /**
     * 待启动（绿球）：启动了，但还没开答，点一下才真开跑。
     * <p>
     * 永远是 {@code paused} 的一部分 ——「待启动」也是「没在答」，所以所有
     * {@code if (paused)} 的闸门都自动管住了它，不用挨个去改。
     * 绿和灰的区别只有「点一下会开答（绿）」和「点一下先回到绿（灰）」。
     */
    private static volatile boolean armed = false;

    /** 当前活着的那个服务实例。遥控口（DumpReceiver）要用它触发暂停/恢复。 */
    private static volatile FloatingService inst;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isPaused() {
        return paused;
    }

    /** 球是不是停在绿的「待启动」上（见 {@link #arm}）。 */
    public static boolean isArmed() {
        return armed;
    }

    /** 遥控口用：暂停回答。服务和 UI 线程都活着才有效。 */
    public static void pauseForDebug() {
        FloatingService s = inst;
        if (s != null) s.ui.post(() -> s.pause("adb 遥控口"));
    }

    public static void resumeForDebug() {
        FloatingService s = inst;
        if (s != null) s.ui.post(() -> s.resume("adb 遥控口"));
    }

    /**
     * 遥控口用：替用户在球上按一下（短按那一下干的事，见 {@link #tapBall}）。
     * <p>
     * 手机锁着屏的时候 {@code input tap} 点不到悬浮球（悬浮窗压在锁屏下面），
     * 而「绿→点→蓝」这条链子恰恰只有真按一下才走得通。留这个口子，
     * 锁着屏也能把三种颜色的迁移验一遍。
     */
    public static void tapBallForDebug() {
        FloatingService s = inst;
        if (s != null) s.ui.post(s::tapBall);
    }

    /**
     * 遥控口用：探一下三条截屏路各自通不通。
     * <p>
     * 为什么值得单独探：学习通那类页面选项正文读不到，每答一题都得截一次图。屏幕录制那条
     * （MediaProjection）会被系统随时掐掉，掐掉之后表现是「拿不到截图」—— 可那句话说不了
     * 清到底是没授权、被掐了、还是压根没连上。这里三条分开试、分开报，一眼看得见该修哪条。
     */
    static String probeScreenshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("Shizuku 已授权=").append(ShizukuShell.granted()).append('\n');
        if (ShizukuShell.granted()) {
            try {
                byte[] dec = Base64.decode(cleanB64(ShizukuShell.raw("screencap -p | base64")), Base64.DEFAULT);
                Bitmap b = BitmapFactory.decodeByteArray(dec, 0, dec.length);
                sb.append("  Shizuku 截图=").append(b == null ? "解不开" : b.getWidth() + "x" + b.getHeight())
                        .append('\n');
            } catch (Throwable t) {
                sb.append("  Shizuku 截图失败=").append(t).append('\n');
            }
        }

        FloatingService s = inst;
        if (s == null) {
            sb.append("悬浮窗服务没在跑，屏幕录制/无障碍截屏都试不了\n");
        } else {
            sb.append("  MediaProjection 已连=").append(s.imageReader != null).append('\n');
            if (s.imageReader != null) {
                s.setOverlayVisible(false);
                try {
                    Bitmap b = s.grabProjection();
                    sb.append("  屏幕录制截图=")
                            .append(b == null ? "拿不到（可能被系统掐了）" : b.getWidth() + "x" + b.getHeight())
                            .append('\n');
                } catch (Throwable t) {
                    sb.append("  屏幕录制截图失败=").append(t).append('\n');
                } finally {
                    s.setOverlayVisible(true);
                }
            }
        }

        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null) {
            sb.append("  无障碍服务没连，无障碍截屏试不了\n");
        } else {
            s = inst;
            // 悬浮球不撤掉就会被拍进图里（它是 TYPE_SYSTEM 应用窗口）—— 真答题时那条路也这么干。
            if (s != null) s.setOverlayVisible(false);
            try {
                Bitmap b = svc.takeScreenshotNow(2500);
                sb.append("  无障碍截屏=")
                        .append(b == null ? "拿不到" : b.getWidth() + "x" + b.getHeight())
                        .append('\n');
            } catch (Throwable t) {
                sb.append("  无障碍截屏失败=").append(t).append('\n');
            } finally {
                if (s != null) s.setOverlayVisible(true);
            }
        }
        return sb.toString();
    }

    /**
     * 这次是不是用**普通 startService** 起来的 —— 只有这种情况下我们才没有
     * 「5 秒内必须升前台」的义务，可以不挂那条常驻通知。
     *
     * <p>静态的，因为 {@code onStartCommand} 看不出自己是怎么被起的。默认 true：
     * 没经过本方法（比如系统重启服务）就当普通启动处理，最多是通知被摘掉，
     * 不影响答题。
     */
    private static volatile boolean plainStart = true;

    public static void start(Context ctx, int code, Intent data) {
        Intent i = new Intent(ctx, FloatingService.class).setAction(ACTION_START);
        i.putExtra(EXTRA_CODE, code);
        if (data != null) i.putExtra(EXTRA_DATA, data);
        Prefs.init(ctx);
        if (code == 0 && !Prefs.keepServiceNotif()) {
            // 用户不要那条常驻状态栏通知，而这次又不需要 MediaProjection
            // （投影一开，系统强制前台服务）——先按普通服务起，头顶就干净了。
            // 后台启动限制会直接抛 IllegalStateException，那就退回下面那条路。
            try {
                plainStart = true;
                ctx.startService(i);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "普通 startService 被拦，改走前台服务", t);
            }
        }
        plainStart = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /**
     * 遥控口（DumpReceiver）发 ASK 用的：服务没在跑时这是唯一起得来的路，只能走前台服务，
     * 所以手动把 {@link #plainStart} 压掉 —— 否则上一条 START 留下的 true 会让
     * onStartCommand 跳过 startForeground，被系统当成「5 秒没升前台」直接杀掉。
     */
    public static void startAsk(Context ctx) {
        Intent i = new Intent(ctx, FloatingService.class).setAction(ACTION_ASK);
        plainStart = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, FloatingService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // ---------------- 状态 ----------------

    private WindowManager wm;
    private Handler ui;
    private ExecutorService worker;
    private TextToSpeech tts;

    private View floatView;
    /** 真正的那颗球（水晶圆），闲置时缩放它，而不是外层容器，避免触摸范围跟着缩。 */
    private View floatBall;
    private WindowManager.LayoutParams floatLp;
    private View bubble;
    private WindowManager.LayoutParams bubbleLp;
    private TextView floatLabel;
    /** 无障碍被关掉时压在屏幕上的引导卡（见 {@link #showA11yGuide}）。 */
    private View guideView;
    private WindowManager.LayoutParams guideLp;

    /** 悬浮球直径，和 view_float_button.xml 里的 58dp 保持一致。 */
    private static final int FLOAT_DP = 58;
    /** 闲置后缩小的比例。 */
    private static final float IDLE_SCALE = 0.62f;

    private ValueAnimator floatAnimator;
    private boolean tucked;
    private final Runnable tuckTask = this::tuckToEdge;

    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private int capW, capH, capDpi;

    private volatile boolean busy;
    private long lastAskAt;
    /**
     * 上一次读到的「选项指纹」。自动连答每轮先比这个：一样就说明页面根本没翻，
     * 那就只再试一次「下一题」，绝不重答 —— 多选题重答会把已经选上的选项点掉。
     */
    private volatile String lastScreenKey = "";
    /** 上一次写进日志的那个指纹（只为别把日志刷爆，见 doLoopStep 里那行）。 */
    private String keyLogged = "";
    /** 「页面没动」连着几拍了。到 {@link #STUCK_LIMIT} 就停下（见 doLoopStep）。 */
    private int sameScreenTicks;
    /** 连着几拍读不到屏幕指纹（见 doLoopStep 里那个空指纹分支）。 */
    private int blankKeyTicks;
    /**
     * 同一屏连着多少拍没进展就停下。连答假定的用法是<strong>用户全程不动</strong>，
     * 所以「答完了却一直不翻页」只有两种解释：这屏压根没有「下一题」（最后一页/结算页），
     * 或者「下一题」没点上。两种都不该靠重读重答去蒙 —— 重读重答正是用户看到的
     * 「同一道题反复问、选上的选项被反复点掉」。等够这么多拍就停，交给用户。
     */
    private static final int STUCK_LIMIT = 8;
    /**
     * 这一屏上已经点过的字母。同一个字母在同一屏上不再点第二下 ——
     * 多选题里再点一次就是<strong>取消</strong>，用户报的「一直在反复按同一个答案」
     * 就是这么来的（重答一次 → 把刚选上的又点掉 → 下一轮再点上…）。
     */
    private String clickedLettersKey = "";
    private final Set<String> clickedLetters = new LinkedHashSet<>();
    /**
     * 一屏多题时「哪道题点过哪个字母」。键是题干指纹（{@link #stemKey}），不是整屏指纹：
     * 往下滑一屏之后第一题滚出去了，整屏指纹会变，但第 2 题还在，不能再点一遍。
     */
    private final Map<String, Set<String>> clickedByStem = new HashMap<>();
    /**
     * 这一屏上已经填过字的输入框（用「左边界,上边界」当键）。换屏清空。
     * <p>
     * 有些 WebView 输入框 {@code ACTION_SET_TEXT} 回 true 却不把字回读到节点上，
     * 光看「框里还是空的」会以为没填、下一拍再填一遍 —— 同一屏填两遍本身无害，
     * 但填空题的答案是按顺序发给空框的，多占一个位置后面的答案就整体错位。
     */
    private final Set<String> filledFields = new LinkedHashSet<>();
    private String filledFieldsKey = "";
    /**
     * 每个大题已经填进去的答案，按填写先后排（键是题干指纹 {@link #stemKey}）。
     * <p>
     * 一页好几道大题、每道好几个空时，往下滑一屏之后上面填过的空会滚出屏幕；
     * 模型如果还把整道大题的 blanks 全抄回来，只按「第几个答案对第几个框」就会把
     * 第一道大题的答案又填进第二道大题的框里。记着每道题已经填过哪几个答案，
     * 重读时把回复里与它开头对上的一截扣掉，剩下的才是这个屏还没填的。
     */
    private final Map<String, List<String>> filledByStem = new HashMap<>();

    /** 一个待填的空：属于哪道大题（题干指纹）+ 该填什么。 */
    private static final class BlankSlot {
        final String stem;
        final String answer;
        BlankSlot(String stem, String answer) {
            this.stem = stem;
            this.answer = answer;
        }
    }
    /**
     * 当前这一屏**到底答出来没有**。连答里那条「点一下下一题」只在这一屏真答出来之后才准跑。
     * <p>
     * 用户报的「大模型请求失败也直接下一题了」就是这么来的：请求超时走 {@code finish()}、
     * 或者模型给了答复但解析不出选项，这两种情况这一屏其实一个选项都没点，
     * 可下一轮循环看到屏幕没变，照样去点「下一题」，等于白白跳过一题。
     */
    private volatile boolean lastRoundAnswered;
    /**
     * 这一屏往下滑了几次。竖排长卷子靠它一屏屏往下走（见 {@link Prefs#autoScroll()}），
     * 但同一屏滑两次还是原样就说明这页压根不能滚，别一直划拉 —— 换屏时清零。
     */
    private int scrollTries;
    /**
     * 因为「选项只露出半截」往下滑了几次。选项一直露不全（比如被浏览器工具栏压住、
     * 页面已经到底）时不能无限滑，滑到上限就宁可不答这题，也不能没看全就点。
     */
    private int optScrollTries;
    /**
     * 这一屏「读到了、但没答出来」之后重读了两次。
     * <p>
     * 失败多半出在那一次读屏上（截图 OCR 认漏了选项标签之类），重读重问一次通常就好；
     * 但必须有上限 —— 一轮轮地重问正是用户报过的毛病。换屏时清零。
     */
    private int reaskTries;
    /**
     * 这一轮是「模型明确说答不了」（选项没显示全、题干是半截），**不是**请求失败。
     * <p>
     * 两者都让 {@link #lastRoundAnswered} 变成 false，但对往下滑的意义正好相反：
     * 请求失败时页面上的题是好的，滑走等于把它跳过去；而「选项没显示全」恰恰要往下滑
     * 才能让它完整露出来。用户报的「第三题选项没出来就点了 BC，滑下去又补点了 A」
     * 就是这一条没分开造成的。
     */
    private volatile boolean lastRoundBlank;
    /**
     * 「这一轮问答」的序号。每开一轮 {@code doAsk} 就 +1，请求发出时记下自己那一号。
     * <p>
     * 大模型偶尔会拖十几秒才回话，那时候屏幕早就翻过好几题了 —— 拿着这份迟到的答案去点，
     * 就会在别的题上乱按一通（用户报的「隔了十几秒突然弹出来，然后屏幕乱按一下」）。
     * 回话时先对一下号、再对一下屏幕指纹：不是自己那一轮、或者屏已经换了，就只记日志不点。
     */
    private volatile int askEpoch;
    /** 当前这一轮问的是哪一屏（指纹）。迟到的答复跟它不匹配就不许点。 */
    private volatile String askKey = "";
    /**
     * 上一次答题时前台是哪个 App。连答只认这个 App —— 手机来电话、弹出别的 App，
     * 屏幕就不再是题目了，而连答那套「点下一题 / 重读一屏」会照着当前屏幕乱来：
     * 实测对着通话界面问过一次大模型，还照着「我的订单」页去找「下一题」关键词
     * （关键词里有「确认」，那页上正躺着「确认收货」）。
     */
    private String quizPkg = "";
    /**
     * 「前台是本应用自己的设置页/日志页」这条已经记过一次了。用户开着设置页看日志的时候
     * 连答还在跑，不记这个就每 2 秒刷一行。
     */
    private boolean ownAppNoted;
    /**
     * 最近一张截图是**谁**给的（Shizuku / 屏幕录制 / 无障碍截屏）。
     * 三条路谁先拿到算谁的，出问题时（截图不对、OCR 读错字）得知道该怀疑哪一条。
     */
    private volatile String shotSrcName = "";
    /**
     * 视觉这条路已经确认走不通了（模型不认图片、模型名/地址填错）：这次运行内别再试。
     * <p>
     * 只记「再发一百次也是同一个结果」的失败（4xx 那类）。超时、断网、限流不算 ——
     * 那些下一拍很可能就通了。不记的话，连答时每两秒白拍一张图、白发一次请求。
     * 每次启动悬浮窗重新给一次机会（用户可能刚把模型名改对）。
     */
    private volatile boolean visionDead;
    /** 视觉为什么废了，日志和提示里说人话用。 */
    private String visionDeadWhy = "";
    /** 「有通知横幅压着，等它走」连着等了几次。 */
    private int bannerWaits;
    /** 连着失败了几轮。用来把重试间隔一轮一轮往后拖，别拿大模型出气。 */
    private int failStreak;
    private List<OcrEngine.Line> lastLines = new ArrayList<>();
    /**
     * 上一次「按行对齐」算出来的选项几何（标签 → 落点矩形），点击时兜底用
     * （见 {@link #alignedBounds}）：无障碍会把整行漏掉，那时候只有这份几何知道
     * 「选项 C 在屏幕的哪个位置」。
     */
    private List<QuizAccessibilityService.OptRow> alignedRows = new ArrayList<>();
    /**
     * 一串「答完 → 稍等一下点下一题」还排着队。连答在答完那一拍也会看到「屏幕没变」，
     * 要是它自己再点一次「下一题」，就等于连点两下跳过一整题（用户报过「答完第 1 题
     * 一路窜到第 5 题」）。所以排队期间连答不许碰「下一题」。
     */
    private volatile boolean nextPending;
    private final Runnable pendingNext = this::doClickNext;
    private final Runnable pendingLoop = this::doLoopStep;
    /** 循环里那次「重新读屏」，单独包一层是为了在服务已经停掉时不要再触发。 */
    private final Runnable loopAsk = () -> {
        if (!running || paused) return;
        if (busy) {                       // 上一轮还没跑完，退回循环再等一轮，别让链条断掉
            ui.postDelayed(pendingLoop, 1000);
            return;
        }
        ask();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // paused 是静态的，会跨实例活着。服务重新起来就是一次新的开始，
        // 别让上一轮的「暂停」把新起的悬浮球也摁住 —— 那就成了「点了没反应」。
        paused = false;
        armed = false;      // 同上：新起一次就是新的待启动（onStartCommand 里会重新置上）
        inst = this;
        Prefs.init(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        ui = new Handler(Looper.getMainLooper());
        worker = Executors.newSingleThreadExecutor();
        createChannels();
        initTts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        // 这次是「从没跑 → 起来」，还是「已经跑着又被 start 一次」（录屏重连、通知里点回来）。
        // 只有前者才回到待启动，后者别把正在答的那一轮给掐了。
        boolean freshStart = !running;

        int code = intent != null ? intent.getIntExtra(EXTRA_CODE, 0) : 0;
        Intent data = intent != null ? intent.getParcelableExtra(EXTRA_DATA) : null;
        boolean withProjection = code != 0 && data != null;

        // 不挂常驻通知时：普通服务起来的就压根不升前台；
        // 若是前台服务起来的（投影 / 被后台限制逼的），先升上去应个卯再摘掉通知。
        if (withProjection || !plainStart || Prefs.keepServiceNotif()) {
            startForegroundCompat(withProjection);
        } else {
            foregroundShown = false;
        }
        if (withProjection) setupProjection(code, data);
        showFloat();
        running = true;
        // 新起一轮，视觉这条路重新给它一次机会（上一轮废掉多半是因为模型名/地址填错，
        // 用户说不定刚改回来了）。
        visionDead = false;
        visionDeadWhy = "";
        // 上一轮跑完停了一会儿，球可能已经缩成边上的小点了；现在要开跑，先摊开 ——
        // running 置位之后再调，scheduleTuck 才会按「运行中不缩」处理（见那边注释）。
        untuck();

        if (ACTION_ASK.equals(action)) {
            // 遥控口明说「现在答这一屏」，那就当已经确认过了，不用再点一下球。
            disarm();
            ui.postDelayed(this::ask, 150);
        } else if (freshStart) {
            // 启动之后先停在绿球「待启动」，**不**自己开答。
            // 以前这儿是「开着自动连答就直接起跑」，可启动那一下人还在助手自己的界面上
            // （或者停在设置页、录屏授权页），直接起跑就会先对着那一屏白问一次大模型、
            // 再照着答案点一遍 —— 点到的全是无关的界面。点了球才算「我人已经到题目上了」。
            arm();
        } else if (Prefs.autoLoop() && !paused) {
            ui.postDelayed(loopAsk, 2000);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        inst = null;
        releaseUi();
        releaseProjection();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转后重建投影，否则截图尺寸不对
        if (projection != null) {
            Point p = screenSize();
            if (p.x != capW || p.y != capH) {
                releaseVirtualDisplay();
                buildVirtualDisplay();
            }
        }
    }

    private void shutdown() {
        running = false;                 // 先掐掉标志，循环里排队的那些 runnable 就不会再干活
        armed = false;
        cancelNextClick();
        ui.removeCallbacks(pendingLoop);
        ui.removeCallbacks(loopAsk);
        lastScreenKey = "";              // 下次起来是新的一屏，别拿旧的指纹去比
        sameScreenTicks = 0;
        scrollTries = 0;
        keyLogged = "";
        blankKeyTicks = 0;
        clickedLettersKey = "";
        clickedLetters.clear();
        ownAppNoted = false;
        failStreak = 0;
        lastRoundAnswered = false;
        stopForeground(true);
        stopSelf();
    }

    // ---------------- 前台通知 ----------------

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel svc = new NotificationChannel(CH_SERVICE,
                getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel ans = new NotificationChannel(CH_ANSWER,
                getString(R.string.channel_answer), NotificationManager.IMPORTANCE_HIGH);
        ans.setShowBadge(true);
        ans.enableVibration(false);
        nm.createNotificationChannel(ans);
    }

    private Notification buildServiceNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(armed ? "悬浮球待启动，点一下开始答题"
                        : (paused ? "已暂停回答，点悬浮球继续"
                        : "正在答题，点它重读当前屏幕（长按暂停）"))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .build();
    }

    /** 现在前台通知到底挂着没有 —— 关掉常驻通知时就别再去 notify 了。 */
    private boolean foregroundShown;

    private void startForegroundCompat(boolean withProjection) {
        Notification n = buildServiceNotification();
        int type = 0;
        if (withProjection) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        } else if (Build.VERSION.SDK_INT >= 34) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
                ServiceCompat.startForeground(this, NOTIF_SERVICE, n, type);
            } else {
                startForeground(NOTIF_SERVICE, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground", t);
            startForeground(NOTIF_SERVICE, n);
        }
        foregroundShown = true;
        // 关掉常驻通知、又不是投影（系统强制前台）：升完这一下应个卯就把通知摘掉。
        // 不升不行 —— startForegroundService 起来的不在 5 秒内 startForeground 会被系统杀掉；
        // 升完再 stopForeground 是允许的，服务本身照跑（进程还睁着眼，无障碍服务吊着呢）。
        if (!withProjection && !Prefs.keepServiceNotif()) {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            foregroundShown = false;
        }
    }

    // ---------------- 投影 ----------------

    private void setupProjection(int code, Intent data) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return;
            if (projection != null) releaseProjection();
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) {
                Log.w(TAG, "getMediaProjection 返回 null");
                return;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "投影被系统停止");
                    ui.post(() -> releaseProjection());
                }
            }, ui);
            Point p = screenSize();
            capW = p.x;
            capH = p.y;
            capDpi = getResources().getDisplayMetrics().densityDpi;
            buildVirtualDisplay();
        } catch (Throwable t) {
            Log.w(TAG, "setupProjection", t);
        }
    }

    private void buildVirtualDisplay() {
        try {
            imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay("quizhelper",
                    capW, capH, capDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, ui);
        } catch (Throwable t) {
            Log.w(TAG, "buildVirtualDisplay", t);
        }
    }

    private void releaseVirtualDisplay() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {
        }
        try {
            if (imageReader != null) imageReader.close();
        } catch (Throwable ignored) {
        }
        virtualDisplay = null;
        imageReader = null;
    }

    private void releaseProjection() {
        releaseVirtualDisplay();
        try {
            if (projection != null) projection.stop();
        } catch (Throwable ignored) {
        }
        projection = null;
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Point(dm.widthPixels, dm.heightPixels);
    }

    private Bitmap grabProjection() {
        if (imageReader == null) return null;
        Image img = null;
        long deadline = SystemClock.uptimeMillis() + 2500;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                img = imageReader.acquireLatestImage();
            } catch (Throwable t) {
                return null;
            }
            if (img != null) break;
            sleep(60);
        }
        if (img == null) return null;
        try {
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixStride = plane.getPixelStride();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (pixStride == 4 && rowStride == w * 4) {
                buf.rewind();
                bmp.copyPixelsFromBuffer(buf);
            } else {
                byte[] row = new byte[rowStride];
                int[] argb = new int[w];
                for (int y = 0; y < h; y++) {
                    if (y * rowStride >= buf.limit()) break;
                    buf.position(y * rowStride);
                    int avail = Math.min(rowStride, buf.remaining());
                    buf.get(row, 0, avail);
                    for (int x = 0; x < w; x++) {
                        int o = x * pixStride;
                        if (o + 2 >= avail) {
                            argb[x] = 0xFF000000;
                        } else {
                            argb[x] = 0xFF000000
                                    | ((row[o] & 0xFF) << 16)
                                    | ((row[o + 1] & 0xFF) << 8)
                                    | (row[o + 2] & 0xFF);
                        }
                    }
                    bmp.setPixels(argb, 0, w, 0, y, w, 1);
                }
            }
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "grabProjection", t);
            return null;
        } finally {
            try {
                img.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 悬浮球 ----------------

    private void showFloat() {
        if (floatView != null) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        floatView = inflater.inflate(R.layout.view_float_button, null);
        floatBall = floatView.findViewById(R.id.floatBall);
        floatLabel = floatView.findViewById(R.id.tvFloatLabel);
        paintBall(!paused ? BALL_RUN : (armed ? BALL_ARMED : BALL_PAUSED));
        setFloatLabel(armed ? "待" : (paused ? "停" : "答"));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        floatLp = new WindowManager.LayoutParams(
                dp(FLOAT_DP),
                dp(FLOAT_DP),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        floatLp.gravity = Gravity.TOP | Gravity.START;
        applyCrystalBlur(floatLp);

        Point p = screenSize();
        int x = Prefs.floatX(), y = Prefs.floatY();
        if (x < 0 || x > p.x - dp(FLOAT_DP)) x = p.x - dp(FLOAT_DP) - dp(6);
        if (y < 0 || y > p.y - statusBarInset() - dp(FLOAT_DP)) y = p.y / 2;
        // 启动时直接吸附到最近的一侧，避免开机后球飘在屏幕中间
        x = snappedX(x + dp(FLOAT_DP) / 2 < p.x / 2);
        floatLp.x = x;
        floatLp.y = y;
        floatLp.alpha = safeAlpha(Prefs.floatAlpha() / 100f);

        floatView.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            long downAt;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelFloatAnimator();
                        ui.removeCallbacks(tuckTask);
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = floatLp.x;
                        startY = floatLp.y;
                        downAt = SystemClock.uptimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        if (moved) {
                            // 一旦开始拖，就当用户想把它拿出来
                            if (tucked) {
                                tucked = false;
                                fadeFloatLabel(true);
                                scaleFloatBall(1f);
                                floatLp.alpha = safeAlpha(Prefs.floatAlpha() / 100f);
                            }
                            floatLp.x = startX + dx;
                            floatLp.y = startY + dy;
                            try {
                                wm.updateViewLayout(floatView, floatLp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            snapToEdge();
                        } else if (SystemClock.uptimeMillis() - downAt > 500) {
                            // 长按：答着的时候是「暂停」（只停，不顺手回桌面 —— 回桌面那一下
                            // 会把用户正看着的页面切走）；已经停着（绿球待启动 / 灰球暂停）
                            // 或者球红了（出错）的时候，长按回本软件主界面 —— 这两种状态下
                            // 用户多半就是想回去改设置、看日志。
                            if (tucked) untuck();
                            if (paused || armed || ballState == BALL_ERROR) {
                                openMain();
                            } else {
                                pause("长按悬浮球");
                            }
                        } else {
                            // 短按：绿球（待启动）点一下才开始答，灰/红（停了）点一下先回到绿球，
                            // 蓝球（正在答）点一下重读当前屏。见 tapBall。
                            if (tucked) untuck();
                            tapBall();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(floatView, floatLp);
            scheduleTuck();
        } catch (Throwable t) {
            Log.w(TAG, "addView float", t);
        }
    }

    // ---------------- 水晶质感 / 贴边 / 自动隐藏 ----------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 悬浮窗的 y 是相对「状态栏以下」的父框架的，实测比屏幕绝对坐标少一个状态栏高度。
     * 不扣掉它的话，球能一直被拖到屏幕外面去。
     */
    private int statusBarInset() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 真正的「毛玻璃」是窗口级的背景模糊（Android 12+）。
     * 系统关闭了「跨窗口模糊」时不申请，否则只是白费一次 IPC。
     * drawable 那层水晶渐变负责兜底，所以这里失败也无所谓。
     */
    private void applyCrystalBlur(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (w == null || !w.isCrossWindowBlurEnabled()) return;
            lp.setBlurBehindRadius(dp(18));
        } catch (Throwable t) {
            Log.w(TAG, "背景模糊不可用", t);
        }
    }

    private int floatSize() {
        return floatView != null && floatView.getWidth() > 0
                ? floatView.getWidth() : dp(FLOAT_DP);
    }

    private boolean isLeftSide() {
        Point p = screenSize();
        return floatLp.x + floatSize() / 2 < p.x / 2;
    }

    private int snappedX(boolean left) {
        Point p = screenSize();
        int inset = dp(6);
        return left ? inset : p.x - floatSize() - inset;
    }

    private void cancelFloatAnimator() {
        if (floatAnimator != null) {
            try {
                floatAnimator.cancel();
            } catch (Throwable ignored) {
            }
            floatAnimator = null;
        }
    }

    private void animateFloatTo(int x, int y, float alpha, long dur) {
        cancelFloatAnimator();
        final int sx = floatLp.x, sy = floatLp.y;
        final float sa = floatLp.alpha;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(dur);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            float f = an.getAnimatedFraction();
            floatLp.x = Math.round(sx + (x - sx) * f);
            floatLp.y = Math.round(sy + (y - sy) * f);
            floatLp.alpha = sa + (alpha - sa) * f;
            try {
                wm.updateViewLayout(floatView, floatLp);
            } catch (Throwable ignored) {
            }
        });
        a.start();
        floatAnimator = a;
    }

    /** 收起的时候把「答」字淡掉，只留一颗安静的水晶球。 */
    private void fadeFloatLabel(boolean visible) {
        if (floatLabel == null) return;
        floatLabel.animate().cancel();
        floatLabel.animate().alpha(visible ? 1f : 0f).setDuration(180).start();
    }

    /** 缩放的是内层那颗球，外层容器始终保持 58dp 的触摸范围。 */
    private void scaleFloatBall(float scale) {
        if (floatBall == null) return;
        floatBall.animate().cancel();
        floatBall.animate().scaleX(scale).scaleY(scale).setDuration(220).start();
    }

    /**
     * 窗口自身的不透明度，永不低于 0.8。
     * Android 12 起，不透明度低于 0.8 的悬浮窗会被系统当成「不可信窗口」，
     * 触摸事件可能直接不派发给它——实测 alpha 降到 0.36 后，球就彻底点不动了。
     */
    private float safeAlpha(float want) {
        return Math.max(0.8f, want);
    }

    /** 松手后吸附到最近的一侧边缘，并重新计时自动隐藏。 */
    private void snapToEdge() {
        if (floatView == null) return;
        tucked = false;
        fadeFloatLabel(true);
        scaleFloatBall(1f);
        Point p = screenSize();
        int size = floatSize();
        boolean left = isLeftSide();
        int x = snappedX(left);
        int y = Prefs.clamp(floatLp.y, 0, Math.max(0, p.y - statusBarInset() - size));
        animateFloatTo(x, y, safeAlpha(Prefs.floatAlpha() / 100f), 220);
        Prefs.setFloatXY(x, y);
        scheduleTuck();
    }

    /**
     * 闲置一段时间后「收起」：缩到 62% 并变淡，但仍然完整留在屏幕内。
     *
     * 这里踩过一个坑：一开始的做法是把球滑出屏幕、只留 42% 在边上，
     * 结果国产 ROM 的屏幕边缘 84px 是系统手势拦截区（logcat 里
     * InputDispatcherExtImpl 的 interception region），留在边上的那一小条
     * 正好落在拦截区里，点它会被系统吃掉，球根本收不到事件。
     * 203px 的球既想藏起来又想还能点，是不可能同时成立的，所以改成原地缩。
     * 窗口本身仍然保持 203x203，触摸范围不缩水。
     */
    private void tuckToEdge() {
        if (floatView == null || tucked) return;
        // 连答跑着的时候一律不缩。scheduleTuck 那边已经拦了一道，这里再拦一道：
        // 球上那个定时器可能在「按下开始」之前就排好了，等它到点时 running 早就是
        // true 了（就是这么在运行中缩掉的）。
        if (running) return;
        // 正在识别 / 正在自动点击时不要藏，否则用户看不到进度
        if (busy) {
            scheduleTuck();
            return;
        }
        if (bubble != null && bubble.getVisibility() == View.VISIBLE) {
            scheduleTuck();
            return;
        }
        tucked = true;
        Point p = screenSize();
        int size = floatSize();
        int x = snappedX(isLeftSide());
        int y = Prefs.clamp(floatLp.y, 0, Math.max(0, p.y - statusBarInset() - size));
        animateFloatTo(x, y, safeAlpha(Prefs.floatAlpha() / 100f), 260);
        scaleFloatBall(IDLE_SCALE);
        fadeFloatLabel(false);
        Prefs.setFloatXY(x, y);
    }

    private void untuck() {
        if (!tucked) return;
        tucked = false;
        fadeFloatLabel(true);
        scaleFloatBall(1f);
        int x = snappedX(isLeftSide());
        animateFloatTo(x, floatLp.y, safeAlpha(Prefs.floatAlpha() / 100f), 200);
        Prefs.setFloatXY(x, floatLp.y);
        scheduleTuck();
    }

    private void scheduleTuck() {
        ui.removeCallbacks(tuckTask);
        // 连答跑着的时候**不缩球**：球缩成边上一个小点之后，「还在跑 / 停了 / 出错了」
        // 三色就全看不见了，用户没法一眼知道该不该管它。要缩也是停了之后再说。
        if (running) return;
        int sec = Prefs.autoHideSec();
        if (sec > 0) ui.postDelayed(tuckTask, sec * 1000L);
    }

    // ---------------- 悬浮球配色 ----------------

    /** 运行中：蓝。 */
    public static final int BALL_RUN = 0;
    /** 已暂停：灰。 */
    public static final int BALL_PAUSED = 1;
    /** 出错（取词失败 / 大模型失败 / 解析不出）：红。 */
    public static final int BALL_ERROR = 2;
    /** 待启动：绿。点一下才开始答（见 {@link #arm}）。 */
    public static final int BALL_ARMED = 3;

    private int ballState = -1;

    /**
     * 按状态给球上色：正在答蓝、待启动绿、暂停灰、出错红。
     * <p>
     * 用代码现搭渐变而不是给 drawable 上 tint：tint 是 {@code SRC_IN}，
     * 会把水晶球的高光和内外反光边一起染成同一个色，球就成了一块死板的色块。
     */
    private void paintBall(final int state) {
        ui.post(() -> {
            if (floatBall == null) return;
            if (state == ballState) return;     // 状态没变就别重建 drawable
            ballState = state;
            int top, bottom;
            switch (state) {
                case BALL_PAUSED:
                    top = 0xB3B0BEC5; bottom = 0x8C546E7A;
                    break;
                case BALL_ERROR:
                    top = 0xB3FF5A5A; bottom = 0x8CB71C1C;
                    break;
                case BALL_ARMED:
                    top = 0xB334C759; bottom = 0x8C1B6E33;
                    break;
                case BALL_RUN:
                default:
                    top = 0xB33D8BFF; bottom = 0x8C0F4FB8;
                    break;
            }
            floatBall.setBackground(ballBackground(top, bottom));
        });
    }

    /** 一颗水晶球：底层上下渐变的实心圆 + 上层左上角白色反光。 */
    private Drawable ballBackground(int topColor, int bottomColor) {
        GradientDrawable body = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, new int[]{bottomColor, topColor});
        body.setShape(GradientDrawable.OVAL);

        GradientDrawable gloss = new GradientDrawable();
        gloss.setShape(GradientDrawable.OVAL);
        gloss.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gloss.setGradientRadius(dp(30));
        gloss.setGradientCenter(0.34f, 0.24f);
        gloss.setColors(new int[]{0x99FFFFFF, 0x33FFFFFF, 0x00FFFFFF});

        LayerDrawable ld = new LayerDrawable(new Drawable[]{body, gloss});
        return ld;
    }

    private void setFloatLabel(final String s) {
        ui.post(() -> {
            if (floatLabel != null) floatLabel.setText(s);
        });
    }

    // ---------------- 无障碍引导卡 ----------------

    /**
     * 无障碍被系统关掉时，在屏幕上摆一张卡片把用户领到设置里去。
     * <p>
     * 为什么非得是「压在屏幕上的卡」：这台机器上通知权限是拒的、语音是关的（见 README），
     * toast 也就两秒 —— 用户此刻正盯着学习通，一抬头什么提示都看不见，感受就是「又卡了」。
     * 卡片一直挂着，上面那个按钮一点直接进无障碍页；他真去把开关打开了，
     * {@link #onA11yConnected()} 会把卡收掉、把球放回绿的「待启动」。
     */
    private void showA11yGuide() {
        ui.post(() -> {
            try {
                if (guideView == null) {
                    guideView = LayoutInflater.from(this).inflate(R.layout.view_a11y_guide, null);
                    guideLp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : WindowManager.LayoutParams.TYPE_PHONE,
                            // NOT_TOUCH_MODAL：卡片以外的点击照旧落到学习通上，
                            // 别为了一张提示卡把整屏的触摸都吃掉。
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    // 摆在上三分之一：不压住页面下半边的选项和「提交」，也不跟右侧的球打架。
                    guideLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    guideLp.y = dp(72);
                    applyCrystalBlur(guideLp);
                    guideView.findViewById(R.id.btnGuideGo)
                            .setOnClickListener(v -> openA11ySettings());
                    guideView.findViewById(R.id.btnGuideOk)
                            .setOnClickListener(v -> hideA11yGuide());
                    wm.addView(guideView, guideLp);
                    logAsk(stamp() + " 无障碍没打开：屏幕上摆了「去打开设置」的引导卡");
                }
                guideView.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                Log.w(TAG, "showA11yGuide", t);
            }
        });
    }

    private void hideA11yGuide() {
        ui.post(() -> {
            if (guideView == null) return;
            try {
                wm.removeViewImmediate(guideView);
            } catch (Throwable ignored) {
            }
            guideView = null;
        });
    }

    /** 一键进无障碍设置：先试本应用的详情页（点进去就是那个开关），ColorOS 上没有就退回列表页。 */
    private void openA11ySettings() {
        try {
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("打不开设置，手动走：设置 → 无障碍 → 已下载的应用 → 答题助手");
        }
    }

    /** 无障碍服务重新连上了（{@link QuizAccessibilityService#onServiceConnected} 调的）。 */
    static void onA11yConnected() {
        FloatingService s = inst;
        if (s != null) s.ui.post(s::a11yCameBack);
    }

    /**
     * 引导卡还挂着就说明它正是为用户摆的 —— 现在可以撤了。
     * <p>
     * 顺手把球从红的「出错了」放回绿的「待启动」：用户刚在设置页里点完开关，
     * 下一步就是回题目上点球；红球会让他以为还得去哪儿按一下「恢复」。
     */
    private void a11yCameBack() {
        if (guideView == null) return;      // 没为这事摆过卡，就别多嘴打扰
        hideA11yGuide();
        cancelA11yNotice();
        logAsk(stamp() + " 无障碍已打开，引导卡收起（球已回到待启动，回题目上点球接着答）");
        if (running && paused) arm();       // arm 里会 toast 一句「点悬浮球才开始答题」
        else toast("无障碍已打开：回到题目那一屏，点一下悬浮球接着答");
    }

    // ---------------- 暂停 / 恢复 ----------------

    /**
     * 暂停回答：掐掉自动连答排着的活，球上的字变成「停」。
     * <p>
     * 只掐标志是不够的 —— 排着队的那几个 runnable 得撤掉，否则它们醒过来还会读一次屏、
     * 白问一次大模型（用户切到别的 App 之后被读到过聊天记录，就是这么来的）。
     */
    private void pause(String why) {
        if (paused && !armed) return;       // 已经停着了（绿球也算「没在答」，见下）
        boolean wasArmed = armed;
        armed = false;
        paused = true;
        cancelNextClick();
        ui.removeCallbacks(pendingLoop);
        ui.removeCallbacks(loopAsk);
        setFloatLabel("停");
        paintBall(BALL_PAUSED);
        toast(wasArmed ? "已取消待启动，点悬浮球重新来" : "已暂停回答，点悬浮球继续");
        logAsk(stamp() + (wasArmed ? " 已取消待启动：" : " 已暂停回答：") + why);
        refreshServiceNotification();
    }

    /**
     * 回到绿球「待启动」：起来了但还没开答，点一下才真开跑。
     * <p>
     * 为什么要多这一下：启动悬浮球那一下，人还在助手自己的界面上（或刚切出录屏授权页、
     * 设置页），要是直接起跑，连答会先对着那一屏白问一次大模型，再照着答案点一遍 ——
     * 点到的全是无关界面。绿球就是「我准备好了」：用户切到题目上再点它，中间不会读错屏。
     */
    private void arm() {
        if (!running) return;
        boolean was = armed;
        armed = true;
        paused = true;                       // 「待启动」也是「没在答」
        cancelNextClick();
        ui.removeCallbacks(pendingLoop);
        ui.removeCallbacks(loopAsk);
        setFloatLabel("待");
        paintBall(BALL_ARMED);
        refreshServiceNotification();
        if (!was) {
            toast("待启动：切到题目上，点悬浮球才开始答题");
            logAsk(stamp() + " 待启动：点悬浮球才开始答题（长按=停，再点=开）");
        }
    }

    /** 从「待启动」退回普通暂停，并保持绿球 —— 遥控口 ASK 用（明说「现在就答」）。 */
    private void disarm() {
        if (!armed) return;
        armed = false;
        paused = false;
    }

    /** 短按悬浮球：绿→开答、灰/红（停了）→先回绿、蓝（正在答）→重读当前屏。 */
    private void tapBall() {
        if (armed) {
            resume("点悬浮球");
            ask();                  // 点完立刻读这一屏，不用等连答那一拍
        } else if (paused) {
            arm();                  // 停了之后想接着答：先绿一下，再点才开
        } else {
            ask();
        }
    }

    private void resume(String why) {
        if (!paused) return;
        paused = false;
        armed = false;
        setFloatLabel("答");
        paintBall(BALL_RUN);
        toast("开始答题");
        logAsk(stamp() + " 已开始答题：" + why);
        // 停下（halt）时「这屏一直没翻」的计数已经攒满；继续时不清掉，第一拍就会
        // 拿旧计数再停一次 —— 用户看着就是「点继续没反应」。当作新的一屏重新读。
        lastScreenKey = "";
        sameScreenTicks = 0;
        blankKeyTicks = 0;
        scrollTries = 0;
        optScrollTries = 0;
        reaskTries = 0;
        cancelNextClick();
        refreshServiceNotification();
        // pause 那一下把排队的 runnable 全撤了（省得它们醒过来白读一次屏），
        // 所以这里必须**重新排**一次循环 —— 只把 paused 翻回 false 是不够的：
        // 队列里已经什么都没有了，连答就再也不会自己动（adb 的 PAUSE/RESUME
        // 和「长按暂停之后点球继续」都踩过这个坑）。
        if (Prefs.autoLoop()) scheduleLoop();
    }

    /** 长按悬浮球（待启动 / 已暂停 / 出错时）：回到本软件主界面。 */
    private void openMain() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            logAsk(stamp() + " 长按悬浮球：回到主界面（球保持原来的状态）");
        } catch (Throwable t) {
            Log.w(TAG, "openMain", t);
            toast("打不开主界面：" + t);
        }
    }

    /** 收尾时把球上的字恢复成「答」—— 待启动/暂停着就保持「待」/「停」。 */
    private void restoreLabel() {
        setFloatLabel(armed ? "待" : (paused ? "停" : "答"));
    }

    private void refreshServiceNotification() {
        if (!foregroundShown) return;            // 通知已经摘掉了（用户关了常驻），别又挂回去
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && running) nm.notify(NOTIF_SERVICE, buildServiceNotification());
        } catch (Throwable ignored) {
        }
    }

    /** 填空题正文写进剪贴板。静态的，遥控口（DumpReceiver）也要用同一条路。 */
    public static void copyToClipboard(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("答题助手", text));
        } catch (Throwable t) {
            Log.w(TAG, "剪贴板写入失败", t);
        }
    }

    private void showBubble(String answer, String exp) {
        if (!Prefs.bubble()) return;
        ui.post(() -> {
            try {
                if (bubble == null) {
                    bubble = LayoutInflater.from(this).inflate(R.layout.view_answer_bubble, null);
                    bubbleLp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    applyCrystalBlur(bubbleLp);
                    applyBubblePos(bubbleLp);
                    wm.addView(bubble, bubbleLp);
                }
                ((TextView) bubble.findViewById(R.id.tvBubbleAnswer)).setText("答案：" + answer);
                ((TextView) bubble.findViewById(R.id.tvBubbleExp)).setText(exp);
                bubble.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                Log.w(TAG, "showBubble", t);
            }
        });
        ui.postDelayed(() -> {
            if (bubble != null) bubble.setVisibility(View.GONE);
        }, Math.max(1, Prefs.bubbleSec()) * 1000L);
    }

    /**
     * 摆放解析气泡。位置由设置里的「解析气泡位置」决定：
     * 0 左上 / 1 右上 / 2 左下 / 3 右下 / 4 中间 / 5 自定义（X%、Y%）。
     *
     * 自定义时 X/Y 是屏幕宽高的百分比，按「左边缘/上边缘」对齐，并且会被夹住，
     * 免得整块飘到屏幕外面去。
     *
     * 坑：overlay 窗口的 y 是相对「状态栏以下」那个父框架的（见 README 里悬浮球那条），
     * 所以按屏幕绝对坐标算出来的 y 要减掉一个状态栏高度。
     */
    private void applyBubblePos(WindowManager.LayoutParams lp) {
        Point p = screenSize();
        int pad = dp(12);
        int sb = statusBarInset();
        lp.gravity = Gravity.TOP | Gravity.START;
        int x = pad, y = pad;
        switch (Prefs.bubblePos()) {
            case 1:                                   // 右上
                lp.gravity = Gravity.TOP | Gravity.END;
                break;
            case 2:                                   // 左下
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                break;
            case 3:                                   // 右下
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                break;
            case 4:                                   // 中间
                lp.gravity = Gravity.CENTER;
                x = 0;
                y = 0;
                break;
            case 5:                                   // 自定义百分比
                x = p.x * Prefs.bubbleX() / 100;
                y = p.y * Prefs.bubbleY() / 100 - sb;
                x = Prefs.clamp(x, 0, Math.max(0, (int) (p.x * 0.7f)));
                y = Prefs.clamp(y, 0, Math.max(0, (int) (p.y * 0.85f) - sb));
                break;
            default:                                  // 左上
                break;
        }
        lp.x = x;
        lp.y = y;
    }

    /** 截图期间把自己的悬浮窗藏起来，免得把悬浮球也拍进去。 */
    private void setOverlayVisible(final boolean v) {
        if (!Prefs.hideOverlayOnShot()) return;
        final boolean show = v;
        ui.post(() -> {
            if (floatView != null) floatView.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show && bubble != null) bubble.setVisibility(View.GONE);
            // 引导卡也是覆盖窗口，不撤掉就会被拍进图里、被 OCR 当成题目正文。
            if (guideView != null) guideView.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        if (!show) sleep(240);
    }

    private void releaseUi() {
        ui.removeCallbacks(tuckTask);
        cancelFloatAnimator();
        ui.post(() -> {
            try {
                if (floatView != null) wm.removeViewImmediate(floatView);
            } catch (Throwable ignored) {
            }
            try {
                if (bubble != null) wm.removeViewImmediate(bubble);
            } catch (Throwable ignored) {
            }
            try {
                if (guideView != null) wm.removeViewImmediate(guideView);
            } catch (Throwable ignored) {
            }
            floatView = null;
            bubble = null;
            guideView = null;
        });
    }

    // ---------------- 主流程 ----------------

    private void ask() {
        if (paused) {
            // 没在答的时候不回答。点球那一下会先 resume 再进来，所以走到这儿的多半是
            // 遥控口 ASK 之类的外部触发 —— 提示一句，别让人以为程序死了。
            toast(armed ? "待启动：点悬浮球开始答题" : "已暂停回答，点悬浮球继续");
            return;
        }
        // 无障碍服务没连上：读屏、点选项、找「下一题」**全是废的**（点选项走的是
        // dispatchGesture，也在这个服务身上）。与其让它跑到中途空手回来 —— 用户只看到
        // 「点了没反应」—— 不如当场把「去打开设置」那张卡摆出来。
        if (QuizAccessibilityService.get() == null) {
            if (tryHealA11y()) {
                ui.postDelayed(this::ask, 2000);   // 设置写进去了，服务要一两秒才绑上来
                return;
            }
            showA11yGuide();
            toast("无障碍服务没打开，点屏幕上那张卡里的「去打开设置」");
            return;
        }
        // 系统弹窗 / 展开的通知栏 / 别的应用浮窗压在上面时先别动：读屏读到的可能是
        // 弹窗里的字，点坐标也会点在弹窗上（选项那行反而没点到）。等它收回去，
        // 最多等 5 次（约 4 秒）—— 等不到就照常答，免得被一直没收回去的窗口卡死。
        QuizAccessibilityService svcB = QuizAccessibilityService.get();
        if (svcB != null && svcB.overlayWindowUp() && bannerWaits < 5) {
            bannerWaits++;
            logAsk(stamp() + " 屏幕上有弹窗/展开的通知栏压着，先不读也不点（第 " + bannerWaits + " 次）");
            ui.postDelayed(this::ask, 800);
            return;
        }
        bannerWaits = 0;

        if (busy) {
            toast("还在处理上一条…");
            scheduleLoop();              // 自动连答时别因为这一下就断了
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastAskAt < 700) {
            scheduleLoop();              // 同上：被限流跳过也要把循环接回去
            return;
        }
        lastAskAt = now;

        busy = true;
        setFloatLabel("…");
        cancelNextClick();
        ui.removeCallbacks(pendingLoop);
        ui.removeCallbacks(tuckTask);
        worker.execute(this::doAsk);
    }

    /**
     * 一次「读屏 → 问大模型」的结果。
     * <p>
     * 单独拎出来是因为这里要跑**两遍**：第一遍按设置里的文本来源来，读不到字 /
     * 大模型给了「？」时，按用户开的兜底开关换另一条路再来一遍。
     */
    private static final class Reading {
        String text = "";
        String source = "";
        LlmClient.Reply reply;
        boolean vision;
        boolean shotBlocked;
        /** 这一遍为什么没成（取词失败 / 请求失败），给日志用。 */
        String fail = "";
        /** 大模型请求本身报错（超时、断网、401）。这种换哪条路都一样，不必兜底重试。 */
        boolean requestFailed;
        /** 这一遍压根没读：前台是本应用自己的设置页/日志页（读到的会是日志正文）。 */
        boolean ownApp;
        /** 这张截图是哪条路给的（Shizuku / 屏幕录制 / 无障碍截屏），日志里带着，好定位。 */
        String shotFrom = "";

        boolean hasReply() {
            return vision || (reply != null && reply.ok);
        }

        AnswerParser.Parsed parsed() {
            if (!hasReply()) return null;
            return AnswerParser.parse(reply == null ? "" : reply.content, Prefs.labels(), text);
        }
    }

    /** 解析出来的答案能不能用。 */
    private static boolean usable(AnswerParser.Parsed p) {
        if (p == null) return false;
        // 一屏多题时别只看第一条：最上面那道题常常「选项没显示全」被模型填了 "?"，
        // 后面几条其实答得好好的。只看第一条的话，这一屏会被判成「没答出来」，
        // 白白换另一条取词路重读一遍（截图 OCR 很贵），还可能在重读时把页面重答一遍。
        for (AnswerParser.Parsed it : p.items) {
            if (it.answer != null && !it.answer.isEmpty() && !"?".equals(it.answer)) return true;
            // 填空题没有字母答案，正文就是答案 —— 只看 answer 的话，纯填空页会被判成
            // 「视觉这条没答出来」，白白再读一遍（WebView 那边还常常读不到字）。
            if (!it.fill.isEmpty() || !it.blanks.isEmpty()) return true;
        }
        return (!p.answer.isEmpty() && !"?".equals(p.answer))
                || !p.fill.isEmpty() || !p.blanks.isEmpty();
    }

    /** 文本来源的名字，日志里说人话用。 */
    private static String srcName(int mode) {
        switch (mode) {
            case 1: return "无障碍文本";
            case 2: return "截图OCR";
            case 3: return "视觉大模型";
            case 4: return "无障碍文本";
            default: return "自动";
        }
    }

    /**
     * 这条路答不出来时该换哪条路，返回 -1 表示不换。
     * <p>
     * 只有当「这一遍读到的字没给出答案」时才换 —— 大模型请求本身失败（超时/断网/401）
     * 换条路读到的是同一块屏幕，换过去照样失败，白白多花一次请求的时间。
     */
    private int altMode(Reading rd, int mode) {
        if (rd.requestFailed) return -1;
        // 前台是本应用自己的界面：换哪条路、读几次都是同一块屏幕（截图也是自己的设置页），
        // 别白拍一张截图，更别让日志说成「改走截图 OCR 重读一遍」。
        if (rd.ownApp) return -1;
        boolean fromA11y = "无障碍文本".equals(rd.source) || "uiautomator".equals(rd.source);
        boolean fromOcr = "本地OCR".equals(rd.source);
        // 一个字都没读到的：按「设置里的另一条路」补
        if (rd.text.isEmpty() && !rd.vision) {
            if (mode == 1) return Prefs.fallbackOcr() ? 2 : -1;      // 只走无障碍 → 退 OCR
            if (mode == 2) return Prefs.fallbackA11y() ? 1 : -1;     // 只走 OCR → 退无障碍
            return -1;
        }
        if (fromA11y && Prefs.fallbackOcr() && mode != 2) return 2;
        if (fromOcr && Prefs.fallbackA11y() && mode != 1) {
            // 退到无障碍**只有在那边真读得到选项正文时**才有意义。学习通随堂练习的
            // 无障碍树里只有光杆标签（正文那截 DOM 没暴露），退回去读的是同一份答不了
            // 题的文字 —— 白花一次请求，还把这轮时间拖长一倍。
            QuizAccessibilityService s = QuizAccessibilityService.get();
            if (s == null || !s.optionsUnreadable()) return 1;
        }
        return -1;
    }

    /**
     * 按 {@code mode} 指定的来源读一次屏，读到字就问一次大模型。
     * mood 见 {@link Prefs#ocrSource()}：0 自动 / 1 仅无障碍 / 2 仅本地OCR / 3 仅视觉。
     */
    private Reading readAndAsk(LogRecord rec, int mode) {
        Reading rd = new Reading();

        // 0) 前台是本应用**自己的界面**（设置页、问答日志页）时，一个字都不读。
        //    那上面一行行「A. 3 / B. 4 ✓」是**日志正文**，不是屏幕上的选项。实测大模型
        //    照着日志把答案又答了一遍，字母点下去却落在前台那个真正的题目 App 上
        //    —— 一屏点出一片乱选（用户报的「读的是日志、点的是别的题」）。
        //    认窗口只信 activeAppPackage()（实时问窗口列表）：foregroundPkg 那个事件变量
        //    会被我们自己弹的广播/界面带偏，停在**本应用**的包名上（见它的注释）。
        //    自己的悬浮球/气泡不算 —— 它们是 TYPE_SYSTEM 窗口，这里只看活动的应用窗口。
        String own = QuizAccessibilityService.activeAppPackage();
        if (!own.isEmpty() && own.equals(getPackageName())) {
            rd.ownApp = true;
            rd.fail = "前台是本应用自己的界面（设置/日志），不是题目页面";
            return rd;
        }

        // 0.5) 视觉优先（取词来源 = 4，默认）：整张截图直接交给多模态模型，连读带答一次做完 ——
        //      题干和选项是它一眼看出来的，不用先抠成文字再拼回去，WebView 那套
        //      「光杆选项 / 整行漏字 / 懒加载」的毛病全绕开了。
        //      这一步读不到（图是黑的、拿不到截图、模型没从图里认出题）就往下走文本那条链子，
        //      也就是「无法读取则改无障碍」。
        final String visFail, visShotFrom;
        final boolean visBlocked, visReqFailed, visHasReply;
        final LlmClient.Reply visReply;
        if (mode == 4) {
            visionPass(rd);
            if (rd.vision && usable(rd.parsed())) return rd;
            logAsk(stamp() + " 视觉这条"
                    + (rd.fail.isEmpty() ? "给了答复但用不了" : clip(rd.fail, 200))
                    + "，改走无障碍文本接着读");
            // 这一遍的结论先收着：文本那条要是也没读出东西，报它说得更具体（见本方法末尾）。
            visFail = rd.fail;
            visShotFrom = rd.shotFrom;
            visBlocked = rd.shotBlocked;
            visReqFailed = rd.requestFailed;
            visHasReply = rd.reply != null && rd.reply.ok;
            visReply = rd.reply;
            rd.fail = "";
            rd.shotFrom = "";
            rd.shotBlocked = false;
            rd.requestFailed = false;
            rd.reply = null;
            rd.vision = false;
            rd.source = "";
        } else {
            visFail = "";
            visShotFrom = "";
            visBlocked = false;
            visReqFailed = false;
            visHasReply = false;
            visReply = null;
        }

        // 1) 无障碍文本（最快，零成本）
        boolean a11yBlind = false;
        if (mode == 0 || mode == 1 || mode == 4) {
            QuizAccessibilityService svc = QuizAccessibilityService.get();
            if (svc != null) rd.text = svc.dumpText();
            // 刚滑过一屏、或者窗口正在换的时候，Chromium 的无障碍树会短暂地读不出东西：
            // 实测「往下滑一屏」的下一拍读到的就是空的，隔半秒再读又好好的。这不是页面上
            // 没题，可白等 30 轮才重问一次太亏（选择题的整屏进度就停在那儿），自己重读两下。
            for (int i = 0; i < 2 && svc != null && rd.text.isEmpty(); i++) {
                sleep(400);
                rd.text = svc.dumpText();
            }
            // 学习通随堂练习这类页面：题干和 A B C D E 都读得到，选项正文一个字没有
            // —— 那截 DOM 压根没进无障碍树（uiautomator 同样读不到，验过）。文字非空、
            // 却根本答不了题，所以这里主动判一次「光杆选项」，当成没读到，
            // 让下面退到截图 OCR / 视觉大模型：屏幕上那行字是实打实画着的，截得下来。
            if (svc != null && !rd.text.isEmpty() && svc.optionsUnreadable()) {
                Log.i(TAG, "无障碍只读得到光杆选项（题干/标签在、正文不在），改走截图");
                a11yBlind = true;
                rd.text = "";
            }
            if (!rd.text.isEmpty()) rd.source = "无障碍文本";
        }

        // 1b) Shizuku uiautomator 兜底。a11yBlind 时跳过 —— uiautomator 读的是同一棵
        //     无障碍树，上面读不到正文，它照样读不到，别在这儿白等一轮。
        if (!a11yBlind && rd.text.isEmpty() && (mode == 0 || mode == 1 || mode == 4)
                && ShizukuShell.granted()) {
            List<UiDump.Node> nodes = dumpNodes();
            if (!nodes.isEmpty()) {
                rd.text = UiDump.textOf(nodes);
                if (!rd.text.isEmpty()) rd.source = "uiautomator";
            }
        }

        // 2) 截图 + 本地 OCR
        Bitmap shot = null;
        if (rd.text.isEmpty() && (mode == 0 || mode == 2 || mode == 4)) {
            shot = screenshot();
            if (shot != null) {
                rd.shotFrom = shotSrcName;
                rd.shotBlocked = looksBlocked(shot);
                try {
                    OcrEngine.Result r = OcrEngine.recognize(shot, Prefs.cropTop(), Prefs.cropBottom());
                    lastLines = r.lines;
                    // 上一屏的对齐几何作废：留着它，点击会按旧坐标落到新一屏上
                    alignedRows = new ArrayList<>();
                    rd.text = r.text;
                    // 「无障碍只读得到光杆选项」时正文只在截图上、标签只在无障碍里，按行
                    // 位置合一份标签准确的题干出来（见 alignOcrWithRows）。
                    // 判据要看**当下这棵树**，不能看上面的 a11yBlind —— 那个只在 mode 走过
                    // 无障碍时才置位，用户把「取词来源」设成「仅本地OCR」时它永远是 false，
                    // 对齐就白算了（实测踩过）。
                    boolean needAlign = a11yBlind;
                    if (!needAlign) {
                        QuizAccessibilityService s2 = QuizAccessibilityService.get();
                        if (s2 != null) {
                            try { needAlign = s2.optionsUnreadable(); } catch (Throwable ignored) {}
                        }
                    }
                    if (needAlign) {
                        String aligned = alignOcrWithRows(r.lines);
                        if (!aligned.isEmpty()) {
                            rd.text = aligned;
                            logAsk(stamp() + " 无障碍只有光杆标签，改成按行对齐的版本："
                                    + clip(aligned.replace("\n", " | "), 300));
                        }
                    }
                    if (!rd.text.isEmpty()) rd.source = "本地OCR";
                } catch (Throwable t) {
                    rec.error = "OCR 失败：" + t.getMessage();
                }
            } else {
                rec.error = "拿不到截图（无障碍服务没连、屏幕录制也没授权）";
            }
        }

        // 3) 视觉大模型：截图直接交给多模态模型
        if (rd.text.isEmpty() && (mode == 0 || mode == 3) && Prefs.hasVision()) {
            if (shot == null) {
                shot = screenshot();
                if (shot != null) {
                    rd.shotFrom = shotSrcName;
                    rd.shotBlocked = looksBlocked(shot);
                }
            }
            // 拍出来是纯色就别浪费一次多模态请求了
            if (shot != null && !rd.shotBlocked) {
                rd.reply = askWithRetry(shot, null);
                if (rd.reply.ok) {
                    rd.vision = true;
                    rd.source = "视觉大模型";
                } else {
                    rd.requestFailed = true;
                    rd.fail = "视觉大模型请求失败：" + rd.reply.error;
                }
            }
        }

        if (shot != null && !shot.isRecycled()) shot.recycle();

        if (rd.vision) return rd;

        if (rd.text.isEmpty()) {
            if (visHasReply) {
                // 视觉那条**给了答复**，只是用不了（多半是「选项没显示全」，模型规规矩矩填了
                // "?"）。留着它交上去：日志里能看到模型怎么解的，比一句「屏幕上没读到文字」
                // 有用得多 —— 文本这条路本来就是一个字都没抠出来才走到这儿。
                rd.reply = visReply;
                rd.vision = true;
                rd.source = "视觉大模型";
                rd.fail = visFail;
                return rd;
            }
            if (!visFail.isEmpty()) {
                // 视觉那条说得更具体（拿不到截图 / 图是黑的 / 模型不认图片）。两条路都空手时
                // 报它：报「没读到文字」用户会去调取词方式，可问题其实在截图上。
                rd.fail = visFail;
                rd.shotBlocked = visBlocked;
                rd.shotFrom = visShotFrom;
                rd.requestFailed = visReqFailed;
                if (visReqFailed) rd.reply = visReply;   // doAsk 里 halt 的提示要看 reply.error
                return rd;
            }
            rd.fail = "屏幕上没读到文字" + (rec.error.isEmpty() ? "" : "：" + rec.error);
            return rd;
        }
        rd.reply = askWithRetry(null, rd.text);
        if (!rd.reply.ok) {
            rd.requestFailed = true;
            rd.fail = "大模型请求失败：" + rd.reply.error;
        }
        return rd;
    }

    /**
     * 「视觉优先」的第一步：整张截图直接发给多模态模型，让它连读题带作答一次做完。
     * <p>
     * 结论全写进 {@code rd}：成了是 {@code rd.vision=true}，没成是 {@code rd.fail} 里一句
     * 说人话的原因（没配 key / 拿不到截图 / 图是黑的 / 模型不认图片），由调用方决定
     * 要不要退到无障碍那条路。
     */
    private void visionPass(Reading rd) {
        if (visionDead) {
            // 已经试过、确认不通了：这一轮**连图都不截**，直接说一句就退无障碍 ——
            // 连答时每两秒一轮，不拦着就是每轮白拍一张图、白发一次请求。
            rd.fail = "这次运行不再试它"
                    + (visionDeadWhy.isEmpty() ? "" : "（" + visionDeadWhy + "）");
            return;
        }
        if (!Prefs.hasVision()) {
            rd.fail = "没配视觉大模型，主模型那栏也没填 key";
            return;
        }
        Bitmap shot = screenshot();
        if (shot == null) {
            rd.fail = "拿不到截图（无障碍没连、屏幕录制也没授权）";
            return;
        }
        try {
            rd.shotFrom = shotSrcName;
            rd.shotBlocked = looksBlocked(shot);
            if (rd.shotBlocked) {
                // 全黑就别浪费一次多模态请求了：这个 App 设了 FLAG_SECURE，截图整条路堵死，
                // 直接退无障碍文本（读控件树不走截图，不受这个标志影响）。
                rd.fail = "截图整块是黑的（这个 App 设了 FLAG_SECURE 禁止截屏）";
                return;
            }
            rd.reply = askWithRetry(shot, null);
            if (rd.reply.ok) {
                rd.vision = true;
                rd.source = "视觉大模型";
                return;
            }
            rd.requestFailed = rd.reply.retryable;
            rd.fail = "请求失败：" + rd.reply.error;
            if (!rd.reply.retryable) {
                // 「模型不认图片」「模型名写错」「地址填错」——再发一百次也是同一个结果。
                // 记住它，这次运行剩下的几轮别再白拍图、白发请求（连答时每两秒就一轮）。
                visionDead = true;
                visionDeadWhy = clip(rd.reply.error, 100);
                logAsk(stamp() + " 视觉这条路走不通（" + visionDeadWhy
                        + "），这次运行不再试它，全走无障碍文本");
            }
        } finally {
            if (!shot.isRecycled()) shot.recycle();
        }
    }

    /** 大模型一次不成最多再重试几次（用户要的「超时自动重试三次」）。 */
    private static final int LLM_RETRIES = 3;

    /**
     * 问一次大模型，**失败就重试**，最多 {@link #LLM_RETRIES} 次。
     * <p>
     * 以前这里问一次就完事：网络抖一下、服务端 20 秒没吐字，这一屏就直接判死，
     * 页面还停在第 8 题干等（实测就是这么停住的）。超时和断网恰恰是「同一份请求再发
     * 一次多半就通」的那类故障，白扔一题太亏。
     * <p>
     * 只重试 {@code retryable} 的失败 —— 没填 Key、401、模型名写错这种再发也没用，
     * 重试只是把等待时间乘三。
     * <p>
     * 重试之间要检查 {@code running}/{@code paused}：用户点了暂停（或者连答已经停下）
     * 就得当场收手，不能睡在那儿把一次请求拖成半分多钟。
     *
     * @param shot 非空走视觉大模型，null 走文本
     */
    private LlmClient.Reply askWithRetry(Bitmap shot, String text) {
        LlmClient.Reply r = null;
        for (int i = 0; i <= LLM_RETRIES; i++) {
            r = shot != null ? LlmClient.askVision(shot) : LlmClient.ask(text == null ? "" : text);
            if (r.ok || !r.retryable) return r;
            if (i == LLM_RETRIES) break;
            if (!running || paused) return r;
            long wait = 1500L << Math.min(i, 3);      // 1.5s / 3s / 6s
            logAsk(stamp() + " 大模型第 " + (i + 1) + " 次失败：" + r.error
                    + "；" + (wait / 1000) + " 秒后重试（还有 " + (LLM_RETRIES - i) + " 次）");
            SystemClock.sleep(wait);
            if (!running || paused) return r;
        }
        return r;
    }

    /** 对齐时用的一个选项锚点：标签、纵向中心、这一行自己已有的文字。 */
    private static final class OptAnchor {
        final char label;
        final int y;
        final int group;
        final String text;
        /** 实认出来的话，这是那一行（或那枚标签）在屏幕上的矩形；断号补出来的没有。 */
        final Rect rect;
        /** 这枚标签是「断号补出来的」，页面上没实读到 —— 只为了把选项表补连续。 */
        boolean guessed;
        OptAnchor(char label, int y, int group, String text, Rect rect) {
            this.label = label;
            this.y = y;
            this.group = group;
            this.text = text == null ? "" : text;
            this.rect = rect;
        }
    }

    /**
     * 「按行对齐」时记下来的选项落点。
     * <p>
     * 无障碍会把整行漏掉（实测学习通第 10 题的 A 和 C 压根不在无障碍树里），这时候
     * {@code boundsOfOption} 是空的，OCR 那枚小字母也可能正好没认出来 —— 就只剩这份几何
     * 能告诉程序「C 这一行在屏幕的哪个位置」。没有它，答案里的 C 会被**悄悄跳过**：
     * 日志写着「答案=ACD」，屏幕上只选中了 A 和 D，用户看到的就是「少选一个」。
     */
    private Rect alignedBounds(String label, int group) {
        if (label == null || label.isEmpty()) return null;
        char want = Character.toUpperCase(label.charAt(0));
        Rect hit = null;
        for (QuizAccessibilityService.OptRow r : alignedRows) {
            if (r.label != want || r.rect == null) continue;
            if (group >= 0 && r.group != group) continue;
            if (group >= 0) return r.rect;
            if (hit == null) hit = r.rect;      // 全屏找：取第一个同名的
        }
        return hit;
    }

    /**
     * 把截图 OCR 认出来的文字，按**行的纵向位置**塞回无障碍认出来的那一列选项里。
     * <p>
     * 学习通这类页面上两边各缺一半：无障碍知道「A 在哪、B 在哪」却读不到正文，
     * 截图 OCR 读得到正文却常常把「A」「B」「C」这些圈里的小标签漏认、错认。更麻烦的是
     * **两家漏的还不是同一批**：实测第 10 题无障碍整行漏了 A 和 C（Chromium 只把 B、D、E
     * 放进了无障碍树），而同一次 OCR 又把 C 那枚小字母认丢了 —— 只信一家，拼出来的选项表
     * 就缺一条，模型回「标签 A、B、D、E 不连续，无法确认」，这题永远答不出来。
     * <p>
     * 所以这里把两家的**标签**并起来当锚点，再加一步：锚点之间「B…D」这种断号说明中间漏了
     * 选项，按行距把缺的那枚标签补回去 —— 选项在页面上就是自上而下顺序、等距排着的。
     *
     * @return 对齐好的题干（第一行选项上面的文字照旧放最前面）；对不齐就返回空串，
     *         调用方继续用原来的 OCR 文字
     */
    private String alignOcrWithRows(List<OcrEngine.Line> lines) {
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null || lines == null || lines.isEmpty()) return "";
        List<QuizAccessibilityService.OptRow> rows = svc.optRows();
        if (rows.size() < 2) return "";

        // 按组分开算（一屏多题时一组一道题）。标签跨组是断的 —— 这一组的 D 接着下一组的 A，
        // 混在一起补断号会凭空插出 B、C 来。
        java.util.TreeMap<Integer, List<QuizAccessibilityService.OptRow>> byGroup = new java.util.TreeMap<>();
        for (QuizAccessibilityService.OptRow r : rows) {
            List<QuizAccessibilityService.OptRow> g = byGroup.get(r.group);
            if (g == null) byGroup.put(r.group, g = new ArrayList<>());
            g.add(r);
        }

        List<OptAnchor> anchors = new ArrayList<>();
        for (Map.Entry<Integer, List<QuizAccessibilityService.OptRow>> e : byGroup.entrySet()) {
            int gi = e.getKey();
            List<QuizAccessibilityService.OptRow> g = e.getValue();
            List<OptAnchor> ga = new ArrayList<>();
            int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE, rowH = 0;
            for (QuizAccessibilityService.OptRow r : g) {
                ga.add(new OptAnchor(r.label, r.rect.centerY(), gi, r.text, r.rect));
                top = Math.min(top, r.rect.top);
                bottom = Math.max(bottom, r.rect.bottom);
                rowH = Math.max(rowH, r.rect.height());
            }
            // 无障碍漏了整行时，OCR 认出来的那枚圈里小字母是唯一线索：一行只有一枚字母、
            // 又瘦又小、落在这一组的纵向范围里，就认它当标签。
            // 往上要多留一行的余地：漏掉的可能是**第一个**选项（实测第 10 题漏的就是 A），
            // 它整个在第一行之上，窗口卡死在一行以内的话，这枚字母正好被挡在外面。
            int pad = Math.max(40, rowH + 80);
            for (OcrEngine.Line l : lines) {
                if (l == null || l.rect == null) continue;
                OcrEngine.Head h = OcrEngine.parseHead(l.text == null ? "" : l.text.trim());
                if (h.label == 0 || !h.rest.trim().isEmpty()) continue;
                if (l.rect.width() > 110 || l.rect.height() > 110) continue;
                int cy = l.rect.centerY();
                if (cy < top - pad || cy > bottom + 40) continue;
                boolean dup = false;
                for (OptAnchor a : ga) if (a.label == h.label) dup = true;
                if (!dup) ga.add(new OptAnchor(h.label, cy, gi, "", l.rect));
            }
            ga.sort((x, y2) -> Integer.compare(x.y, y2.y));
            anchors.addAll(ga);
        }
        if (anchors.size() < 2) return "";

        // 行距取相邻锚点的**最小**间距：多行换行的选项那一格会拉开，最小那个才最接近「一行」。
        int pitch = Integer.MAX_VALUE;
        for (int i = 1; i < anchors.size(); i++) {
            int d = anchors.get(i).y - anchors.get(i - 1).y;
            if (d > 30 && d < pitch) pitch = d;
        }
        if (pitch == Integer.MAX_VALUE) pitch = 200;

        // 断号补全：相邻两枚标签差 gap（「B」到「D」差 2），中间的 gap-1 枚就是漏掉的。
        // 位置按行距插在两者中间。
        for (int i = 0; i < anchors.size() - 1; i++) {
            OptAnchor a = anchors.get(i), b = anchors.get(i + 1);
            int gap = b.label - a.label;
            int dy = b.y - a.y;
            if (gap <= 1 || gap > 4) continue;
            if (a.group != b.group) continue;
            // 两者挨得还没有「gap 行」那么开，说明断号多半是标签认错，别硬插
            if (dy < pitch * gap * 0.55) continue;
            for (int k = 1; k < gap; k++) {
                OptAnchor m = new OptAnchor((char) (a.label + k), a.y + dy * k / gap, a.group, "", null);
                m.guessed = true;
                anchors.add(i + k, m);
            }
            i += gap - 1;
        }

        // 离最近的锚点超过这么远，就不算「这一行的正文」了 —— 题干、页脚都靠这个甩开
        int tol = Math.max(60, Math.min(150, pitch * 6 / 10));
        int firstY = anchors.get(0).y, lastY = anchors.get(anchors.size() - 1).y;

        List<List<String>> bodies = new ArrayList<>();
        List<Rect> bodyRects = new ArrayList<>();     // 每一行正文合起来占的那块，点击兜底用
        for (int i = 0; i < anchors.size(); i++) {
            bodies.add(new ArrayList<>());
            bodyRects.add(null);
        }
        List<String> above = new ArrayList<>();
        for (OcrEngine.Line l : lines) {
            if (l == null || l.rect == null) continue;
            String t = l.text == null ? "" : l.text.trim();
            if (t.isEmpty()) continue;
            OcrEngine.Head h = OcrEngine.parseHead(t);
            // 光秃秃的标签（「A」「B.」）不用当正文 —— 上面已经拿它当过锚点了
            if (h.label != 0 && h.rest.trim().isEmpty()) continue;
            int cy = l.rect.centerY();
            int hit = -1, bestD = Integer.MAX_VALUE;
            for (int i = 0; i < anchors.size(); i++) {
                int d = Math.abs(cy - anchors.get(i).y);
                if (d < bestD) {
                    bestD = d;
                    hit = i;
                }
            }
            if (hit >= 0 && bestD <= tol) {
                // 这一行自己带着「A 肾上腺素」这种完整写法时，剥掉标签只留正文。
                // **只有 OCR 认出的那枚标签跟这一行的标签对得上才剥**：
                // 「G-6-P」「F-6-P」这种选项正文本身就以大写字母开头，OCR 会把
                // 首字母当成选项标签、把「-」当成分隔符，一剥就剩「6-P」——
                // 两个选项都变成「6-P」，模型再聪明也分不出来（实测第 8 题）。
                // 标签对不上，说明那根本不是标签，是正文的一部分，原样留着。
                String body = t;
                if (h.label != 0 && h.label == anchors.get(hit).label) body = h.rest.trim();
                if (!body.isEmpty()) {
                    bodies.get(hit).add(body);
                    Rect b = bodyRects.get(hit);
                    if (b == null) bodyRects.set(hit, new Rect(l.rect));
                    else b.union(l.rect);
                }
            } else if (hit >= 0 && cy > firstY && cy < lastY) {
                // 夹在选项堆里、又离哪一行都远（换行太长、或者它那一行的标签两家都没认出来）：
                // 宁可塞给最近的一行，也别把正文丢了 —— 少一条正文，模型看到的选项就是残的。
                if (t.length() >= 2) bodies.get(hit).add(t);
            } else if (cy <= firstY) {
                // 选项上方那一坨：题干、「3.单选题」有用，单字的「く」「三」是 OCR 噪声，丢掉。
                if (t.length() >= 2) above.add(t);
            }
        }

        int filled = 0;
        for (List<String> b : bodies) if (!b.isEmpty()) filled++;
        if (filled < 2) return "";      // 一行正文都对不上：这份「对齐」没意义，别拿去顶掉原文

        StringBuilder sb = new StringBuilder();
        for (String a : above) sb.append(a).append('\n');
        for (int i = 0; i < anchors.size(); i++) {
            sb.append(anchors.get(i).label).append(' ').append(anchors.get(i).text);
            for (String b : bodies.get(i)) sb.append(b);
            sb.append('\n');
        }

        // 顺手把每一行的落点记下来，供点击时兜底（见 alignedBounds）：无障碍漏掉的那些行
        // （实测是 A 和 C）在无障碍树里按标签根本找不到，只能靠这份几何。
        List<QuizAccessibilityService.OptRow> geo = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            OptAnchor a = anchors.get(i);
            Rect r = a.rect != null ? new Rect(a.rect) : null;
            if (r == null) {
                // 断号补出来的那一行：页面上没实读到它的矩形，就照左右邻行的**宽度和高度**
                // 在中间摆一个。要照着邻行来，不能拿它的正文矩形 —— 学习通这行能点的只有
                // 左边那枚圈里的小标签，正文那一截是另一个节点，落在正文上点不动
                // （实测点「A」那一块才选得上）。
                OptAnchor near = nearestRectAnchor(anchors, i);
                if (near != null) {
                    Rect nr = near.rect;
                    int h = Math.max(80, Math.min(nr.height(), pitch / 2));
                    r = new Rect(nr.left, a.y - h / 2, nr.right, a.y + h / 2);
                } else {
                    r = bodyRects.get(i);
                }
            }
            if (r != null && r.width() > 0 && r.height() > 0) {
                geo.add(new QuizAccessibilityService.OptRow(a.label, r, "", a.group));
            }
        }
        alignedRows = geo;
        return sb.toString().trim();
    }

    /** 找离第 {@code i} 个锚点最近、又实认得出矩形的那一个（用来给补出来的行定宽高）。 */
    private static OptAnchor nearestRectAnchor(List<OptAnchor> anchors, int i) {
        for (int d = 1; d < anchors.size(); d++) {
            int a = i - d, b = i + d;
            if (a >= 0 && anchors.get(a).rect != null) return anchors.get(a);
            if (b < anchors.size() && anchors.get(b).rect != null) return anchors.get(b);
        }
        return null;
    }

    /**
     * 遥控口用：把这一屏的 **OCR 原始行**（正文 + 坐标）、**无障碍认出来的选项行**、
     * 以及按行对齐的成品并排落一份，给 adb 拉回来看。
     * <p>
     * 起因：学习通有的页面上 Chromium 只把**一部分**选项行放进无障碍树（实测第 10 题
     * 只给了 B、D、E，A 和 C 整行不在树里），对齐出来的选项列表就缺两条，模型回「选项
     * 不连续、无法确认」。到底是「OCR 没认出圈里那枚字母」还是「无障碍漏了行」，光看
     * 对齐后的成品分不出来 —— 这里把两个原始来源并排摆出来，一眼能看见缺的是哪一头。
     */
    /**
     * 遥控口用：把「无障碍掉线自救」这条路从头到尾探一遍，每步结果原样回报。
     * <p>
     * 自救走的是 Shizuku（shell 身份写 secure 设置），中间任何一环断了都只会
     * 「静悄悄地没修好」。这个口子就是把每一环的状态摆出来：Shizuku 通不通、
     * 授权还在不在、shell 命令真跑起来没有、读回来的设置是什么。
     */
    static String debugHeal() {
        StringBuilder sb = new StringBuilder();
        FloatingService self = inst;
        sb.append("WRITE_SECURE_SETTINGS=")
                .append(self == null ? "?（悬浮窗服务没在跑，查不了）"
                        : (self.canWriteSecure() ? "给了" : "没给"))
                .append('\n');
        sb.append("Shizuku ping=").append(ShizukuShell.ping())
                .append(" granted=").append(ShizukuShell.granted())
                .append(" ver=").append(ShizukuShell.version()).append('\n');
        sb.append("无障碍服务实例=")
                .append(QuizAccessibilityService.get() != null ? "在" : "不在")
                .append("   悬浮窗服务=").append(inst != null ? "在" : "不在").append('\n');
        try {
            sb.append("shell id=").append(ShizukuShell.str("id").trim()).append('\n');
            sb.append("enabled_accessibility_services=")
                    .append(ShizukuShell.str("settings get secure enabled_accessibility_services").trim())
                    .append('\n');
            sb.append("accessibility_enabled=")
                    .append(ShizukuShell.str("settings get secure accessibility_enabled").trim())
                    .append('\n');
        } catch (Throwable t) {
            sb.append("shell 执行失败：").append(t).append('\n');
        }
        if (self == null) {
            sb.append("（悬浮窗服务没在跑，试不了它那条自救）");
        } else {
            self.lastHealAt = 0;                     // 绕开防抖，手动跑一次真的
            boolean healed = self.tryHealA11y();
            sb.append("tryHealA11y=").append(healed).append('\n');
            sb.append("（试完 enabled_accessibility_services=")
                    .append(self.readEnabledA11y()).append('\n')
                    .append("  accessibility_enabled=")
                    .append(android.provider.Settings.Secure.getString(self.getContentResolver(),
                            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED))
                    .append("）");
        }
        return sb.toString();
    }

    /**
     * 遥控口用：把这一屏的截图直接发给视觉大模型问一次，回复原样交给调用方落盘。
     * <p>
     * 只为验「手上这套 key / 模型认不认图片」—— 不用改设置、不用等连答跑一轮。
     */
    static String debugVision() {
        FloatingService s = inst;
        if (s == null) return "悬浮窗服务没在跑（先在 App 里点「启动悬浮窗」）";
        if (!Prefs.hasVision()) return "没有可用的 key（主模型那栏也没填）";
        Bitmap shot = s.screenshot();
        if (shot == null) return "截不到屏";
        try {
            LlmClient.Reply r = LlmClient.askVision(shot);
            return "模型=" + Prefs.effVisionModel() + "\n地址=" + Prefs.effVisionUrl()
                    + "\nok=" + r.ok + "  耗时=" + r.costMs + "ms  可重试=" + r.retryable
                    + "\n错误=" + r.error
                    + "\n\n--- 模型回复 ---\n" + r.content;
        } catch (Throwable t) {
            return "视觉请求炸了：" + t;
        } finally {
            if (!shot.isRecycled()) shot.recycle();
        }
    }

    static String debugOcrDump() {
        FloatingService s = inst;
        if (s == null) return "悬浮窗服务没在跑（先在 App 里点「启动悬浮窗」）";
        Bitmap shot = s.screenshot();
        if (shot == null) return "截不到屏";
        try {
            OcrEngine.Result r = OcrEngine.recognize(shot, Prefs.cropTop(), Prefs.cropBottom());
            StringBuilder sb = new StringBuilder();
            sb.append("== OCR 原始行（").append(r.lines.size()).append(" 行）==\n");
            for (OcrEngine.Line l : r.lines) {
                if (l == null || l.rect == null) continue;
                sb.append("  y ").append(l.rect.top).append("..").append(l.rect.bottom)
                        .append("  x ").append(l.rect.left).append("..").append(l.rect.right)
                        .append("  | ").append(l.text).append('\n');
            }
            sb.append("\n== 无障碍认出来的选项行 ==\n");
            QuizAccessibilityService svc = QuizAccessibilityService.get();
            if (svc == null) {
                sb.append("（无障碍服务没连）\n");
            } else {
                List<QuizAccessibilityService.OptRow> rows = svc.optRows();
                sb.append("  rows=").append(rows.size()).append('\n');
                for (QuizAccessibilityService.OptRow row : rows) {
                    sb.append("  ").append(row.label)
                            .append("  y ").append(row.rect.top).append("..").append(row.rect.bottom)
                            .append("  | ").append(row.text).append('\n');
                }
                sb.append("\n== 按行对齐之后 ==\n")
                        .append(s.alignOcrWithRows(r.lines)).append('\n');
                sb.append("\n== 指纹素材（换题判定用）==\n")
                        .append("  整屏：").append(svc.quickText().replace("\n", " "))
                        .append("\n  挖掉选项后：").append(svc.stemText().replace("\n", " "))
                        .append("\n  screenKey=").append(s.screenKey())
                        .append('\n');
                // 每个标签的选中态和落点：查「已经选上的还要不要再点」时全靠这几行
                sb.append("\n== 每个标签 ==\n");
                for (char c = 'A'; c <= 'F'; c++) {
                    String lab = String.valueOf(c);
                    Rect br = svc.boundsOfOption(lab, -1);
                    Rect ar = s.alignedBounds(lab, -1);
                    sb.append("  ").append(lab)
                            .append("  a11y选中=").append(svc.optionSelected(lab, -1))
                            .append("  a11y行=").append(br == null ? "null" : br.toShortString())
                            .append("  对齐落点=").append(ar == null ? "null" : ar.toShortString())
                            .append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "OCR 失败：" + t;
        } finally {
            if (!shot.isRecycled()) shot.recycle();
        }
    }

    private void doAsk() {
        long t0 = SystemClock.uptimeMillis();
        LogRecord rec = new LogRecord();
        rec.time = System.currentTimeMillis();

        // 进 I/O 之前先把中断标志清掉。上一轮留下的标志会让这一次请求
        // 一上来就抛 InterruptedIOException，白白废掉一题。
        Thread.interrupted();

        try {
            int mode = Prefs.ocrSource();
            // 这一轮问答的号：迟到的答复回来时对不上号就不许点（见 askEpoch）
            final int myEpoch = ++askEpoch;

            // 读屏**之前**先把这一屏的指纹和前台 App 拍下来。读屏到点答案中间隔着一整次
            // 大模型请求（实测 2～20 秒），这期间页面翻了屏、或者前台换成了别的 App，
            // 按旧屏算出来的组号点下去就是瞎点。上面那个 askKey 是**答复回来之后**才拍的，
            // 拿它比等于自己跟自己比，拦不住任何东西（见 screenMoved）。
            String keyAtRead = screenKey();
            String pkgAtRead = QuizAccessibilityService.activeAppPackage();

            Reading rd = readAndAsk(rec, mode);
            if (!rd.ownApp) ownAppNoted = false;

            // 记下「我刚看过的这一屏」。自动连答下一轮先比这个指纹：一样就说明页面没翻，
            // 那就只重试「下一题」，不重答。这里是每次都记（不管后面成没成功），
            // 所以读到一个答不出题的页面（比如答完的结算页）也只会白问一次，不会反复问。
            // 必须跟循环里的 screenKey() 用同一套算法，否则两边算出来的指纹对不上，
            // 循环会把每一屏都当成「刚翻过」。
            askKey = screenKey();
            lastScreenKey = askKey;

            // 记下「这次答的是哪个 App」，自动连答只在这个 App 还是前台时才继续。
            // **只有读到真像题目的屏才记**：随手切到桌面、切到微信那几轮读到的不是题，
            // 却也把 App 记下来的话，连答就认死了桌面那个包名 —— 等用户回到题目 App，
            // 「前台换 App 了」这条会把答题永久停住（实测按一下 Home 就中招）。
            String pkg = QuizAccessibilityService.currentPackage();
            if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())
                    && looksLikeQuiz()) {
                quizPkg = pkg;
            }

            // 这条路答不出来（没读到字 / 模型给了「？」）就换另一条路再读再问一遍。
            // 两个开关在设置里，「无障碍↔OCR」各自可控。
            AnswerParser.Parsed p = rd.parsed();
            if (!usable(p)) {
                int alt = altMode(rd, mode);
                if (alt >= 0) {
                    logAsk(stamp() + " " + (rd.fail.isEmpty() ? "这条路没答出来" : rd.fail)
                            + "，改走「" + srcName(alt) + "」重读一遍");
                    Reading rd2 = readAndAsk(rec, alt);
                    AnswerParser.Parsed p2 = rd2.parsed();
                    if (usable(p2) || (!rd.hasReply() && rd2.hasReply())) {
                        rd = rd2;
                        p = p2;
                    }
                }
            }

            if (!rd.hasReply()) {
                if (rd.ownApp) {
                    // 自己的设置页/日志页停在前台时每个连答间隔都会走到这儿，只报一次，
                    // 不然日志两秒刷一行。
                    if (!ownAppNoted) {
                        ownAppNoted = true;
                        logAsk(stamp() + " 前台是本应用自己的界面，不是题目页面：这一轮不读不点"
                                + "（回到题目 App 自动接着答）");
                    }
                    lastRoundAnswered = false;
                    lastRoundBlank = false;   // 这不是题目页，别拿「往下滑」去翻自己的设置页
                    restoreLabel();
                    scheduleLoop();
                    return;
                }
                // 说清楚到底哪儿卡住了：读不到字和「大模型超时」以前都印成「取词失败」，
                // 看日志的人（和写日志的人）都以为是取词的问题。
                logAsk(stamp() + " " + (rd.fail.isEmpty() ? "取词失败" : rd.fail)
                        + " source=" + rd.source
                        + (rd.shotFrom.isEmpty() ? "" : " 截图来自" + rd.shotFrom)
                        + (rd.shotBlocked ? " 截图全黑(FLAG_SECURE)" : "")
                        + (rec.error.isEmpty() ? "" : " err=" + rec.error));
                // 大模型这条已经重试满了还在失败（askWithRetry 里试够了次数）：这不是
                // 「这一屏读坏了」，是网络/服务端真的不通。再往下滚也只是每拍重来一次
                // 同样的失败，白耗一题的时间，索性停下等用户（球变红，日志里写着原因）。
                // 只在连答跑着的时候停：单次问答（autoLoop 关着）是用户手按的，
                // 网络抖一下就把服务钉成暂停太粗暴；用户点了暂停时 halt 自己会 no-op。
                if (rd.requestFailed && Prefs.autoLoop() && running && !paused) {
                    finish(rec, t0, rd.fail);
                    halt("大模型连着 " + (LLM_RETRIES + 1) + " 次都没通（" + rd.reply.error + "）");
                    return;
                }
                if (rd.shotBlocked) {
                    // 视觉优先模式下截图黑了会自动退无障碍（readAndAsk 里做了），走到这儿的
                    // 意思是**两条路都没读出题**，那就别再劝用户去改设置了。
                    finish(rec, t0, mode == 4
                            ? "截图是黑的（这个 App 设了 FLAG_SECURE），已经改读无障碍文本，"
                                    + "那边也没读出题来。看看「全屏看 → 运行日志」里读到的是什么"
                            : "截图是黑的，这个 App 很可能设了 FLAG_SECURE 禁止截屏。"
                                    + "把「文本来源」改成「仅无障碍文本」试试——无障碍读字不走截图");
                } else {
                    finish(rec, t0, rd.fail.isEmpty() ? "这一轮没拿到答案" : rd.fail);
                }
                return;
            }

            String text = rd.text;
            String source = rd.source;
            LlmClient.Reply reply = rd.reply;
            boolean answeredByVision = rd.vision;

            rec.question = text;
            rec.source = source;
            logAsk(stamp() + " source=" + source
                    + (rd.shotFrom.isEmpty() ? "" : "/" + rd.shotFrom)
                    + (answeredByVision ? "（走视觉大模型）" : " 读到" + text.length() + "字")
                    // 记下「答这题时这一屏长什么样」。连答下一拍拿它比，才能知道页面翻没翻 ——
                    // 只报长度和开头，够看「是不是同一屏」，又不至于把日志刷爆。
                    + " 这屏" + keyPreview(askKey)
                    + "\n--- 题目 ---\n" + (answeredByVision ? "(截图，无文本)" : clip(text, 800))
                    + "\n--- 模型原始回复 ---\n" + clip(reply == null ? "" : reply.content, 800)
                    + (reply == null || reply.note.isEmpty() ? "" : "\n注：" + reply.note));

            // 把这一屏的原文一起交给解析器：题面上写了「单选」还是「多选」得它自己看。
            if (p == null) p = AnswerParser.parse(reply == null ? "" : reply.content, Prefs.labels(), text);
            rec.answer = p.answer;
            rec.fill = p.fill;
            rec.explanation = p.explanation;
            rec.costMs = SystemClock.uptimeMillis() - t0;

            // 迟到的答复：问了这屏、等回来的却是几题之后的答案 —— 点下去就是在别的题上乱按。
            // 用户报的「隔了十几秒突然弹出来，然后屏幕乱按一下」正是这个。
            long waitedMs = rec.costMs;
            if (myEpoch != askEpoch) {
                logAsk(stamp() + " 这一轮的答复来晚了（已经开了新一轮），丢弃不点："
                        + rec.answer);
                lastRoundAnswered = false;
                restoreLabel();
                return;
            }
            // 只有思维链、没有结论的答复一律不点。content 为空说明模型压根没写出答案 JSON，
            // 解析器是从思维链那段自言自语里抠出来的字母 —— 那里面同时躺着「A 还是 B？」
            // 「D 应该是东」和它转述的选项文本，抠出来的东西点下去就是乱选。这种答复
            // LlmClient 已经换直答模型重问过一遍了（见 usedReasoning），还是这样就只能不点。
            if (!answeredByVision && reply != null && reply.usedReasoning) {
                logAsk(stamp() + " 模型只吐了思维链、没给结论，这一轮不点（怕从自言自语里抠出字母）："
                        + rec.answer);
                lastRoundAnswered = false;
                lastRoundBlank = false;     // 答复是废的，不是「这屏答不了」，别拿去当「往下滑」的理由
                // 别清 lastScreenKey：这一屏就当成「没答出来」，连答那边看到指纹没变
                // 就不会去点「下一题」；清了它反倒被当成「换屏了」。
                restoreLabel();
                scheduleLoop();
                return;
            }

            // 读屏和点答案中间隔了一整次大模型请求：这期间页面翻了屏、或者前台换成了别的
            // App，这一轮的答案就是给**上一屏**的，点下去落在别的题上 —— 用户报的
            // 「选项都没出来就把 BC 选了，往下滑查到这题又选了 A」正是这么来的。
            // 不管等了多久都比一次：实测两秒的答复照样能点到另一个 App 的另一道题上。
            String moved = screenMoved(pkgAtRead, keyAtRead, p);
            if (!moved.isEmpty()) {
                logAsk(stamp() + " 读屏到点答案之间" + moved + "（等了 " + (waitedMs / 1000)
                        + " 秒），这一轮作废不点：答案 " + rec.answer);
                lastRoundAnswered = false;
                lastRoundBlank = false;   // 没答成是因为屏幕换了，不是这屏答不了，别往下滑
                lastScreenKey = "";     // 让连答下一拍走「页面换过了」那条路，立刻重读
                restoreLabel();
                showBubble(rec.answer, "页面变了，没点");
                scheduleLoop();
                return;
            }

            // 填空题：模型把逐空答案放在 blanks 里（没给 blanks 就退回 fill 整段）。
            // 一屏多题 —— 包括「填空题 + 选择题混排」「好几个填空」—— 都走 doMulti，
            // 那里才能按屏幕上的先后顺序一题一题处理；只有单题才走 doFill。
            List<String> pageBlanks = blanksOf(p);
            QuizAccessibilityService svcG = QuizAccessibilityService.get();
            int groups = svcG == null ? 0 : svcG.optionGroupCount();
            boolean wantFill = (Prefs.fillCopy() || Prefs.autoFill())
                    && (!pageBlanks.isEmpty() || !rec.fill.isEmpty());
            // 一屏多题（竖排版）：屏幕上认得出两组以上选项、或者模型给了两条以上答案，
            // 答案就得按题号一组一组地点。选项标签 A/B/C 在每一题里都重名，不分组的话
            // 每条答案都会落到屏幕最上面那一组，也就是「好几个答案全点到第一题上」。
            // 只认出一组时也不能掉以轻心：
            //   · 这组要是没完整露出来（上面缺 A、下面压着 C/D），先交给 doMulti，
            //     它会一个字母都不点，只滑一点等看全 —— 用户明确要求「选项不显示全不回答」；
            //   · 屏幕上明明有选项行、却一组都没凑出来（下一题只露出一个 A），
            //     更不能走单题老路全屏找第一个字母，那等于闭着眼睛点。
            boolean oneGroupCut = svcG != null && groups == 1
                    && !svcG.optionGroupFullyVisible(0);
            boolean strayOptions = svcG != null && groups == 0 && svcG.hasAnyOptionRow();
            // 上半截还压着上一题的选项（不是从 A 起头）：单题老路全屏找字母会点到
            // 那几行上，必须按组点 —— 哪怕只认出一组。
            boolean tailAbove = svcG != null && groups <= 1 && svcG.hasLeadingOptionTail();
            if (groups >= 2 || p.items.size() >= 2 || oneGroupCut || strayOptions
                    || tailAbove) {
                doMulti(rec, p, groups);
                return;
            }
            if (wantFill) {
                doFill(rec, p);
                return;
            }
            if (rec.answer.isEmpty() || "?".equals(rec.answer)) {
                rec.clicked = -1;
                rec.error = "没能确定答案";
                logAsk("    → 解析不出答案。explanation=" + p.explanation);
                LogStore.add(this, rec);
                notifyAnswer("?", rec.explanation.isEmpty() ? "无法判断，看日志里的原文" : rec.explanation);
                paintBall(BALL_ERROR);   // 模型没给出可用答案，球变红
                restoreLabel();
                // 这一屏没答出来：连答不许点「下一题」，跳过去就等于白丢一题。
                lastRoundAnswered = false;
                // 「模型说答不了」和「请求失败」是两回事：前者是选项没显示全，往下滑一屏
                // 就能答；后者跟屏幕无关，滑走就是把题跳过去（见 lastRoundBlank）。
                // 得是它真看过题（items 非空）才算「答不了」：items 空说明这轮没读成，
                // 往下滑等于把最上面那题直接划过去（见 doMulti 里同一条注释）。
                lastRoundBlank = !p.items.isEmpty();
                // 这种不算失败：别把连答间隔一轮轮拖长，不然滑到能答的地方要等半分钟。
                scheduleLoop();
                return;
            }

            lastRoundAnswered = true;    // 这一屏确实答出来了，连答才允许往下一题走
            lastRoundBlank = false;
            paintBall(BALL_RUN);         // 答出来了，球回到蓝的
            showBubble(rec.answer, rec.explanation);
            speak(rec.answer, rec.explanation);

            // 模型抄回来的题干是**屏幕下面**那道题的（只露出题干、选项还在屏幕外）：
            // 这一屏的选项属于上面那题，把这条答案点下去就是点到别人头上。
            // 用户报的布局：A B C D 下面紧跟一行「2.下一题题干」。滑动前会先确认键盘
            // 已经收起（见 scrollToShowOptions），免得手势打到键盘上。
            if (Prefs.autoClick() && AnswerParser.stemBelowOptions(p.q, rec.question)) {
                if (optScrollTries < 3 && scrollToShowOptions(false)) {
                    optScrollTries++;
                    restoreLabel();
                    return;
                }
                logAsk(stamp() + " 模型答的是屏幕下面那道题（只露出题干、选项还在屏幕外），"
                        + "这一屏不点；想往下滑也没滑动");
                notifyAnswer(rec.answer, "那是下面那道题，选项还没露出来");
                showBubble(rec.answer, "那是下面那道题，选项还没露出来");
                LogStore.add(this, rec);
                restoreLabel();
                halt("模型答的是屏幕下方只露出题干的那道题，它的选项还没露出来 —— "
                        + "往下挪一下让选项全部露出来，再点悬浮球继续");
                return;
            }

            if (Prefs.autoClick()) {
                awaitSettled();          // 页面还在滚就先别点，坐标会飘
                setFloatLabel(rec.answer);
                rec.clicked = clickLetters(rec.answer);
                if (rec.clicked == 0 && running && !paused) {
                    // 一个都没点中：多半是这一瞬读不到无障碍树（选项节点全找不到，
                    // 见 rootOrNull），等半秒再点一次。真点不上时只是白等这半秒。
                    // 上一次一个都没落下去，clickedLetters 里也没记东西，重试是干净的。
                    sleep(700);
                    rec.clicked = clickLetters(rec.answer);
                }
                logAsk("    → 答案=" + rec.answer + " 点了 " + rec.clicked + " 个选项"
                        + (rec.clicked > 0 ? "" : "（一个都没点中！）") + trimmedHint(p, rec.answer));
                if (Prefs.autoNext()) {
                    scheduleNextClick((long) (Prefs.nextDelaySec() * 1000));
                }
            } else {
                logAsk("    → 答案=" + rec.answer + "（自动点击关着）" + trimmedHint(p, rec.answer));
            }
            // 先点后弹：这条通知是 IMPORTANCE_HIGH 的，会以**横幅**形式压在屏幕顶上。
            // 弹在点击之前就是自己给自己挡路 —— 横幅盖在选项上，点下去落在横幅上，
            // 选项那行反而没点到。一屏多题那条路本来就是先点后弹。
            notifyAnswer(rec.answer, rec.explanation);
            LogStore.add(this, rec);
            restoreLabel();
            failStreak = 0;              // 答出来了，重试间隔恢复正常
            scheduleLoop();
        } catch (Throwable t) {
            Log.w(TAG, "doAsk", t);
            rec.error = String.valueOf(t);
            finish(rec, t0, "出错了：" + t);
        } finally {
            busy = false;
            scheduleTuck();
        }
    }

    /**
     * 单选题被截成一个字母时，把模型原本给的字母串写进日志。截断是**悄悄**发生的
     * （题面写着「单选」、模型却回了 ACD），不写出来就没法复盘「它到底是不是回错了」。
     */
    private static String trimmedHint(AnswerParser.Parsed p, String answer) {
        String raw = p.rawAnswer;
        if (raw == null || raw.length() <= 1) return "";
        if (answer == null || answer.length() != 1) return "";
        if (raw.length() > 12) raw = raw.substring(0, 12) + "…";
        return "（模型原答 " + raw + "，题面按单选只留了 " + answer + "）";
    }

    /**
     * 把整屏的逐空答案摊成一条：按题目在屏幕上的先后，一道题接一道题。
     * 每一项都带着它属于哪道大题（题干指纹），往下滑过之后靠它把「已经填过的那几个」
     * 从回复里扣掉，后面大题的空才不会吃到前面大题的答案。
     * 模型没给 {@code blanks} 时退回那条题的 {@code fill}：多行 / 带 1. 2. 编号的
     * 拆成逐空，老格式和模型偶尔的偷懒都还能用。
     */
    private static List<BlankSlot> blankSlotsOf(AnswerParser.Parsed p) {
        List<BlankSlot> out = new ArrayList<>();
        if (p.items.isEmpty()) {
            String stem = stemKey(p.q);
            if (!p.blanks.isEmpty()) {
                addBlanks(out, stem, p.blanks);
            } else if (!p.fill.isEmpty()) {
                for (String b : splitFill(p.fill)) out.add(new BlankSlot(stem, b));
            }
            return out;
        }
        for (AnswerParser.Parsed it : p.items) {
            String stem = stemKey(it.q);
            if (!it.blanks.isEmpty()) {
                addBlanks(out, stem, it.blanks);
            } else if (!it.fill.isEmpty()) {
                for (String b : splitFill(it.fill)) out.add(new BlankSlot(stem, b));
            }
        }
        return out;
    }

    /** 把一道大题的 blanks 按顺序摊开，顺手去掉模型误抄的题面编号。 */
    private static void addBlanks(List<BlankSlot> out, String stem, List<String> raw) {
        for (int i = 0; i < raw.size(); i++) {
            out.add(new BlankSlot(stem, cleanBlank(raw.get(i), i)));
        }
    }

    private static List<String> blanksOf(AnswerParser.Parsed p) {
        List<String> out = new ArrayList<>();
        for (BlankSlot s : blankSlotsOf(p)) out.add(s.answer);
        return out;
    }

    /**
     * 模型把一道大题几个空挤进一条 fill（「1. 甲\n2. 乙」「①甲②乙」「（1）甲（2）乙」）时，
     * 按编号拆回逐空。只有真的出现两个以上编号才拆，单纯的小数、化学式不动。
     */
    private static List<String> splitFill(String fill) {
        List<String> out = new ArrayList<>();
        if (fill == null) return out;
        String text = fill.trim();
        if (text.isEmpty()) return out;
        String[] lines = text.split("\\r?\\n");
        if (lines.length > 1) {
            for (String line : lines) {
                String s = stripNumber(line.trim());
                if (!s.isEmpty()) out.add(s);
            }
            if (out.size() > 1) return out;
            out.clear();
        }
        java.util.regex.Matcher m = NUMBER_MARK.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            ends.add(m.end());
        }
        if (starts.size() < 2) {
            out.add(text);
            return out;
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = ends.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String s = text.substring(from, to).trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (out.isEmpty()) out.add(text);
        return out;
    }

    /** 「1. 」「1、」「（1）」「①」这类题面编号。小数点后面必须有空白才算编号。 */
    private static final java.util.regex.Pattern NUMBER_MARK = java.util.regex.Pattern.compile(
            "(?:^|(?<=\\s))(?:[（(][0-9０-９]{1,2}[）)]|[0-9０-９]{1,2}[、)]"
                    + "|[0-9０-９]{1,2}[.．](?=\\s)|[①②③④⑤⑥⑦⑧⑨⑩])");

    private static String stripNumber(String s) {
        java.util.regex.Matcher m = NUMBER_MARK.matcher(s);
        if (m.find() && m.start() == 0) return s.substring(m.end()).trim();
        return s;
    }

    /**
     * 模型有时把题面编号一起抄进某个空的答案（「1. 北京」「（2）长江」「② H2O」）。
     * <p>
     * 只有开头编号**正好等于这个空在题里的序号**、且后面确实是答案文字时才去掉：
     * 「2.5」「3.14」「1、2、3」这种答案本身带数字的保持原样，不能把数字吃掉。
     * 一页题面上是「1.____ 2.____」编号、下面才是「1. □」「2. □」作答框的那种排版，
     * 编号只是标位置，不该被填进框里。
     */
    private static String cleanBlank(String raw, int index) {
        if (raw == null) return "";
        String s = raw.trim();
        java.util.regex.Matcher m = BLANK_NUM_PREFIX.matcher(s);
        if (!m.find() || m.start() != 0) return s;
        String num = null;
        for (int g = 1; g <= 5; g++) {
            if (m.group(g) != null) {
                num = m.group(g);
                break;
            }
        }
        String rest = s.substring(m.end()).trim();
        if (num == null || rest.isEmpty()) return s;
        return blankNumber(num) == index + 1 ? rest : s;
    }

    private static int blankNumber(String token) {
        if (token == null || token.isEmpty()) return -1;
        int circled = "①②③④⑤⑥⑦⑧⑨⑩".indexOf(token.charAt(0));
        if (circled >= 0) return circled + 1;
        int d = Character.digit(token.charAt(0), 10);
        return d < 0 ? -1 : d;
    }

    /**
     * 开头题号：「（2）」「1. 」后面跟空白的、「1.北京」点号后不是数字的、
     * 「2、」「3）」后不是数字的、以及「①」。小数点后直接跟数字的（2.5）不算编号。
     */
    private static final java.util.regex.Pattern BLANK_NUM_PREFIX =
            java.util.regex.Pattern.compile(
                    "^\\s*(?:[（(]([0-9０-９]{1,2})[）)]"
                            + "|([0-9０-９]{1,2})\\s*[.．]\\s+(?=\\S)"
                            + "|([0-9０-９]{1,2})\\s*[.．](?=\\D)"
                            + "|([0-9０-９]{1,2})\\s*[、)）](?=\\D)"
                            + "|([①②③④⑤⑥⑦⑧⑨⑩]))\\s*");

    /**
     * 把答案按顺序填进这一屏空着的输入框，返回真填进去几个。
     * <p>
     * 答案和输入框都按「从上到下、同一行从左到右」排 —— 第 k 个答案进第 k 个空。
     * 空字符串答案占一个位置但不填字（模型没答出来的那个空），后面的答案就不会往前挪；
     * 同一屏重读时已经填过的框同样占位置、但不重复填（见 {@link #filledFields}）。
     * <p>
     * 只填完整显示在屏幕内、当前还是空的框：已经有字的不覆盖，屏幕外的不动 ——
     * 等连答往下滑、它完整露出来再说。已经填过的框（文字和排到的答案一样）算完成、
     * 不重复填，这样填完重读这一屏也不会被当成「没填进去」。
     */
    private int fillBlanks(String screenKey, List<BlankSlot> slots) {
        if (slots.isEmpty()) return 0;
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null) {
            logAsk("    → 想填空，但无障碍服务没连上");
            return 0;
        }
        if (!screenKey.equals(filledFieldsKey)) {
            filledFields.clear();
            filledFieldsKey = screenKey;
        }
        // 每道大题已经填过的开头一截先扣掉：往下滑过一屏之后，上面填过的框不在屏幕上了，
        // 可模型往往还把整道大题的 blanks 全抄回来 —— 不扣就会把第一道大题的答案
        // 又填进第二道大题的空里。
        List<BlankSlot> answers = dropAlreadyFilled(slots);
        if (answers.isEmpty()) {
            logAsk("    → 这一屏的填空都已经填过了");
            return countFilledSlots(slots);
        }
        List<AccessibilityNodeInfo> fields = svc.visibleFields();
        if (fields.isEmpty()) {
            logAsk("    → 屏幕上看不到输入框（" + answers.size() + " 个逐空答案没处填）");
            return 0;
        }
        int filled = 0, cursor = 0;
        for (AccessibilityNodeInfo f : fields) {
            if (paused) break;
            if (cursor >= answers.size()) break;
            String cur = QuizAccessibilityService.textOfField(f).trim();
            if (!cur.isEmpty()) {
                // 已经有字的框：不覆盖。里面正好是某个还没用掉的答案，就当它是刚才填进去的，
                // 重读这一屏时不重复填，也不能把它当成「没填」—— 否则这一题就永远不算完成，
                // 也就不会去点「下一题」。往后找而不只看 cursor：往下滑过一屏之后，前面的
                // 答案对的是已经滚出屏幕的框，手里这个框对应的是后面那条。
                // 不属于答案的文字（搜索框之类）只跳过、不占答案位。
                int hit = -1;
                for (int j = cursor; j < answers.size(); j++) {
                    if (cur.equals(answers.get(j).answer.trim())) {
                        hit = j;
                        break;
                    }
                }
                if (hit >= 0) {
                    if (hit > cursor) {
                        logAsk("    → 填空第 " + (cursor + 1) + "～" + hit + " 个空已经滚出屏幕，跳过");
                    }
                    logAsk("    → 填空第 " + (hit + 1) + " 个空已经是：" + clip(cur, 60));
                    filled++;
                    cursor = hit + 1;
                }
                continue;
            }
            Rect r = QuizAccessibilityService.boundsOfNode(f);
            String key = r == null ? "" : r.left + "," + r.top;
            BlankSlot slot = answers.get(cursor++);
            String ans = slot.answer;
            if (ans.isEmpty()) {
                logAsk("    → 填空第 " + cursor + " 个空：模型没给答案，留空");
                rememberFilled(slot);
                continue;
            }
            if (!key.isEmpty() && filledFields.contains(key)) {
                logAsk("    → 填空第 " + cursor + " 个空：这一屏刚才已经填过，跳过");
                continue;
            }
            boolean ok = svc.setFieldText(f, ans);
            if (ok) {
                filled++;
                if (!key.isEmpty()) filledFields.add(key);
                rememberFilled(slot);
                sleep(250);      // 等 WebView 把字画上去、页面重排，再填下一个
            }
            logAsk("    → 填空第 " + cursor + " 个空" + (r == null ? "" : "（y=" + r.top + "）")
                    + (ok ? "已填入：" : "没填进去：") + clip(ans, 60));
        }
        return filled;
    }

    /**
     * 扣掉每道大题「已经填过」的那一截。只扣回复里和记录开头**逐项对得上**的部分：
     * 模型这次只给了剩下的空（开头就是还没填的那个），一项都对不上，就原样保留。
     */
    private List<BlankSlot> dropAlreadyFilled(List<BlankSlot> slots) {
        List<BlankSlot> out = new ArrayList<>();
        int i = 0;
        while (i < slots.size()) {
            String stem = slots.get(i).stem;
            int j = i;
            while (j < slots.size() && slots.get(j).stem.equals(stem)) j++;
            List<BlankSlot> group = slots.subList(i, j);
            int drop = 0;
            List<String> done = stem.isEmpty() ? null : filledByStem.get(stem);
            if (done != null) {
                while (drop < done.size() && drop < group.size()
                        && group.get(drop).answer.trim().equals(done.get(drop).trim())) {
                    drop++;
                }
            }
            if (drop > 0 && drop >= group.size()) {
                logAsk("    → 「" + clip(stem, 16) + "」这道大题的 " + drop + " 个空都已经填过");
            }
            out.addAll(group.subList(drop, group.size()));
            i = j;
        }
        return out;
    }

    /** 记下这道大题又填进去一个空，下次重读时用它扣掉已填的前缀。 */
    private void rememberFilled(BlankSlot slot) {
        if (slot == null || slot.stem.isEmpty()) return;
        List<String> done = filledByStem.get(slot.stem);
        if (done == null) {
            done = new ArrayList<>();
            filledByStem.put(slot.stem, done);
        }
        done.add(slot.answer);
    }

    /** 全部扣完时，这一组里真正填进去过几个（占位的空不算）。 */
    private int countFilledSlots(List<BlankSlot> slots) {
        int n = 0;
        for (BlankSlot s : slots) if (!s.answer.isEmpty()) n++;
        return n;
    }

    /**
     * 填空填完这一屏后，屏幕下方还有空着的输入框就往下滑一屏。
     * <p>
     * 为什么不能等连答那一拍：填完字之后这一屏的读屏结果就变了（空框变成有字），
     * 屏幕指纹跟着变，连答会把「刚填完的同一屏」当成翻到了新的一页，**不会**走
     * 「往下滑」那条路。所以这里填完就自己滑，滑到下半截再读、再填。
     *
     * @return 真的滑了并排好了重读 —— 调用方到此为止，别再点下一题
     */
    private boolean scrollToMoreBlanks() {
        if (!Prefs.autoScroll()) return false;
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null || !svc.hasEmptyFieldsBelow()) return false;
        boolean scrolled = false;
        // 空框可能隔着不止一屏（题干长、中间有空白），滑一次没露出来就再滑 ——
        // 最多三次，免得页面底部那段空输入框把我们一直往下带。
        for (int i = 0; i < 3 && !paused; i++) {
            if (!svc.scrollDown()) break;
            scrolled = true;
            sleep(700);                       // 等滑动动画和惰性渲染
            if (!svc.emptyFields().isEmpty()) break;   // 空框已经露出来了
            if (!svc.hasEmptyFieldsBelow()) break;
        }
        logAsk(stamp() + " 填空：下面还有空着的输入框，"
                + (scrolled ? "往下滑，接着填" : "想往下滑但没滑动"));
        if (!scrolled) return false;
        lastScreenKey = "";       // 滑过之后是新的一屏，别拿旧指纹去比
        scrollTries = 0;
        requeueAsk(1200);         // 等滑动动画和惰性渲染
        return true;
    }

    /**
     * 选项只露出前几行（比如 C、D 被压在下沿）时，小幅往下滑一点，让整组露出来。
     * <p>
     * 这里**不能**整屏滑：整组贴在屏幕底部时，滑一整屏会把 A、B 送出去，只剩 C、D，
     * 又变成「没露全」，永远点不了。只滑约屏高的 17%，刚够把压在下面的行带上来。
     *
     * @return 真的滑了并排好了重读 —— 调用方到此为止，这一屏一个字母都别点
     */
    private boolean scrollToShowOptions(boolean up) {
        if (!Prefs.autoScroll()) return false;
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null) return false;
        // 键盘没确认收掉**绝对不能**滑：滑动手势会落在键盘上，轻的滑不动，
        // 重的会触发键盘输入 / 滑行输入把刚填好的答案改掉（真机上多打出一个「5」）。
        if (svc.isImeVisible()) {
            svc.hideIme(500);
            if (svc.isImeVisible()) {
                logAsk(stamp() + " 键盘还盖着屏幕，先不滑动（免得手势打到键盘上改错答案）");
                return false;
            }
        }
        boolean scrolled = up ? svc.scrollUpSmall() : svc.scrollDownSmall();
        logAsk(stamp() + " 有选项没完整显示："
                + (scrolled ? (up ? "往上滑一点" : "往下滑一点") + "，看全了再点"
                            : "想滑动但没滑动"));
        if (!scrolled) return false;
        lastScreenKey = "";       // 坐标全变了，别拿旧指纹去比
        scrollTries = 0;
        requeueAsk(1400);         // 等滑动动画
        return true;
    }

    /**
     * 一屏多题：从上到下一题一组，第 k 条答案只找第 k 组选项去点。
     * <p>
     * 对位靠模型给的题号：给了就按题号找组，某条题号在这屏找不到对应组就单独跳过；
     * 一条题号都没有时，只有「条数正好等于组数」才按顺序一一对应，否则**一个都不点**
     * —— 点错组等于把第 2 题的答案点到第 1 题上，那还不如不点。
     */
    private void doMulti(LogRecord rec, AnswerParser.Parsed p, int groups) {
        List<AnswerParser.Parsed> items = p.items;
        if (items.isEmpty()) {
            items = new ArrayList<>();
            items.add(p);                       // 认得多组但模型只给了一条
        }

        // 展示用：「1.A 2.CD 3.填空」
        StringBuilder show = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            AnswerParser.Parsed it = items.get(i);
            String a = it.answer.isEmpty() ? (it.fill.isEmpty() ? "" : "填空") : it.answer;
            if (a.isEmpty()) continue;
            if (show.length() > 0) show.append(' ');
            show.append(i + 1).append('.').append(a);
        }
        rec.answer = show.length() == 0 ? "?" : show.toString();
        rec.explanation = joinExplain(items, groups);

        logAsk("    → 一屏认到 " + groups + " 组选项，模型给了 " + items.size() + " 条答案："
                + rec.answer);

        // 对位：模型给了题号就按题号找。题号可能是「屏幕上从上往下第几题」，也可能是
        // 「题面印的那个号」—— 翻到第 6、7 题那一屏就分家了，AnswerParser.mapGroups
        // 两种都算一遍，哪种每一题都落得进范围就用哪种，都对不上就一条都不点。
        int[] groupOf = AnswerParser.mapGroups(items, groups, rec.question);
        int limit = 0;
        for (int g : groupOf) if (g >= 0) limit++;
        int base = AnswerParser.groupBase(items, groups, rec.question);
        if (AnswerParser.matchByStem(items, groups, rec.question) != null) {
            logAsk("    → 这屏按模型抄回来的题干对上组（题号对不上也不影响）");
        } else if (base < 0) {
            logAsk("    → 这屏按每条题干上印的题号对组（最上面那题题干滚出去了也没关系）");
        } else if (base > 0) {
            logAsk("    → 这屏的题号是从第 " + (base + 1) + " 题开始印的，按「题号 − "
                    + (base + 1) + " + 1」对组");
        } else if (limit == 0 && items.size() > 0) {
            StringBuilder ns = new StringBuilder();
            for (AnswerParser.Parsed it : items) {
                if (ns.length() > 0) ns.append('/');
                ns.append(it.n);
            }
            logAsk("    → 这屏认出 " + groups + " 组选项，模型的题号是 " + ns
                    + "，对不上位 —— 这次一个都不点（宁可漏点，不能串题）");
        }

        // 混排页的兜底：填空题条目没有选项组，不该占组号。模型没抄题干、上面按题号
        // 又一条都对不上时，按「有字母答案的条目」在屏幕上的先后依次对组 ——
        // 只有条数和组数**正好相等**才敢这么对，少一条就退回一个都不点。
        int letterItems = 0, mappedItems = 0;
        for (int i = 0; i < items.size(); i++) {
            AnswerParser.Parsed it = items.get(i);
            if (it.answer == null || it.answer.isEmpty() || "?".equals(it.answer)) continue;
            letterItems++;
            if (groupOf[i] >= 0) mappedItems++;
        }
        if (letterItems > 0 && mappedItems == 0 && letterItems == groups) {
            int gi = 0;
            for (int i = 0; i < items.size(); i++) {
                AnswerParser.Parsed it = items.get(i);
                if (it.answer == null || it.answer.isEmpty() || "?".equals(it.answer)) continue;
                groupOf[i] = gi++;
            }
            logAsk("    → 题号对不上组，按「有字母答案的题」从上往下依次对组（填空条目跳过）");
        }

        // 模型抄的题干属于**屏幕下方**那道题（只露出题干、选项还在屏幕外）。
        // 这一条对不上任何一组选项是对的，别点 —— 往下滑一点去看全它再答。
        boolean belowQuestion = false;
        for (int i = 0; i < items.size(); i++) {
            AnswerParser.Parsed it = items.get(i);
            if (groupOf[i] >= 0) continue;
            if (it.answer == null || it.answer.isEmpty() || "?".equals(it.answer)) continue;
            if (AnswerParser.stemBelowOptions(it.q, rec.question)) belowQuestion = true;
        }

        int total = 0;                       // 这一屏真点上了几个（下面收尾要用）
        int filledCount = 0;                 // 这一屏真填进去几个空
        boolean optionsCut = false;          // 有题选项只露出前几行：这题一个字母都不点
        boolean optionsCutTop = false;       // 缺的是上面几行（要往上滑），不是下面
        List<BlankSlot> allSlots = blankSlotsOf(p);
        List<String> allBlanks = blanksOf(p);
        if (Prefs.autoClick()) {
            awaitSettled();          // 一屏多题更怕飘：点错一行就是串题
            String firstFill = "";
            for (int i = 0; i < items.size(); i++) {
                AnswerParser.Parsed it = items.get(i);
                int g = groupOf[i];
                // 填空条目没有选项组（g < 0）也要处理：上面已经按顺序填进空格了，
                // 这里只需要在日志里说一声，别让选项组对不上就把它整条丢掉。
                if (it.answer.isEmpty()) {
                    if (!it.fill.isEmpty() || !it.blanks.isEmpty()) {
                        if (firstFill.isEmpty()) firstFill = it.fill;
                        logAsk("    → 第 " + (i + 1) + " 题是填空"
                                + (g >= 0 ? "（第 " + (g + 1) + " 组选项）" : "")
                                + "，没有可点的字母"
                                + (Prefs.autoFill() && filledCount > 0 ? "（正文已填进空格）" : ""));
                    } else {
                        logAsk("    → 第 " + (i + 1) + " 题模型没给答案，跳过");
                    }
                    continue;
                }
                if (g < 0) continue;     // 选择题对不上选项组：宁可漏点，不能串题
                // 选项没完整露在屏幕里就**一个都不点**（用户明确要求）。光看组里剩下的
                // 行数会误判：C、D 被压在下沿时 optionBars() 早把它们筛掉了，组里只剩
                // A、B，看起来「这题就两个选项」。optionGroupFullyVisible() 会顺着最后
                // 一行往下找，看看下一个标签（B 后面的 C）是不是还在屏幕下面。
                // 已经点过的题（连答重读同一屏）不再拦：没有要点的字母就没必要为它滚动。
                String stem0 = stemKey(it.q.isEmpty() ? askKey : it.q);
                String allowed0 = AnswerParser.allowedLetters(Prefs.labels());
                boolean needClick = false;
                for (int k = 0; k < it.answer.length(); k++) {
                    String L = String.valueOf(it.answer.charAt(k));
                    if (allowed0.contains(L) && it.answer.indexOf(L) == k
                            && !alreadyClickedStem(stem0, L)) {
                        needClick = true;
                        break;
                    }
                }
                QuizAccessibilityService svcOpt = QuizAccessibilityService.get();
                if (needClick && svcOpt != null) {
                    int vis = svcOpt.optionGroupVisibility(g);
                    if (vis != QuizAccessibilityService.OPT_FULL) {
                        boolean top = vis == QuizAccessibilityService.OPT_CUT_TOP;
                        logAsk("    → 第 " + (i + 1) + " 题（第 " + (g + 1) + " 组选项）"
                                + (top ? "上面几行没露出来" : "下面几行没露出来")
                                + "，这题先不点，滑" + (top ? "上" : "下") + "去看全再答");
                        optionsCut = true;
                        optionsCutTop = top;
                        continue;
                    }
                }
                // 一个字母一个字母地点，好知道具体哪个没落下去。
                // **只把「无障碍和手势都没点上」的字母记下来补点**，绝不拿「读回来还是
                // 没选中」当判据：WebView 里的 div 选项压根不报 checked/selected，读出来
                // 永远是 false，那样连答时会反复补点，把刚选上的又点掉 —— 正是
                // 「ACD 来回选最后随机」那个老毛病。宁可漏补，不能补掉。
                int c = 0;
                List<String> missed = new ArrayList<>();
                // 这道题点过哪些字母：按「题干指纹」记，不按整屏指纹 —— 一屏多题时
                // 往下滑一屏，第一条题滚出屏幕，整屏指纹就变了，而第 2 题还在原地。
                // 不记住的话重读这一屏会把同一道题的同一个字母再点一下，等于取消选择
                // （测试页上「B 先变绿、下一拍又变回去」就是这么来的）。
                String stem = stemKey(it.q.isEmpty() ? askKey : it.q);
                String allowed = AnswerParser.allowedLetters(Prefs.labels());
                for (int k = 0; k < it.answer.length(); k++) {
                    if (paused) break;
                    String L = String.valueOf(it.answer.charAt(k));
                    if (!allowed.contains(L) || it.answer.indexOf(L) != k) continue;
                    if (alreadyClickedStem(stem, L)) {
                        logAsk("    → 第 " + (i + 1) + " 题的「" + L + "」已经点过，跳过（再点一次等于取消）");
                        c++;
                        continue;
                    }
                    if (clickOne(L, g)) {
                        c++;
                        rememberClickStem(stem, L);
                    } else {
                        missed.add(L);
                    }
                    sleep(Prefs.clickGapMs());
                }
                total += c;
                logAsk("    → 第 " + (i + 1) + " 题（第 " + (g + 1) + " 组选项）答案=" + it.answer
                        + " 点了 " + c + " 个"
                        + (missed.isEmpty() ? "" : "（" + missed.size() + " 个没落下去）"));
                // 题与题之间多歇一下：点完上一题的选项，页面可能还要画一下
                sleep(Math.max(Prefs.clickGapMs(), 300));
            }
            rec.clicked = total;

            // 一屏里混了填空题：剪贴板只放得下一条，先放第一条当兜底 ——
            // 自动填成功的那些不用管，填不进去的还能手动粘。
            if (!firstFill.isEmpty() && Prefs.fillCopy()) {
                copyToClipboard(this, firstFill);
                logAsk("    → 一屏里还有填空题，正文（" + firstFill.length()
                        + " 字）已复制到剪贴板"
                        + (Prefs.autoFill() && filledCount > 0 ? "（能自动填的已填好）" : "，自己粘一下"));
            }
            if (items.size() != groups) {
                logAsk("    → 这屏认出 " + groups + " 组选项、模型给了 " + items.size()
                        + " 条答案，按题号对上了 " + limit + " 条");
            }
            if (limit == 0) logAsk("    → 一条都没点");
        } else {
            rec.clicked = -1;
            logAsk("    → 自动点击关着");
        }

        // 填空题放在点击之后：粘贴兜底会拉起输入法、页面会滚，先点完再填，坐标不会飘。
        // 逐空答案按题目在屏幕上的先后顺序拼成一条，统一往这一屏空着的输入框里填。
        QuizAccessibilityService svcNow = QuizAccessibilityService.get();
        if (Prefs.autoFill() && !allBlanks.isEmpty()) {
            filledCount = fillBlanks(askKey, allSlots);
            logAsk("    → 这一屏有 " + allBlanks.size() + " 个空的答案，按顺序填进去 "
                    + filledCount + " 个");
            if (filledCount > 0 && svcNow != null
                    && svcNow.hideIme(600)) {
                logAsk("    → 收掉了自动弹出来的输入法键盘");
            }
        }

        if (optionsCut) {
            // 有题选项没露全：先小幅下滑让整组露出来，滑完重读这一屏再点。
            // 一屏最多试三次 —— 选项被浏览器底栏压住、页面已经到底时再怎么滑也露不全，
            // 那就宁可不答，也不能没看全就点。
            if (optScrollTries < 3 && scrollToShowOptions(optionsCutTop)) {
                optScrollTries++;
                return;
            }
            logAsk(stamp() + " 选项一直显示不全（滑了 " + optScrollTries
                    + " 次），这题不点了，也不点「下一题」");
            notifyAnswer(rec.answer, rec.explanation);
            showBubble(rec.answer, rec.explanation);
            LogStore.add(this, rec);
            restoreLabel();
            halt("这一屏有题的选项一直没完整显示出来（只露出前几行），没有点它 —— "
                    + "把页面往上/往下挪一下，让选项全露出来，再点悬浮球继续");
            return;
        }

        // 这一屏没有一条答案能落进完整的选项组：要么模型答的是下面那道题（它的选项
        // 还在屏幕外），要么选项行一条都没凑成一组。别硬点 —— 往下滑一点让选项
        // 露出来，滑完重读这一屏再答。
        if (belowQuestion || (groups == 0 && svcNow != null && svcNow.hasAnyOptionRow())) {
            if (optScrollTries < 3 && scrollToShowOptions(false)) {
                optScrollTries++;
                return;
            }
            logAsk(stamp() + " 答案落在屏幕下面还没露出来的选项上（滑了 "
                    + optScrollTries + " 次也没露全），这一屏不点");
            notifyAnswer(rec.answer, rec.explanation);
            showBubble(rec.answer, rec.explanation);
            LogStore.add(this, rec);
            restoreLabel();
            halt("这一屏还有题的选项在屏幕下面没露出来 —— 往下挪一下让选项全部露出来，"
                    + "再点悬浮球继续");
            return;
        }
        optScrollTries = 0;      // 这屏看全了，下次「露不全」重新数

        notifyAnswer(rec.answer, rec.explanation);
        showBubble(rec.answer, rec.explanation);
        speak(rec.answer, rec.explanation);
        paintBall(BALL_RUN);
        LogStore.add(this, rec);
        restoreLabel();

        // 这一屏真点上了才算答过；一个字母都没点上（模型全给 "?"、题号对不上位、
        // 或者点击全落空）就不算 —— 不能让连答顺手点「下一题」跳过去。
        boolean anyAnswered = false, anyFill = false;
        for (AnswerParser.Parsed it : items) {
            if (!it.fill.isEmpty()) anyFill = true;
            if (it.answer != null && !it.answer.isEmpty() && !"?".equals(it.answer)) anyAnswered = true;
        }
        // 这一屏的填空是不是全填上了。没填完就别点「下一题」：剩下的空多半在屏幕下面。
        // 屏幕上已经一个空输入框都不剩时也算填完 —— 往下滑过一屏之后，上面那几句的答案
        // 还在模型回复里，但对应的框已经滚出屏幕了，再死等「个数对上」只会原地打转。
        int needBlanks = 0;
        for (String b : allBlanks) if (!b.isEmpty()) needBlanks++;
        boolean noEmptyLeft = true;
        if (svcNow != null) noEmptyLeft = svcNow.emptyFields().isEmpty();
        boolean fillsComplete = !Prefs.autoFill() || needBlanks == 0
                || filledCount >= needBlanks || noEmptyLeft;

        // 填空只在「真填进去」或者「退回剪贴板」时才算出过答案：模型认成填空、
        // 页面却一个空输入框都没有时，不能让连答顺手点「下一题」跳过去。
        lastRoundAnswered = (total > 0 || filledCount > 0
                || (!Prefs.autoFill() && anyFill && Prefs.fillCopy())) && fillsComplete;
        // 「模型明说答不了」才算 blank（要往下滑找完整的题）；点击落空那种是另一回事。
        // 而且得是**它真的一题一题看过**：items 是空的说明这轮压根没读成（页面还没渲染完、
        // 模型超时、屏幕上只有浏览器工具栏），什么都没答出来就往下滑，等于把最上面那题
        // 直接划过去 —— 实测就是这么把第 1 题漏掉的（2026-10-11 09:44，七题页第一屏）。
        lastRoundBlank = (!anyAnswered && !anyFill && !items.isEmpty())
                || (Prefs.autoFill() && !fillsComplete && needBlanks > 0);

        // 填空填完了、下面还有空框：先往下滑接着填，别点「下一题」把剩下的空跳过去。
        if (Prefs.autoFill() && needBlanks > 0 && fillsComplete && scrollToMoreBlanks()) return;
        if (Prefs.autoNext() && fillsComplete) {
            scheduleNextClick((long) (Prefs.nextDelaySec() * 1000));
        }
        failStreak = 0;
        scheduleLoop();
    }

    /** 多题页的解析合成一段：每行「第 k 题：…」，行数对不上就照实说。 */
    private static String joinExplain(List<AnswerParser.Parsed> items, int groups) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < items.size(); i++) {
            String x = items.get(i).explanation;
            if (x == null || x.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("第").append(i + 1).append("题：").append(x.trim());
            any = true;
        }
        if (!any) return "";
        if (items.size() != groups) {
            sb.append("\n（这屏认出 ").append(groups).append(" 组选项，模型给了 ")
                    .append(items.size()).append(" 条答案，数目对不上）");
        }
        return sb.toString();
    }

    /**
     * 单选题里的填空题：优先按顺序自动填进这一屏空着的输入框；填不进去再退回
     * 「复制到剪贴板 → 等一会儿 / 暂停」那条老路。
     * <p>
     * 一道大题里有好几个空的时候，{@link AnswerParser.Parsed#blanks} 是按题面先后排好的，
     * 屏幕上的输入框也按从上到下、从左到右排好（见 {@code emptyFields()}），两边第 k 个
     * 一对，答案就不会串空。**只填看得见的空**：滚到屏幕外的留给往下滑之后的那一轮。
     */
    private void doFill(LogRecord rec, AnswerParser.Parsed p) {
        List<BlankSlot> slots = blankSlotsOf(p);
        if (slots.isEmpty() && !rec.fill.isEmpty()) {
            for (String b : splitFill(rec.fill)) slots.add(new BlankSlot(stemKey(p.q), b));
        }
        List<String> blanks = new ArrayList<>();
        for (BlankSlot s : slots) blanks.add(s.answer);

        int filled = 0;
        if (Prefs.autoFill() && !slots.isEmpty()) {
            filled = fillBlanks(askKey, slots);
            // WebView 填完字常把键盘弹出来盖住下半屏，收掉再接着读（见 hideIme）。
            QuizAccessibilityService s = QuizAccessibilityService.get();
            if (filled > 0 && s != null && s.hideIme(600)) {
                logAsk("    → 收掉了自动弹出来的输入法键盘");
            }
        }

        // 剪贴板照旧留一份：自动填不上时这就是兜底，填上了也不碍事。
        if (Prefs.fillCopy() && !rec.fill.isEmpty()) {
            copyToClipboard(this, rec.fill);
        }

        int clicked = -1;
        if (Prefs.autoClick() && !rec.answer.isEmpty() && !"?".equals(rec.answer)) {
            clicked = clickLetters(rec.answer);
            logAsk("    → 填空题：候选选项答案=" + rec.answer + "，点了 " + clicked + " 个");
        }
        rec.clicked = clicked;

        String show = rec.fill.length() > 200 ? rec.fill.substring(0, 200) + "…" : rec.fill;
        notifyAnswer("填空", show);
        showBubble("填空", show);
        speak(rec.answer, rec.explanation);
        paintBall(BALL_RUN);
        LogStore.add(this, rec);
        restoreLabel();
        failStreak = 0;

        int need = 0;
        for (String b : blanks) if (!b.isEmpty()) need++;
        boolean complete = filled >= need;
        if (!complete && Prefs.autoFill()) {
            // 同 doMulti：屏幕上已经一个空框都不剩，就没什么能再填的了，
            // 多出来的答案对的是已经滚出屏幕的空，别为它卡着不翻页。
            QuizAccessibilityService svcNow = QuizAccessibilityService.get();
            if (svcNow != null && svcNow.emptyFields().isEmpty()) complete = true;
        }

        if (filled > 0) {
            logAsk("    → 填空题：" + (complete
                    ? "按顺序填进 " + filled + " 个空"
                    : "填进 " + filled + "/" + need + " 个空，还有没露出来的")
                    + (Prefs.fillCopy() ? "（正文也复制到剪贴板了）" : ""));
            // 还有空没填上：多半在屏幕下面，让连答往下滑，别急着点「下一题」。
            lastRoundAnswered = complete;
            lastRoundBlank = !complete;
            // 这一屏填完了、下面还有空框：自己先滑一屏接着填（见 scrollToMoreBlanks）。
            if (complete && scrollToMoreBlanks()) return;
            if (complete && Prefs.autoNext()) {
                scheduleNextClick((long) (Prefs.nextDelaySec() * 1000));
            }
            scheduleLoop();
            return;
        }

        // 一个空都没填进去：页面没有输入框、无障碍读不到，或者全被键盘挡住。
        if (Prefs.fillCopy()) {
            logAsk("    → 填空题：正文 " + rec.fill.length() + " 字已复制到剪贴板\n"
                    + rec.fill);
            lastRoundAnswered = true;    // 正文出来了，交给用户粘贴，也算这一屏处理过了
            lastRoundBlank = false;
            int waitSec = Prefs.fillWaitSec();
            if (waitSec <= 0) {
                pause("填空题正文已复制");
                return;
            }
            logAsk("    → 填空题：等 " + waitSec + " 秒后接着答");
            requeueAsk(waitSec * 1000L);
            return;
        }

        // 既没填进去、也没开复制：可能输入框在屏幕下面 —— 按「往下滑找完整的题」处理。
        logAsk("    → 填空题：这一屏没找到空着的输入框"
                + (blanks.isEmpty() ? "（模型也没给逐空答案）" : ""));
        lastRoundAnswered = false;
        lastRoundBlank = !blanks.isEmpty();
        scheduleLoop();
    }

    private void finish(LogRecord rec, long t0, String msg) {
        rec.costMs = SystemClock.uptimeMillis() - t0;
        if (rec.error.isEmpty()) rec.error = msg;
        LogStore.add(this, rec);
        notifyAnswer("—", msg);
        paintBall(BALL_ERROR);       // 这一轮没成，球变红给个一眼能看见的提示
        restoreLabel();
        toast(msg);
        // 这一轮失败了（取词失败 / 大模型超时断网 / 解析不出来），**这一屏还没答**：
        // 连答绝不许顺手点「下一题」——用户报过「请求失败也直接翻页跳过一题」。
        lastRoundAnswered = false;
        lastRoundBlank = false;      // 取词/请求失败跟屏幕内容无关，不能拿它当「往下滑」的理由
        // 这一轮失败了，但循环不能就这么停在这儿：网络抖一下、页面刚好在翻、
        // 或者撞上一个读不出字的页面，都只是这一轮的事，下一轮该接着答。
        failStreak++;
        scheduleLoop();
    }

    // ---------------- 截图 ----------------

    private Bitmap screenshot() {
        int src = Prefs.shotSource();
        boolean wantShizuku = (src == 0 || src == 1);
        boolean wantProjection = (src == 0 || src == 2);

        if (wantShizuku && ShizukuShell.granted()) {
            setOverlayVisible(false);
            try {
                byte[] png = ShizukuShell.raw("screencap -p | base64");
                byte[] dec = Base64.decode(cleanB64(png), Base64.DEFAULT);
                Bitmap b = BitmapFactory.decodeByteArray(dec, 0, dec.length);
                if (b != null) {
                    shotSrcName = "Shizuku";
                    return b;
                }
            } catch (Throwable t) {
                Log.w(TAG, "shizuku screencap", t);
            } finally {
                setOverlayVisible(true);
            }
        }
        if (wantProjection && imageReader != null) {
            setOverlayVisible(false);
            try {
                Bitmap b = grabProjection();
                if (b != null) {
                    shotSrcName = "屏幕录制";
                    return b;
                }
            } finally {
                setOverlayVisible(true);
            }
        }

        // 最后一道兜底：让无障碍服务自己截（API 30+）。它不要 Shizuku、不要屏幕录制授权，
        // 只要服务连着就一直在 —— 实测屏幕录制的 MediaProjection 被系统掐掉之后，上面两条
        // 全拿不到图，连着九十秒每秒白报一次「拿不到截图」，学习通那种选项正文读不到的页面
        // 就彻底没法答了。
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc != null) {
            setOverlayVisible(false);
            sleep(150);          // 悬浮球是应用窗口（TYPE_SYSTEM），不先撤掉就会被拍进图里挡住题目
            try {
                Bitmap b = svc.takeScreenshotNow(2500);
                if (b != null) shotSrcName = "无障碍截屏";
                return b;
            } finally {
                setOverlayVisible(true);
            }
        }
        return null;
    }

    /**
     * 目标 App 设了 {@code FLAG_SECURE}（银行、考试、部分题库）时，系统会在
     * SurfaceFlinger 层把这一层内容从截图里剔除，得到一整块纯色（一般是全黑）。
     *
     * 注意：MediaProjection、Shizuku 的 screencap、甚至 adb 全都拦，root 也绕不过去，
     * 唯一例外是给目标 App 打 LSPosed 模块把 FLAG_SECURE 摘掉。
     * 所以这里认出它，只为给用户一句有用的提示，而不是干巴巴的「没读到文字」。
     * 真正能用的路子是无障碍节点树——读文字不走截图，不受这个标志影响。
     */
    private static boolean looksBlocked(Bitmap bmp) {
        if (bmp == null) return false;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 16 || h < 16) return false;

        // 只扫中间这条带。FLAG_SECURE 抹掉的是「目标 App 那一层」，
        // 状态栏和导航栏是别的窗口，不会被抹，所以它们照旧出现在截图里——
        // 拿整屏去判「纯色」永远判不出来。
        int y0 = h * 2 / 10, rows = h * 6 / 10;
        if (rows < 8) return false;

        // 必须整幅取回来逐点比：抽样会漏掉稀疏的文字，
        // 也会因为采样点全落在背景色上，把夜间模式的深色界面误判成被禁截屏。
        int[] px = new int[w * rows];
        try {
            bmp.getPixels(px, 0, w, 0, y0, w, rows);
        } catch (Throwable t) {
            return false;
        }

        int modal = px[0] & 0x00FFFFFF;
        int diff = 0, limit = px.length / 10000;
        for (int i = 1; i < px.length; i++) {
            if ((px[i] & 0x00FFFFFF) != modal && ++diff > limit) return false;
        }
        // 纯色还不够：必须同时很暗。全白的空白页也是纯色，那不是被禁截屏。
        int r = (modal >> 16) & 0xFF, g = (modal >> 8) & 0xFF, b = modal & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 60;
    }

    private static String cleanB64(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        return s.replaceAll("\\s", "");
    }

    private List<UiDump.Node> dumpNodes() {
        try {
            String xml = ShizukuShell.str(
                    "uiautomator dump /sdcard/.qh_ui.xml >/dev/null 2>&1; cat /sdcard/.qh_ui.xml");
            return UiDump.parse(xml);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    // ---------------- 点击 ----------------

    private int clickLetters(String answer) {
        return clickLetters(answer, -1);
    }

    /**
     * 把答案里的字母一个个点掉。
     *
     * @param group 一屏多题时表示「第几组选项」（0 起）；&lt;0 表示全屏找（一屏一题的老路）
     */
    private int clickLetters(String answer, int group) {
        // 用户长按暂停了就别再点：这一串点下去，人已经到桌面上了。
        if (paused) return 0;
        List<String> letters = new ArrayList<>();
        String allowed = AnswerParser.allowedLetters(Prefs.labels());
        for (int i = 0; i < answer.length(); i++) {
            String c = String.valueOf(answer.charAt(i));
            if (allowed.contains(c) && !letters.contains(c)) letters.add(c);
        }
        int done = 0;
        for (String L : letters) {
            // 同一道题上点过的字母不再点第二下。多选题里再点一次不是「再选一次」，是**取消** ——
            // 用户报的「一直在反复按同一个答案」就是这么一圈圈来回的（重答→点掉→再点上）。
            if (alreadyClicked(L)) {
                logAsk("    ·「" + L + "」这道题已经点过了，跳过（再点一次等于取消选择）");
                done++;          // 算它处理过了：不然 doAsk 会以为「一个都没点中」再点一轮
                continue;
            }
            if (clickOne(L, group)) {
                done++;
                if (!askKey.isEmpty()) {
                    if (!askKey.equals(clickedLettersKey)) {
                        clickedLettersKey = askKey;
                        clickedLetters.clear();
                    }
                    clickedLetters.add(L);
                    // 落盘一份：重开 App / 再点一次「开始」时，内存里那份就没了，
                    // 而页面上的勾还在 —— 照着它跳过，别把已经选好的答案点掉。
                    // 存最近好几屏，翻回前面某题时也认得出。
                    Prefs.rememberClicks(askKey, String.join("", clickedLetters));
                }
            }
            sleep(Prefs.clickGapMs());
        }
        return done;
    }

    /**
     * 这道题上的这个字母，是不是已经点过了。
     * <p>
     * 先看内存里那份（连答一轮一轮之间用），再看**上一次运行留下的那份**（存在设置里）：
     * 用户关掉再打开、或者再点一下「开始」，内存那份就没了，而页面上的勾还好好在着。
     * 这时候再点一下正好是取消，用户看到的就成了「一按开始，刚选上的全没了」。
     * 题干没变，就不该再点 —— 指纹里已经挖掉了选项那一坨，点选怎么变都不影响它
     * （见 stemText）。
     */
    private boolean alreadyClicked(String letter) {
        if (askKey.isEmpty()) return false;
        if (askKey.equals(clickedLettersKey) && clickedLetters.contains(letter)) return true;
        // 落盘那份留了最近好几屏（见 Prefs.rememberClicks）：中途翻回上一题、或者用户点了
        // 「答题卡」跳回前面某题，这一屏的「点过哪些」还在。只认最近一屏的话，这时就查不到，
        // 于是对着已经选中的选项再点一遍 —— 多选题被点没就是这么来的。
        return Prefs.clickedBefore(askKey, letter);
    }

    /** 题干指纹：去掉空白和标点，模型两次抄回来的题干措辞略有出入也认得出是同一题。 */
    private static String stemKey(String stem) {
        return stem == null ? "" : stem.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** 这道题的这个字母是不是已经点过了（一屏多题版，见 {@link #clickedByStem}）。 */
    private boolean alreadyClickedStem(String stem, String letter) {
        if (stem.isEmpty() || letter.isEmpty()) return false;
        Set<String> seen = clickedByStem.get(stem);
        if (seen != null && seen.contains(letter)) return true;
        return Prefs.clickedBefore(stem, letter);
    }

    private void rememberClickStem(String stem, String letter) {
        if (stem.isEmpty() || letter.isEmpty()) return;
        Set<String> seen = clickedByStem.get(stem);
        if (seen == null) {
            seen = new LinkedHashSet<>();
            clickedByStem.put(stem, seen);
        }
        seen.add(letter);
        // 也落盘：重开 App / 再点一次「开始」时，页面上的勾还在，不能被当成没点过
        Prefs.rememberClicks(stem, String.join("", seen));
    }

    /**
     * 点下去之前先等页面站住：选项的位置指纹连着两次读到一样才动手。
     * <p>
     * 页面刚翻、或者刚滑过去的那一两秒里，WebView 还在做惯性滚动，每一帧读到的选项矩形
     * 都不一样。拿着「这一帧」的坐标落手势，等触摸真送到屏幕上那一行早滑走了 —— 现象就是
     * 「点了 6 个，一个都没落下去」，隔几十秒单独点同一个位置却又好好的。
     * <p>
     * 最多等 1 秒（4 × 250ms），等不到就照现状点：连答那套本来就有重试兜底，
     * 干等下去只会把连答节奏拖垮。
     */
    private void awaitSettled() {
        dismissAnswerNotif();
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null) return;
        String labels = AnswerParser.allowedLetters(Prefs.labels());
        if (labels.isEmpty()) return;
        String prev = svc.layoutSignature(labels);
        if (prev.isEmpty()) return;          // 一屏一个选项都没读到，等也白等
        for (int i = 0; i < 4; i++) {
            sleep(250);
            String now = svc.layoutSignature(labels);
            if (now.isEmpty()) return;
            if (now.equals(prev)) return;    // 站住了
            prev = now;
        }
    }

    /**
     * 点之前把自己那条答案通知收掉。
     * <p>
     * 它是 {@code IMPORTANCE_HIGH} 的，落下来就是**能接收触摸的横幅**，压在屏幕顶上那一带。
     * 选项行要是正好排在那儿，触摸就被横幅接走了，选项等于没点 —— 用户说的「弹出来的通知
     * 会影响点击答案」就是这个。收掉不影响别的：这一轮点完会重新弹一条。
     */
    private void dismissAnswerNotif() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ANSWER);
        } catch (Throwable ignored) {
        }
    }

    private boolean clickOne(String label, int group) {
        int pref = Prefs.clickSource();
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        boolean mayTap = pref == 0 || pref == 2 || pref == 3;

        // 1) 无障碍节点点击
        if ((pref == 0 || pref == 1) && svc != null) {
            if (svc.clickOption(label, group)) return true;
        }
        if (!mayTap) return false;

        // 找坐标：无障碍节点 > 按行对齐的几何 > uiautomator（慢，最后才用） / OCR 行。
        // 「按行对齐」那份要排在 OCR 前面：无障碍漏掉整行的选项（实测第 10 题的 A、C）
        // 在无障碍树里按标签找不到，OCR 那枚小字母也常常没认出来 —— 而这份几何是拿
        // 两边的标签合起来算的，正是为这种情况留的。
        Rect r = svc == null ? null : svc.boundsOfOption(label, group);
        if (r == null) r = alignedBounds(label, group);
        // 指定了组就别再退回 OCR / uiautomator —— 那两条路只能给出「全屏第一个 A」，
        // 正好是「几条答案全点到第一题上」的老毛病。组里没有这个标签就如实说没有。
        if (group < 0) {
            if (r == null) r = OcrEngine.findLabel(lastLines, label);
            if (r == null && ShizukuShell.granted()) r = UiDump.findLabel(dumpNodes(), label);
        }
        if (r == null) return false;

        // 2) Shizuku input tap
        if ((pref == 0 || pref == 2) && ShizukuShell.granted()) {
            if (ShizukuShell.tap(r.centerX(), r.centerY())) return true;
        }
        // 3) 手势
        if ((pref == 0 || pref == 3) && svc != null) {
            if (svc.tapAt(r.centerX(), r.centerY())) return true;
        }
        return false;
    }

    /**
     * 「翻页」关键词里混着几个**不可逆**的：最后一页那颗按钮也叫「提交」。
     * 连答一路点下去，点到它就是替用户交卷了 —— 交上去收不回来，用户没让干这个。
     * 所以这几个词一律不点，翻不动就停在那儿（见 doLoopStep 末尾那条停机）。
     */
    private static final String[] SUBMIT_WORDS = {"提交", "交卷", "递交", "上传答案"};

    /** 这一屏是不是交卷页（有「提交」这一类按钮）。 */
    private boolean looksLikeSubmitPage() {
        List<String> subs = new ArrayList<>();
        java.util.Collections.addAll(subs, SUBMIT_WORDS);
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        try {
            if (svc != null && svc.boundsOfKeyword(subs) != null) return true;
        } catch (Throwable ignored) {
        }
        return OcrEngine.findKeyword(lastLines, subs) != null;
    }

    private boolean clickNextKeyword() {
        List<String> kws = OcrEngine.keywordList(Prefs.nextKeywords());
        if (kws.isEmpty()) return false;
        // 交卷那类词剔掉，剩下的才是「翻页」
        for (String w : SUBMIT_WORDS) kws.remove(w);
        if (kws.isEmpty()) return false;
        int pref = Prefs.clickSource();
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        boolean mayTap = pref == 0 || pref == 2 || pref == 3;

        // 按钮贴着底边时先挪出来：底下常压着浏览器工具栏 / 手势条，
        // 直接按中心落触摸会点空，页面一直不翻。
        if (svc != null) svc.revealKeyword(kws);

        if ((pref == 0 || pref == 1) && svc != null) {
            if (svc.clickKeyword(kws)) return true;
        }
        if (!mayTap) return false;

        Rect r = svc == null ? null : svc.boundsOfKeyword(kws);
        if (r == null) r = OcrEngine.findKeyword(lastLines, kws);
        if (r == null && ShizukuShell.granted()) r = UiDump.findKeyword(dumpNodes(), kws);
        if (r == null) return false;

        if ((pref == 0 || pref == 2) && ShizukuShell.granted()) {
            if (ShizukuShell.tap(r.centerX(), r.centerY())) return true;
        }
        if ((pref == 0 || pref == 3) && svc != null) {
            if (svc.tapAt(r.centerX(), r.centerY())) return true;
        }
        return false;
    }

    /** 排一次「点下一题」。只此一条链，排之前先把旧的撤掉（见 {@link #nextPending}）。 */
    private void scheduleNextClick(long delayMs) {
        nextPending = true;
        ui.removeCallbacks(pendingNext);
        ui.postDelayed(pendingNext, Math.max(200, delayMs));
    }

    /** 撤掉排着队的「点下一题」，并清掉那个标志。 */
    private void cancelNextClick() {
        nextPending = false;
        ui.removeCallbacks(pendingNext);
    }

    private void doClickNext() {
        nextPending = false;
        boolean ok = clickNextKeyword();
        if (ok) return;
        // 连答开着的时候**不弹这条**：它会一直在屏幕正中间飘着，正好压在选项行上面，
        // 而我们每隔几秒就要往那些行落一次触摸。连答本来就会自己重试，日志里也有
        // 「「下一题」也没找到，继续等」，不缺这一句提示。
        if (Prefs.autoLoop()) {
            logAsk(stamp() + " 没找到「下一题」按钮（连答开着，不弹提示免得挡住选项）");
            return;
        }
        toast("没找到「下一题」按钮，可在设置里补充关键词");
    }

    /**
     * 只留一条连答回调在排队。<b>所有</b>排下一轮的地方都得走这里 ——
     * 之前 doLoopStep 自己排一条、外面又排一条，同一屏会被两条链各跑一遍，
     * 日志里能看见两次「补点」隔一秒连着落地，等于把选项点了两遍。
     */
    private void requeueLoop(long delayMs) {
        ui.removeCallbacks(pendingLoop);
        ui.removeCallbacks(loopAsk);
        ui.postDelayed(pendingLoop, Math.max(200, delayMs));
    }

    /** 同上，排的是「重新读屏问一次」那条链。 */
    private void requeueAsk(long delayMs) {
        ui.removeCallbacks(pendingLoop);
        ui.removeCallbacks(loopAsk);
        ui.postDelayed(loopAsk, Math.max(200, delayMs));
    }

    /**
     * 这一屏看着像不像题目：认出至少一组选项就算像。「自动点下一题」拿关键词去点，
     * 「确认」「继续」这种词别的页面上到处都是，得先用选项把门 —— 没有选项就不点。
     */
    private boolean looksLikeQuiz() {
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        return svc != null && svc.optionGroupCount() > 0;
    }

    /** 答完一题后安排下一轮自动连答。 */
    private void scheduleLoop() {
        if (!running || paused || !Prefs.autoLoop()) return;
        long d = (long) (Prefs.loopDelaySec() * 1000);
        if (failStreak > 0) {
            // 连着失败（断网、Key 填错、接口挂了）就一轮比一轮拖得久，封顶 30 秒。
            // 否则每 3 秒白问一次大模型，既没结果又烧额度。
            d = Math.max(d, Math.min(d << Math.min(failStreak, 4), 30000L));
        }
        requeueLoop(d);
    }

    /**
     * 当前这屏的指纹：优先用选项文字（稳定），读不到选项才退回整屏文字。
     * 整屏文字里挂着倒计时，每秒都在变，只能当次选。
     * <p>
     * **只读得到光杆标签的页面必须走退路**。学习通的随堂练习是典型：选项正文压根没进
     * 无障碍树，指纹只剩 {@code A=A|B=B|C=C|D=D|E=E} —— 每一题都长这样。拿它当
     * 「翻没翻页」的凭据，每翻一题都判成「还停在原屏」，于是「下一题」被一连点好几下，
     * 答完第 1 题能直接窜到第 5 题（用户报的就是这个）。题干每题都不同，换整屏文字
     * 才认得出翻页。
     * <p>
     * 取指纹前先等一等无障碍服务。这台机器上系统每隔十几秒就会把<strong>所有</strong>
     * 无障碍服务重新绑一遍（服务日志里「无障碍服务已连接」一遍遍地刷，系统侧对应
     * {@code AccessibilityManagerService.onUserStateChangedLocked}），解绑到重连之间
     * {@code get()} 是 null。连答那一拍正好落在这一瞬，指纹就成了空串 —— 而空串会被当成
     * 「换屏了」，于是同一道题被一遍遍重读、重问大模型、重新点一遍，停不下来（用户报的
     * 「最后一页十秒一循环」就是这么来的）。重连极快（实测 8 毫秒），等一下就好。
     */
    /**
     * 拿到无障碍服务实例，拿不到就等一小会儿 —— 系统的重新绑定有个很短的真空期
     * （实测 8 毫秒就回来了），值得等。
     *
     * @param timeoutMs 最多等多久；真断了（服务被用户关掉/系统回收）会一直等到超时
     */
    private static QuizAccessibilityService waitForService(long timeoutMs) {
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        long waited = 0;
        while (svc == null && waited < timeoutMs) {
            SystemClock.sleep(50);
            waited += 50;
            svc = QuizAccessibilityService.get();
        }
        return svc;
    }

    private String screenKey() {
        QuizAccessibilityService svc = waitForService(600);
        if (svc == null) return "";      // 服务真断了：返回空串，调用方按「读不到」处理
        String sig = svc.optionSignature(AnswerParser.allowedLetters(Prefs.labels()));
        if (!sig.isEmpty() && !bareSig(sig) && !svc.optionsUnreadable()) return sig;
        // 选项正文读不到的那些页面（学习通随堂练习）：指纹只能靠**题干那部分**。
        // 不能拿整屏文字（quickText）：选项行是**懒加载**的，点一下选项会补几行进树，
        // 整屏文字跟着变，连答就以为翻了页 —— 同一道题重答一遍，答案里的字母又点了一遍，
        // 而 WebView 的选项点一下是取消，刚选上的全被点掉（见 stemText 的注释）。
        String t = svc.stemText();
        if (t.isEmpty()) t = svc.quickText();     // 循环里每秒比一次，别用要睡 500ms 的 dumpText()
        if (!t.isEmpty()) {
            String k = cleanKey(t);
            if (!k.isEmpty()) return k;
        }
        // 只剩光杆标签、整屏文字又读不到：这种指纹认不出换题，宁可当「读不到」，
        // 也不能拿它去说「页面没动」—— 那会让连答把「下一题」一连点好几下。
        return bareSig(sig) ? "" : sig;
    }

    /**
     * 这个选项指纹是不是等于「什么都没读到」—— 每一段都是 {@code A=A}、{@code B=} 这种
     * 「标签等于标签自己」。
     * <p>
     * 学习通的随堂练习只有光杆标签进得了无障碍树（选项正文那截 DOM 压根没暴露），
     * 指纹就成了 {@code A=A|B=B|C=C|D=D} —— <b>每一道题都长这样</b>。拿它判
     * 「页面翻没翻」永远判成没翻：答完第一题以后，循环每一拍都以为还停在原屏，
     * 于是反复补点「下一题」（一跳跳好几题），或者干脆停下。
     */
    private static boolean bareSig(String sig) {
        if (sig == null || sig.isEmpty()) return true;
        int n = 0;
        for (String part : sig.split("\\|")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) continue;                  // 多题那种 "#0:A|B|…"：不含 =，另有判断
            String lab = part.substring(0, eq).trim();
            String rest = part.substring(eq + 1).trim();
            if (!rest.isEmpty() && !rest.equals(lab)) return false;   // 这段读到正文了
            n++;
        }
        return n > 0;
    }

    /**
     * 整屏文字拿去当指纹之前的净化。
     * <p>
     * 要抹掉两类东西，它们一变，同一道题也会被算成「翻了页」：
     * 一是选中的勾（点完选项很多页面会把文字改掉，指纹跟着变，连答就会把刚点上的
     * 选项<b>再点一遍</b> —— WebView 的选项点一下是取消）；二是倒计时那种每秒都在
     * 跳的数字。
     */
    private static String cleanKey(String s) {
        String t = s.replaceAll("\\s+", "");
        t = t.replaceAll("[✓✔√☑☒●◉■★]", "");
        t = t.replace("已选中", "").replace("已选", "");
        t = t.replaceAll("\\d{1,3}:\\d{2}(:\\d{2})?", "");    // 「剩余 09:58」「1:55:31」
        return t;
    }

    /**
     * 现在这一屏还是「刚才读的那一屏」吗？不是就返回一句给人看的说明，是就返回空串。
     * <p>
     * 读屏（无障碍文字 / 截图 OCR）到点答案中间隔着一整次大模型请求，实测 2～20 秒。
     * 这段时间里用户自己滑了屏、页面自己翻了页、或者干脆切走了 App —— 那这一轮模型给的
     * 答案是给**上一屏**的，按它去点就是在别的题上乱按。实测最惨的一次：读到的是本应用
     * 自己的日志页（见 readAndAsk 里那条守卫），字母却点进了前台浏览器的第 1 题。
     * <p>
     * 宁可这一轮白问一次（下一拍重新读屏重问），也不要在别的题上留一堆点错的选项
     * —— 多选题再点一下就是取消，点错比不点贵得多。
     */
    private String screenMoved(String pkgAtRead, String keyAtRead, AnswerParser.Parsed p) {
        // ① 前台换 App 了没有。只信 activeAppPackage()（实时窗口列表），不看那个会被
        //    我们自己弹的界面带偏的 foregroundPkg；读不着包名时当没换，别乱拦。
        String now = QuizAccessibilityService.activeAppPackage();
        if (!pkgAtRead.isEmpty() && !now.isEmpty() && !now.equals(pkgAtRead)) {
            return "前台从 " + pkgAtRead + " 换成了 " + now;
        }

        // ② 模型**照抄回来的题干**还在不在这一屏上。这条最准：页面翻到别的题，题干就没了；
        //    而页面上的倒计时、工具栏、动画那些噪声不影响题干。提示词逼着模型逐字抄题干
        //    （items[].q），正好拿来用 —— 一屏多题时每条都在，任意一条还在就算没翻。
        //    比的是**模糊匹配**（sameStem），不是逐字 contains：学习通的题干是截图 OCR 认出来的，
        //    「染色体体」认成「本」这种错字每个长句都有，逐字比等于每轮都判成「翻页了」，
        //    一轮都不会点（用户报的「一直在读、就是不答」就是这么来的）。
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        String text = svc == null ? "" : svc.quickText();
        if (!text.isEmpty() && p != null) {
            int asked = 0;
            int n = Math.max(1, p.items.isEmpty() ? 1 : p.items.size());
            for (int i = 0; i < n; i++) {
                AnswerParser.Parsed it = p.items.isEmpty() ? p : p.items.get(i);
                String q = AnswerParser.normStem(it.q);
                if (q.length() < 4) continue;        // 没抄题干 / 抄得太短，这条不算数
                asked++;
                if (AnswerParser.sameStem(it.q, text)) return "";   // 还在，这一屏没换
            }
            if (asked > 0) return "屏幕已经翻过去了（模型读到的题干不在这一屏上了）";
        }

        // ③ 模型没抄题干（题干本身没读出来、或者回了份不按格式的 JSON）时才退回指纹比对。
        //    指纹读不出来（正在翻页、树还没生成、页面上压根没选项）的一律放行：
        //    算不出指纹就分不清「没变」和「读不到」，拦下去会把每一轮都废掉。
        //    只用 keyAtRead（读屏前拍的），**不能退回 askKey** —— askKey 是答复回来之后才拍的，
        //    拿它和「现在」比就是几毫秒内自己跟自己比，永远相等，这条守卫等于没有。
        String ref = keyAtRead;
        String cur = screenKey();
        if (cur.isEmpty() || ref.isEmpty() || cur.equals(ref)) return "";
        // 整屏文字那条退路里挂着倒计时（「剩余 09:58」每秒都在变），比之前先把数字抹掉：
        // 只有**字**变了才算翻页，计时器跳秒、题号进位都不算。
        if (bare(cur).equals(bare(ref))) return "";
        return "屏幕已经翻过去了";
    }

    /**
     * 遥控口（DUMP）用：把「这一屏的指纹到底是怎么算出来的」摊开写进 groups.txt。
     * <p>
     * 出一趟「明明是同一道题、连答却一轮一轮地重问」，八成是 {@link #screenKey()} 返回了
     * 空串 —— 循环里 {@code same} 直接判 false，每拍都当成「换屏了」，于是重读、重问、
     * 再点一遍，停不下来。这几行就是拿来看它到底空在哪一步的（选项指纹？整屏文字？）。
     */
    static String debugKeyInfo() {
        StringBuilder sb = new StringBuilder("screenKey 诊断：\n");
        QuizAccessibilityService svc = QuizAccessibilityService.get();
        if (svc == null) return sb.append("  无障碍服务没连（screenKey 只能返回空串）\n").toString();
        String labels = AnswerParser.allowedLetters(Prefs.labels());
        String sig = svc.optionSignature(labels);
        String quick = svc.quickText();
        sb.append("  labels=\"").append(labels).append("\"\n");
        sb.append("  选项指纹 ").append(keyPreview(sig)).append('\n');
        sb.append("  选项读不到正文=").append(svc.optionsUnreadable()).append('\n');
        sb.append("  选项组数=").append(svc.optionGroupCount()).append('\n');
        sb.append("  整屏文字 ").append(keyPreview(quick)).append('\n');
        FloatingService s = inst;
        sb.append("  最终 screenKey ").append(keyPreview(s == null ? "" : s.screenKey())).append('\n');
        sb.append(svc.barsDebug());
        return sb.toString();
    }

    /**
     * 指纹的短预览：日志里比「换没换屏」全靠它，整串太长会把日志刷爆。
     * <p>
     * 两头都留：光看开头的话，「同一屏、指纹却在尾巴上抖」这种毛病在日志里
     * 两行长得一模一样，根本看不出哪不一样（踩过一次）。
     */
    private static String keyPreview(String k) {
        if (k == null || k.isEmpty()) return "(空)";
        String one = k.replaceAll("\\s+", "");
        if (one.length() <= 40) return "len=" + one.length() + "「" + one + "」";
        return "len=" + one.length() + "「" + one.substring(0, 24) + "…"
                + one.substring(one.length() - 14) + "」";
    }

    /** 抹掉所有数字和空白，只留「字」：倒计时跳秒这种自带的变化不算翻页。 */
    private static String bare(String s) {
        return s.replaceAll("[0-9０-９\\s]", "");
    }

    /**
     * 自动连答的一步：只看「屏幕还是不是刚才那一屏」。是就等着（顺带把没点中的
     * 「下一题」再点两下、该往下滑的滑一屏），不是就接着答新的一屏。
     * <p>
     * 假定的用法只有一种：<b>用户全程不动屏幕，只在需要的时候按暂停/继续</b>。
     * 所以每一屏的正常剧本是固定的 —— 读屏、问大模型、点答案、点「下一题」、翻过去。
     * 凡是这个剧本解释不了的（页面没翻、读不到屏、前台被切走），都属于「其他情况」：
     * 直接停下等用户按继续，**不猜、不重试、不重答**。
     * <p>
     * 以前这里堆了三层补救（补点选项、改手势点坐标、卡住就重问一遍），代价用户都见过了：
     * 同一道题被一圈圈重问、多选题已经选上的选项被反复点掉（「一直在反复按同一个答案」）。
     * 页面翻不动就是翻不动，停下来告诉人，比一轮轮瞎猜强。
     */
    private void doLoopStep() {
        if (!running || paused) return;
        if (busy) {                       // 上一轮还没跑完，等会儿再看
            requeueLoop(800);
            return;
        }

        // 前台不是答题那个 App 了（用户切了 App / 回桌面 / 弹出别的窗口）——按「用户不动」
        // 的模型这不该发生，出现了就是其他情况：直接停，别在别人的界面上乱读乱点。
        String pkg = QuizAccessibilityService.currentPackage();
        if (!quizPkg.isEmpty() && !pkg.isEmpty()
                && !pkg.equals(quizPkg) && !pkg.equals(getPackageName())) {
            halt("前台变成了 " + pkg + "，不是在答题了");
            return;
        }

        String key = screenKey();
        // 页面刚翻过去、或者系统正在重绑无障碍服务的那一两秒里，Chromium 的无障碍树
        // 读出来是空的 —— 实测「点完下一题」的下一拍、「往下滑一屏」的下一拍都是这样，
        // 隔半秒再读又是好好的。别急着把这拍记成「读不到」，就地再读两下
        // （和 readAndAsk 里那条重读一个道理）。
        for (int i = 0; i < 2 && key.isEmpty() && running && !paused; i++) {
            SystemClock.sleep(400);
            key = screenKey();
        }
        // 指纹读不出来（无障碍服务正被系统重新绑定、页面还没渲染、真断了）**不等于换屏了**：
        // 这一拍本来就什么都读不到，问出来的答案有没有对上根本没准。等它回来；
        // 一直读不到，就是「其他情况」→ 停下。
        if (key.isEmpty()) {
            blankKeyTicks++;
            // 「读不到」有两种，得分开说：
            //   1) 无障碍服务压根没连上（服务实例都拿不到）—— ColorOS 会把无障碍服务
            //      整个关掉（`enabled_accessibility_services` 里直接没了、总开关也变 0），
            //      这时候读屏、点选项、找「下一题」全是废的，等多久都不会自己好。
            //      以前一律印「系统正在重连」，用户看着一头雾水，还以为是我们读错了。
            //   2) 连着但这一瞬读不出字（页面在翻、Chromium 懒惰加载）—— 等半秒就好。
            boolean offline = QuizAccessibilityService.get() == null;
            if (blankKeyTicks == 1 || blankKeyTicks % 10 == 0 || (offline && blankKeyTicks == 3)) {
                logAsk(stamp() + " 连答：" + (offline
                        ? "无障碍服务没连上（多半被系统关掉了），点不了也读不了"
                        : "这一拍读不到屏幕（无障碍服务多半正在被系统重连）")
                        + "，先等下一拍" + (blankKeyTicks > 1 ? "（第 " + blankKeyTicks + " 拍）" : ""));
            }
            // 无障碍服务被系统关掉之后**不会自己回来**。以前这里只会停下、让用户去设置里
            // 手动打开 —— 用户多半压根没看见那条提示，在他的感受里就是「又卡了」。
            // 现在先用 Shizuku（shell 身份，能写 secure 设置）把服务重新打开，能自救就自救。
            if (offline && tryHealA11y()) {
                blankKeyTicks = 0;
                cancelA11yNotice();
                // 日志那一行在 tryHealA11y 里写（它知道走的是哪条路）
                toast("无障碍被系统关了，已自动打开，接着答");
                requeueLoop(2500);
                return;
            }
            // 自救不了（没装 Shizuku / 没授权 / 刚试过还在冷却）就别再拍十拍了：
            // 拍多少下都一样，早点停下把话说清楚。
            // 这时候光写日志、发通知用户都看不见（这台机器上通知是关的），
            // 所以直接把「去打开设置」那张卡摆到屏幕上 —— 这是唯一他一定看得见的地方。
            if (offline && blankKeyTicks >= 3) {
                notifyA11yOff();
                showA11yGuide();
                halt("无障碍服务被系统关掉了，读不到屏幕也点不动选项 —— "
                        + "点屏幕上那张卡里的「去打开设置」，把「答题助手」重新打开"
                        + "（打开后点一下悬浮球继续）");
                return;
            }
            if (blankKeyTicks >= STUCK_LIMIT) {
                halt("连着 " + blankKeyTicks + " 拍读不到屏幕上的字");
                return;
            }
            requeueLoop((long) (Prefs.loopDelaySec() * 1000));
            return;
        }
        blankKeyTicks = 0;
        cancelA11yNotice();          // 读到了就说明无障碍又活着了，那条提示该撤了
        hideA11yGuide();             // 同上：能读屏了就不需要那张「去打开设置」的卡

        if (key.equals(lastScreenKey)) {
            // 页面没翻。正常剧本里这一屏答完、点完「下一题」就该翻过去了 —— 没翻只有几种
            // 可能：这一屏压根没有「下一题」（最后一页/结算页）、「下一题」没点中、
            // 或者题太长、后面几题在屏幕外，得往下滑。都不是靠重读重答能解决的，
            // 宽限几拍还不动就停下（见 STUCK_LIMIT）。
            sameScreenTicks++;
            boolean quiz = looksLikeQuiz();
            // 上一屏真答出来了才谈得上翻页：大模型超时/解析失败那几拍，页面本来就不会动。
            // nextPending 为真说明「答完点下一题」还排着队，别跟它抢着点 —— 连点两下
            // 就是跳过一整题（用户报过「答完第 1 题一路窜到第 5 题」）。
            if (Prefs.autoNext() && quiz && lastRoundAnswered && !nextPending
                    && sameScreenTicks % 2 == 0 && sameScreenTicks <= 6) {
                boolean clicked = clickNextKeyword();
                logAsk(stamp() + " 连答：这屏还没翻，" + (clicked ? "再点了一次「下一题」" : "没找到「下一题」")
                        + "（第 " + sameScreenTicks + " 拍）");
            }
            // 竖排长卷子：这一屏答完了、「下一题」也没有，但下面还有内容 —— 往下滑一屏就是
            // 这类页面的「翻页」（一页七题那种，后面几题在屏幕外）。模型说「这屏答不了」
            // （选项没显示全，见 lastRoundBlank）时同理：滑下去才看得全。
            // 一屏最多滑两次，滑不动就不再试。
            boolean wantScroll = lastRoundAnswered || lastRoundBlank;
            if (Prefs.autoScroll() && quiz && wantScroll && !nextPending && scrollTries < 2) {
                QuizAccessibilityService s = QuizAccessibilityService.get();
                if (s != null && s.hasMoreBelow()) {
                    scrollTries++;
                    boolean ok = s.scrollDown();
                    logAsk(stamp() + " 连答：这屏答完了、没有「下一题」，下面还有内容，"
                            + (ok ? "往下滑一屏" : "想往下滑但没滑动") + "（第 " + scrollTries + " 次）");
                    sameScreenTicks = 0;
                    lastScreenKey = "";       // 滑过之后是新的一屏，别拿旧指纹去比
                    requeueAsk(1800);         // 等滑动动画和惰性加载
                    return;
                }
            }
            if (sameScreenTicks >= STUCK_LIMIT) {
                if (quiz && looksLikeSubmitPage()) {
                    // 正常跑完一卷的终点。交卷得用户自己点，这里只把话说清楚。
                    halt("到最后了（页面上只剩「提交」，不替用户交卷）");
                } else {
                    // 纯填空题页面没有选项，quiz 会是 false；这一屏真答过（填进去了）就别
                    // 说「不像题目」，用户听着像出错，其实只是最后一页没有「下一题」。
                    halt("这一屏" + (quiz || lastRoundAnswered ? "答完之后一直没翻动" : "没有选项、也不像题目"));
                }
                return;
            }
            // 这一屏「读到了、但没答出来」（模型说选项没读全、或者压根解析不出答案）：
            // 多半是刚才那一次读屏出了岔子 —— 截图 OCR 认漏了选项标签之类（实测第 3 题
            // 把 C 认丢了，模型回「标签不连续，无法确定」，这一屏就再没人管了）。
            // 重读重问一次通常就好。**有上限**：问过两次还不行就按「其他情况」停下，
            // 绝不能变成一轮轮地重问 —— 那正是用户报过的毛病。
            if (lastRoundBlank && reaskTries < 2 && !nextPending && lastScreenKey.equals(key)) {
                reaskTries++;
                logAsk(stamp() + " 连答：这一屏没答出来，重读一遍再问（第 " + reaskTries + " 次）");
                requeueAsk(1500);
                return;
            }
            requeueLoop((long) (Prefs.loopDelaySec() * 1000));
            return;
        }

        // 换屏了：这就是「上一屏答完、点了下一题」的结果，接着答新的一屏。
        sameScreenTicks = 0;
        scrollTries = 0;
        reaskTries = 0;
        if (!key.equals(keyLogged)) {
            logAsk(stamp() + " 连答：换到新的一屏 " + keyPreview(key)
                    + "（上一屏 " + keyPreview(lastScreenKey) + "）");
            keyLogged = key;
        }
        lastScreenKey = key;
        requeueAsk(1200);                           // 给翻页留出时间再读屏
    }

    /**
     * 停下自动连答，等用户按继续。
     * <p>
     * 只在「按用户全程不动的模型解释不了」的时候调用：页面不翻、读不到屏、前台被切走。
     * 这种时候以前会一轮轮重读重答重试，用户看到的就是同一道题被反复问、已经选上的
     * 选项被反复点掉。停下来是最省事也最不容易出错的处理。
     */
    private void halt(String why) {
        if (!running || (paused && !armed)) return;
        logAsk(stamp() + " 连答：停下 —— " + why);
        armed = false;
        paused = true;
        cancelNextClick();
        ui.removeCallbacks(pendingLoop);
        ui.removeCallbacks(loopAsk);
        setFloatLabel("停");
        paintBall(BALL_ERROR);
        toast("连答停下：" + why);
        refreshServiceNotification();
    }

    // ---------------- 无障碍自愈 ----------------

    /** 上次试着自救是什么时候，用来防抖（见 {@link #tryHealA11y}）。 */
    private long lastHealAt;
    /**
     * 两次自救之间至少隔这么久。
     * <p>
     * 「无障碍被关掉」这件事要么一次就修好，要么根本修不了（没 Shizuku / 没授权）——
     * 每拍都去敲一遍 Shizuku 只是白等，还会把日志刷满。
     */
    private static final long HEAL_GAP_MS = 20_000;

    /**
     * 无障碍服务被系统关掉之后，把自己重新打开。
     * <p>
     * ColorOS 会在 App 更新 / 被强停 / 进程反复挂掉之后把无障碍服务整个摘掉
     * （{@code enabled_accessibility_services} 里没了、总开关也变 0）。这是最常见的
     * 「学习通又卡了」：球还亮着，但读不了也点不动，App 只能停下喊用户去设置里点。
     * <p>
     * 两条路，按可靠程度排：
     * <ol>
     *   <li><b>直接写 secure 设置</b> —— 要 {@code WRITE_SECURE_SETTINGS}。这是个
     *       {@code development} 级权限，电脑上敲一句
     *       {@code adb shell pm grant com.moling.quizhelper android.permission.WRITE_SECURE_SETTINGS}
     *       就永久给上了（装完 App 给一次就行，重启手机也不掉），之后完全靠 App 自己。</li>
     *   <li><b>Shizuku</b> —— 上面那条没给上时的替补。Shizuku 跑在 shell(uid 2000) 身份下，
     *       那个 uid 天然有 WRITE_SECURE_SETTINGS，跟我们平时敲
     *       `adb shell settings put secure ...` 是同一件事。但它得一直活着
     *       （手机重启、ADB 断开就没了），所以只当替补。</li>
     * </ol>
     * 返回 true 表示「这一拍把该做的都做了」：设置写进去了，服务会在随后一两秒内绑上来，
     * 调用方隔一拍重读即可 —— 这里**故意不原地等**（本方法跑在主线程）。
     */
    /** 本 App 有没有 {@code WRITE_SECURE_SETTINGS}（电脑上 `adb shell pm grant` 给的那种）。 */
    private boolean canWriteSecure() {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 读一眼「现在挂了哪些无障碍服务」。读 secure 设置不需要权限，谁都能读。 */
    private String readEnabledA11y() {
        try {
            String s = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean tryHealA11y() {
        if (QuizAccessibilityService.get() != null) return false;   // 已经好了，不用管
        long now = SystemClock.uptimeMillis();
        if (lastHealAt != 0 && now - lastHealAt < HEAL_GAP_MS) return false;

        boolean direct = canWriteSecure();
        boolean shizuku = ShizukuShell.granted();
        if (!direct && !shizuku) return false;
        lastHealAt = now;

        // 先用自带权限写；它要是在这台机器上被系统挡了，再走 Shizuku 那条。
        if (direct && writeA11ySettings(null)) {
            logAsk(stamp() + " 连答：无障碍服务被系统关掉了，已用自带的权限重新打开，下一拍接着答");
            return true;
        }
        if (shizuku && writeA11ySettings("shizuku")) {
            logAsk(stamp() + " 连答：无障碍服务被系统关掉了，已用 Shizuku 重新打开，下一拍接着答");
            return true;
        }
        return false;
    }

    /**
     * 真的去写那两条 secure 设置。{@code viaShizuku} 为 null 表示用本 App 自己的
     * WRITE_SECURE_SETTINGS 权限（给 {@link android.content.ContentResolver} 写），
     * 非 null 表示让 Shizuku 以 shell 身份敲 {@code settings put}。
     *
     * @return 是否写成功
     */
    private boolean writeA11ySettings(String viaShizuku) {
        try {
            // 只加自己，**不动别人**：原值里那一串别的无障碍服务（系统自带的、别的工具）
            // 必须原样留着，覆盖掉等于替用户把别的都关了。
            // 判「在不在里面」只看到包名为止：系统自己写回去的是简写 `包名/.类名`
            // （实测装完 APK 就变成这样），拿全名去找会认不出来，于是每自救一次就
            // 往那串里再追加一份同样的自己。
            String cur;
            if (viaShizuku == null) {
                String s = android.provider.Settings.Secure.getString(getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                cur = s == null ? "" : s.trim();
            } else {
                cur = ShizukuShell.str("settings get secure enabled_accessibility_services").trim();
            }
            String val = cur;
            if (!cur.contains(getPackageName() + "/")) {
                String me = getPackageName() + "/" + QuizAccessibilityService.class.getName();
                val = (cur.isEmpty() || "null".equals(cur)) ? me : cur + ":" + me;
            }
            if (viaShizuku == null) {
                android.provider.Settings.Secure.putString(getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, val);
                android.provider.Settings.Secure.putString(getContentResolver(),
                        android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            } else {
                ShizukuShell.str("settings put secure enabled_accessibility_services '" + val + "'");
                ShizukuShell.str("settings put secure accessibility_enabled 1");
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "自救无障碍没写成（via=" + viaShizuku + "）", t);
            return false;
        }
    }

    // ---------------- 调试日志 ----------------

    /**
     * 把每次作答的关键信息追加到 files/asklog.txt，方便 adb 拉出来复盘：
     *   adb shell cat /sdcard/Android/data/com.moling.quizhelper/files/asklog.txt
     * 只写题目/回复/答案/点击结果，不写 API Key。超过 512KB 就重头开始。
     */
    private static String stamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date()) + " ";
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("[ \\t]+", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…（后面还有 " + (t.length() - n) + " 字）" : t;
    }

    private void logAsk(String s) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "asklog.txt");
            if (f.length() > 512 * 1024) f.delete();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(s);
                if (!s.endsWith("\n")) w.write('\n');
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 通知 / 播报 ----------------

    private void notifyAnswer(String answer, String exp) {
        if (!Prefs.notifyAnswer()) return;      // 设置里把答案通知关了
        if (exp == null || exp.isEmpty()) exp = "（无解析）";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CH_ANSWER)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("答案：" + answer)
                .setContentText(exp)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(exp))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
        try {
            nm.notify(NOTIF_ANSWER, n);
        } catch (Throwable ignored) {
        }
        ui.postDelayed(() -> {
            try {
                nm.cancel(NOTIF_ANSWER);
            } catch (Throwable ignored) {
            }
        }, Math.max(1, Prefs.notifSec()) * 1000L);
    }

    /**
     * 无障碍服务被系统关掉、自己又救不回来时，弹一条通知给用户**一键跳设置**的路。
     * <p>
     * 以前这里只在日志里写一句「去设置 → 无障碍 → 已下载的应用…」。连答的日志用户
     * 根本不会翻，在他眼里就是「球还亮着、可它不动了」—— 也就是他说的「又卡了」。
     * 通知栏那一条是躲不掉的，点一下直接落到无障碍列表页，省得自己去翻菜单。
     */
    private void notifyA11yOff() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CH_ANSWER)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("无障碍被系统关掉了，连答停了")
                .setContentText("点这条去把它打开（答题助手），回来点一下悬浮球接着答")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("系统把「答题助手」的无障碍权限关掉了，读屏和点选项都用不了。\n"
                                + "点这条通知 → 在列表里把「答题助手」打开 → 回来点一下悬浮球。"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
        try {
            nm.notify(NOTIF_A11Y, n);
        } catch (Throwable ignored) {
        }
    }

    /** 无障碍恢复之后把那条「去打开无障碍」的通知收掉。 */
    private void cancelA11yNotice() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_A11Y);
        } catch (Throwable ignored) {
        }
    }

    private void initTts() {
        try {
            tts = new TextToSpeech(this, status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                try {
                    int r = tts.setLanguage(Locale.CHINA);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.US);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            tts = null;
        }
    }

    /**
     * 念答案。开了「念解析」就把解析接在后面一起念。
     * <p>
     * 一律用 {@code QUEUE_FLUSH}：下一题开始念的时候，上一题那句（尤其是解析，能念十几秒）
     * 得当场掐掉。用 QUEUE_ADD 会排成一条长队，念到第五题时还在念第一题的解析。
     */
    private void speak(String answer, String explanation) {
        if (!Prefs.tts() || tts == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            if (answer != null && !answer.isEmpty()) {   // 纯填空题没有字母答案，就别念「答案」俩字
                sb.append("答案 ");
                for (int i = 0; i < answer.length(); i++) sb.append(answer.charAt(i)).append(' ');
            }
            if (Prefs.ttsExplanation() && explanation != null && !explanation.isEmpty()) {
                sb.append('。').append(explanation);
            }
            if (sb.length() == 0) return;
            tts.speak(sb.toString(), TextToSpeech.QUEUE_FLUSH, null, "quizhelper");
        } catch (Throwable ignored) {
        }
    }

    /** 没开语音念解析时的调用（只有答案）。 */
    private void speak(String answer) {
        speak(answer, "");
    }

    private void toast(final String s) {
        ui.post(() -> {
            try {
                Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // 这里什么都不做，尤其不要再把中断标志设回去。
            // 标志一旦挂在工作线程上，后面每一次阻塞式网络调用（HttpURLConnection）
            // 都会立刻抛 InterruptedIOException，看着像「大模型请求失败」，
            // 其实是我们自己把线程弄脏了。
        }
    }
}
