package com.cx.biliredirect;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.XposedBridge;

/**
 * 内嵌的 native 规避器。
 *
 * QQ 的风控库 libfekit.so 用 fopen("/proc/self/smaps") 扫描内存映射检测注入痕迹，
 * 这是 native 调用，Java hook 拦不住，必须在 native 层改它的 GOT。
 *
 * 本类负责把随 APK 打包的 libstealth.so 加载进宿主进程并启动轮询。
 * 用 System.load 绝对路径（而不是 loadLibrary），因为宿主进程的 native 搜索路径
 * 不含我们模块的 lib 目录。
 */
public final class Stealth {

    private static final String TAG = "BiliRedirect";
    private static final String MODULE_PKG = "com.cx.biliredirect";
    private static final String SO_NAME = "libstealth.so";
    private static final String TARGET_LIB = "libfekit.so";
    private static final String BLOCK_PATH = "/proc/self/smaps";

    private static volatile boolean sStarted = false;

    private Stealth() {}

    /** 每个进程只需调一次。失败只记日志，不影响跳转功能。 */
    public static synchronized void ensureStarted(Context ctx) {
        if (sStarted) return;
        sStarted = true;

        try {
            String dir = nativeDir(ctx);
            if (dir == null) {
                XposedBridge.log(TAG + ": 找不到模块的 nativeLibraryDir，跳过 native 规避");
                return;
            }
            String soPath = dir + "/" + SO_NAME;
            System.load(soPath);
            XposedBridge.log(TAG + ": 已加载 " + soPath);
            nativeStart(TARGET_LIB, BLOCK_PATH);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 加载 libstealth.so 失败 " + t);
        }
    }

    /** 拿模块自己的 nativeLibraryDir（宿主进程里的 PackageManager 能查到）。 */
    private static String nativeDir(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(MODULE_PKG, 0);
            if (ai.nativeLibraryDir != null && !ai.nativeLibraryDir.isEmpty()) {
                return ai.nativeLibraryDir;
            }
        } catch (Throwable ignored) {
        }
        // 兜底：直接用 LSPosed 给模块分配的路径约定
        try {
            Application app = (Application) ctx.getApplicationContext();
            if (app != null) {
                ApplicationInfo self = app.getPackageManager()
                        .getApplicationInfo(MODULE_PKG, 0);
                return self.nativeLibraryDir;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static native void nativeStart(String libName, String blockPath);
}
