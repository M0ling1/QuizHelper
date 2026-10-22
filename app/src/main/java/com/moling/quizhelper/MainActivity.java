package com.moling.quizhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int RC_PROJECTION = 2001;
    private static final int RC_NOTIF = 2002;
    private static final int RC_SHIZUKU = 2003;

    private TextView tvStatus;
    private TextView tvCount;
    private RecyclerView recycler;
    private LogAdapter adapter;

    private boolean pendingStart;
    private boolean pendingRestartWithProjection;
    /** 这一次进主界面已经为「无障碍没开」提醒过了 —— 别每次 onResume 都弹一遍。 */
    private boolean a11yGuided;

    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> {
                toast(grantResult == PackageManager.PERMISSION_GRANTED
                        ? "Shizuku 已授权" : "Shizuku 授权被拒绝");
                refreshStatus();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);
        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogAdapter(r -> {
            Intent i = new Intent(this, LogDetailActivity.class);
            i.putExtra(LogDetailActivity.EXTRA_LINE, r.toJson().toString());
            startActivity(i);
        });
        recycler.setAdapter(adapter);

        findViewById(R.id.btnStart).setOnClickListener(v -> startOverlay());
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            FloatingService.stop(this);
            toast("已停止");
            tvStatus.postDelayed(this::refreshStatus, 400);
        });
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnPerm).setOnClickListener(v -> showPermissionDialog());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.btnFullLog).setOnClickListener(v ->
                startActivity(new Intent(this, LogFullActivity.class)));

        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        List<LogRecord> list = LogStore.load(this);
        adapter.setData(list);
        tvCount.setText(list.isEmpty() ? "" : "共 " + list.size() + " 条");
        findViewById(R.id.tvEmpty).setVisibility(list.isEmpty() ? android.view.View.VISIBLE
                : android.view.View.GONE);
        guideA11yIfMissing();
    }

    /**
     * 无障碍没开就在主界面上直接把话说清楚。
     * <p>
     * 状态卡里那行小字「无障碍服务 未开启」太容易被略过了，可没有它整个助手就是废的
     * （读屏、点选项、找「下一题」全在无障碍服务身上）。这台机器上它是被 ColorOS 自己
     * 关掉的（强停 / 更新之后），用户的感觉是「昨天还好好的，今天怎么点都没反应」，
     * 所以这里不弹第二遍：一次进主界面提醒一次，去开了就不再提。
     */
    private void guideA11yIfMissing() {
        if (isAccessibilityEnabled() || a11yGuided) return;
        a11yGuided = true;
        new AlertDialog.Builder(this)
                .setTitle("无障碍服务没打开")
                .setMessage("助手的读屏、点选项、找「下一题」都靠它，没打开就什么都干不了。\n\n"
                        + "路径：设置 → 无障碍 → 已下载的应用 → 答题助手 → 打开开关。\n\n"
                        + "ColorOS 有时会在 App 被强停或更新之后自己把它关掉，"
                        + "所以看着像「突然就卡了」。打开之后回题目上点一下悬浮球就能接着答。")
                .setPositiveButton("去打开", (d, w) -> gotoA11ySettings())
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 跳无障碍设置：先试本应用的详情页，系统上没有这个页面就退回列表页。 */
    private void gotoA11ySettings() {
        try {
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("找到「答题助手」并打开开关");
        } catch (Throwable t) {
            toast("打不开无障碍设置：" + t.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ---------------- 状态 ----------------

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(line("悬浮球", !FloatingService.isRunning() ? "已停止"
                : (FloatingService.isArmed() ? "待启动（点悬浮球开始答题）"
                : (FloatingService.isPaused() ? "已暂停回答（点悬浮球继续）" : "运行中"))));
        sb.append(line("悬浮窗权限", Settings.canDrawOverlays(this) ? "已授权" : "未授权"));
        sb.append(line("无障碍服务", isAccessibilityEnabled() ? "已开启" : "未开启"));
        sb.append(line("Shizuku", shizukuState()));
        sb.append(line("API Key", Prefs.apiKey().isEmpty() ? "未填写" : "已填写"));
        // 模型和接口地址分两行写：地址带 https:// 常常超出一行，
        // 挤在一行时会从中间断开，看着像出错了。
        sb.append(line("模型", Prefs.model()));
        sb.append(line("接口地址", Prefs.baseUrl()));
        sb.append(line("文本来源", ocrSourceName()));
        sb.append(line("自动点击答案", Prefs.autoClick() ? "开" : "关"));
        sb.append(line("自动点下一题", Prefs.autoNext()
                ? "开（延迟 " + Prefs.nextDelaySec() + "s）" : "关"));
        sb.append(line("自动连答", Prefs.autoLoop()
                ? "开（间隔 " + Prefs.loopDelaySec() + "s）" : "关"));
        sb.append(line("填空题", fillModeName()));
        if (Prefs.bubble()) {
            sb.append(line("答案气泡", Prefs.bubblePos() == 5
                    ? "自定义 " + Prefs.bubbleX() + "% / " + Prefs.bubbleY() + "%"
                    : posName(Prefs.bubblePos())));
        }
        tvStatus.setText(sb.toString().trim());
    }

    private String line(String k, String v) {
        return k + "：" + v + "\n";
    }

    private String shizukuState() {
        if (!ShizukuShell.ping()) return "未安装 / 未运行";
        if (ShizukuShell.granted()) return "已授权（v" + ShizukuShell.version() + "）";
        return "已运行，未授权";
    }

    private String posName(int p) {
        String[] names = {"左上角", "右上角", "左下角", "右下角", "屏幕正中", "自定义"};
        return p >= 0 && p < names.length ? names[p] : "左上角";
    }

    /** 状态页那一行「填空题」：两条路都关才算关，开着就写清楚是自动填还是只复制。 */
    private String fillModeName() {
        if (!Prefs.fillCopy() && !Prefs.autoFill()) return "关";
        StringBuilder sb = new StringBuilder();
        if (Prefs.autoFill()) sb.append("自动填进空格");
        if (Prefs.fillCopy()) {
            if (sb.length() > 0) sb.append(" + 复制");
            else sb.append("复制正文");
            sb.append("（").append(Prefs.fillWaitSec() <= 0
                    ? "不自动翻页" : "等 " + Prefs.fillWaitSec() + "s 后接着答").append("）");
        }
        return sb.toString();
    }

    private String ocrSourceName() {
        switch (Prefs.ocrSource()) {
            case 1: return "仅无障碍文本";
            case 2: return "仅本地 OCR";
            case 3: return "仅视觉大模型";
            case 4: return "视觉优先（读不到退无障碍）";
            default: return "自动";
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String a = getPackageName() + "/" + QuizAccessibilityService.class.getName();
            String b = getPackageName() + "/.QuizAccessibilityService";
            for (String p : s.split(":")) {
                if (p.equalsIgnoreCase(a) || p.equalsIgnoreCase(b)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---------------- 启动 ----------------

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("先给「显示在其他应用上层」权限");
            requestOverlay();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIF);
        }
        if (needsProjection()) {
            pendingStart = true;
            requestProjection(null);
        } else if (FloatingService.isRunning()) {
            toast(FloatingService.isArmed() ? "悬浮球已就绪（绿）：切到题目上点球开始答"
                    : "悬浮球已经在跑了");
        } else {
            FloatingService.start(this, 0, null);
            toast("悬浮球已启动：切到题目上，点一下绿球开始答题");
            tvStatus.postDelayed(this::refreshStatus, 500);
        }
    }

    private boolean needsProjection() {
        if (!Prefs.needsScreenshot()) return false;
        int src = Prefs.shotSource();
        if (src == 1) return false;               // 只用 Shizuku
        if (src == 2) return true;                // 只用录屏
        return !ShizukuShell.granted();           // 自动：没有 Shizuku 才需要录屏
    }

    private void requestProjection(Intent prefill) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                toast("拿不到 MediaProjectionManager");
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), RC_PROJECTION);
        } catch (Throwable t) {
            toast("申请屏幕录制失败：" + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_PROJECTION) return;

        boolean ok = resultCode == Activity.RESULT_OK && data != null;
        if (pendingRestartWithProjection) {
            pendingRestartWithProjection = false;
            FloatingService.stop(this);
            if (ok) {
                FloatingService.start(this, resultCode, data);
                toast("已用新的录屏授权重启");
            } else {
                toast("未授权，已停止悬浮球");
            }
            tvStatus.postDelayed(this::refreshStatus, 600);
            return;
        }
        if (!pendingStart) return;
        pendingStart = false;

        if (ok) {
            FloatingService.start(this, resultCode, data);
            toast("悬浮球已启动（录屏 + 无障碍）：切到题目上点一下绿球开始答");
        } else {
            FloatingService.start(this, 0, null);
            toast("未授权录屏，改用无障碍/Shizuku 取词");
        }
        tvStatus.postDelayed(this::refreshStatus, 600);
    }

    // ---------------- 权限面板 ----------------

    private void showPermissionDialog() {
        String[] items = {
                "① 悬浮窗权限（必须）",
                "② 通知权限（Android 13+，状态栏显示答案）",
                "③ 无障碍服务（读屏 + 自动点击的核心）",
                "④ Shizuku 授权（免 root 提权，可截图/点击）",
                "⑤ 屏幕录制授权（不用 Shizuku 时的取词方式）",
                "⑥ 电池优化白名单（防止后台被杀）",
                "⑦ 重新申请屏幕录制并重启悬浮球"
        };
        new AlertDialog.Builder(this)
                .setTitle("权限与授权")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            requestOverlay();
                            break;
                        case 1:
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestPermissions(
                                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIF);
                            } else {
                                toast("当前系统不需要单独授权");
                            }
                            break;
                        case 2:
                            gotoA11ySettings();
                            break;
                        case 3:
                            requestShizuku();
                            break;
                        case 4:
                            pendingStart = true;
                            requestProjection(null);
                            break;
                        case 5:
                            requestIgnoreBattery();
                            break;
                        default:
                            pendingRestartWithProjection = true;
                            requestProjection(null);
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void requestOverlay() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("跳转失败，请手动到设置里授权");
        }
    }

    private void requestShizuku() {
        try {
            if (!ShizukuShell.ping()) {
                toast("Shizuku 没在运行。先装 Shizuku 并通过无线调试/ADB 启动它。");
                return;
            }
            Shizuku.requestPermission(RC_SHIZUKU);
        } catch (Throwable t) {
            toast("请求 Shizuku 授权失败：" + t.getMessage());
        }
    }

    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(i);
            toast("把「答题助手」设为「不受限制」");
        } catch (Throwable t) {
            toast("跳转失败，请手动设置");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_NOTIF) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "通知权限已授予" : "没有通知权限，状态栏就看不到答案了");
            refreshStatus();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空问答日志？")
                .setMessage("会删掉本机所有历史记录，不可恢复。")
                .setPositiveButton("清空", (d, w) -> {
                    LogStore.clear(this);
                    adapter.setData(LogStore.load(this));
                    findViewById(R.id.tvEmpty).setVisibility(android.view.View.VISIBLE);
                    toast("已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
