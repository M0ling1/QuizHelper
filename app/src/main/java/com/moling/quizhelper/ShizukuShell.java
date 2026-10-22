package com.moling.quizhelper;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 以 shell(uid 2000) 身份执行命令。
 * 用来做 screencap 截图、input tap 点击、uiautomator dump 取控件树。
 * 没有 Shizuku / 没有授权时，所有方法都安全失败，调用方自行降级。
 *
 * 注意：Shizuku v13 把 {@code Shizuku.newProcess} 改成了包内可见，
 * 所以这里直接走公开的 AIDL 接口 {@link IShizukuService#newProcess}。
 */
public final class ShizukuShell {

    private ShizukuShell() {
    }

    public static boolean ping() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean granted() {
        try {
            return ping() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String version() {
        try {
            return String.valueOf(Shizuku.getVersion());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static IShizukuService service() throws Exception {
        IBinder b = Shizuku.getBinder();
        if (b == null) throw new IllegalStateException("Shizuku 没在运行");
        IShizukuService s = IShizukuService.Stub.asInterface(b);
        if (s == null) throw new IllegalStateException("连不上 Shizuku 服务");
        return s;
    }

    /** 执行 shell 命令，返回 stdout 原始字节。失败抛异常。 */
    public static byte[] raw(String cmd) throws Exception {
        if (!granted()) throw new IllegalStateException("Shizuku 未授权");
        IRemoteProcess proc = service().newProcess(new String[]{"sh", "-c", cmd}, null, null);
        if (proc == null) throw new IllegalStateException("创建 shell 进程失败");

        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        InputStream in = null;
        try {
            ParcelFileDescriptor pfd = proc.getInputStream();
            if (pfd == null) throw new IllegalStateException("shell 没有输出流");
            in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);

            int code = proc.waitFor();
            if (code != 0 && bos.size() == 0) {
                String err = readErr(proc);
                throw new IllegalStateException("命令失败（退出码 " + code + "）"
                        + (err.isEmpty() ? "" : "：" + err));
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String str(String cmd) throws Exception {
        return new String(raw(cmd), StandardCharsets.UTF_8);
    }

    private static String readErr(IRemoteProcess proc) {
        try {
            ParcelFileDescriptor e = proc.getErrorStream();
            if (e == null) return "";
            InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(e);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < 8192) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** input tap，返回是否成功。 */
    public static boolean tap(int x, int y) {
        try {
            raw("input tap " + x + " " + y);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        try {
            raw("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
