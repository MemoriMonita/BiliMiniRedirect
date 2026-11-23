package io.github.memorimonita.biliminiredirect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 系统框架（system_server）侧的重定向 —— 零注入 QQ 进程。
 *
 * 思路：不在 QQ 里挂任何 hook，而是在 system_server 解析 Intent 的阶段
 * （ActivityTaskSupervisor#resolveIntent / #resolveActivity）把「目标是官方 B 站」
 * 的 Intent 就地改成目标客户端（默认 PiliPlus）的包名。
 *
 * 为什么改「原对象」而不是换新的 Intent：
 *   resolveIntent 之后，ActivityStarter.Request 里存的还是同一个 Intent 引用，
 *   换对象会导致「解析出来是 PiliPlus、真正启动却还是 bili」的错位；
 *   就地 setPackage 则两边天然一致。
 *
 * 好处：QQ 进程里没有 LSPosed 注入、没有 XposedBridge、没有 lspd so，
 *       libfekit.so 再怎么扫 maps 也扫不到东西。
 */
public final class SysHook {

    private static final String TAG = "BiliRedirect";
    private static final String MODULE_PKG = "io.github.memorimonita.biliminiredirect";
    private static final String PREF_NAME = "config";
    private static final String DEFAULT_TARGET = "com.example.piliplus";

    private static final Set<String> BILI_PKGS = new HashSet<>(Arrays.asList(
            "tv.danmaku.bili",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.in",
            "com.bilibili.bilithings",
            "com.bilibili.app.blue"));

    private static Context sCtx;
    private static XSharedPreferences sPrefs;
    private static String sTarget = DEFAULT_TARGET;
    private static boolean sEnabled = true;
    private static long sLastLoad;

    private SysHook() {}

    public static void install(LoadPackageParam lp) {
        int n = 0;
        try {
            Class<?> sup = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskSupervisor", lp.classLoader);
            for (Method m : sup.getDeclaredMethods()) {
                String name = m.getName();
                if (!"resolveIntent".equals(name) && !"resolveActivity".equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 1 || p[0] != Intent.class) continue;
                XposedBridge.hookMethod(m, new Redirect());
                XposedBridge.log(TAG + ": [sys] hook " + name
                        + "(" + p.length + " 参) OK");
                n++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [sys] hook 失败 " + t);
        }
        if (n == 0) {
            XposedBridge.log(TAG + ": [sys] 没找到 resolveIntent/resolveActivity —— 系统版本不匹配");
        }
    }

    /** 命中官方 B 站包名时，把 Intent 就地改成目标客户端的包名。 */
    static class Redirect extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length == 0 || !(param.args[0] instanceof Intent)) return;
                loadConfig();
                if (!sEnabled) return;

                Intent it = (Intent) param.args[0];
                String from = targetOf(it);
                if (from == null || !BILI_PKGS.contains(from)) return;
                if (!targetInstalled()) return;

                // 就地改：清掉显式 component，只留包名，让系统重新解析到目标客户端的
                // 启动 Activity（date/extras/action 全部原样带过去）。
                if (it.getComponent() != null) it.setComponent((ComponentName) null);
                it.setPackage(sTarget);

                XposedBridge.log(TAG + ": [sys] " + describe(it)
                        + "  (原目标 " + from + " → " + sTarget + ")");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": [sys] redirect 失败（已放行原 Intent）" + t);
            }
        }
    }

    private static String targetOf(Intent it) {
        ComponentName cn = it.getComponent();
        if (cn != null) return cn.getPackageName();
        return it.getPackage();
    }

    private static String describe(Intent it) {
        return "pkg=" + it.getPackage()
                + " comp=" + it.getComponent()
                + " action=" + it.getAction()
                + " data=" + it.getData();
    }

    /** 目标客户端没装就放行，避免把跳转改成「Activity 不存在」。 */
    private static boolean targetInstalled() {
        Context ctx = ctx();
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(sTarget, 0);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [sys] 目标未安装 " + sTarget);
            return false;
        }
    }

    /** system_server 里没有 Application，只能从 AppGlobals 拿系统上下文。 */
    private static synchronized Context ctx() {
        if (sCtx != null) return sCtx;
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Object app = ag.getMethod("getInitialApplication").invoke(null);
            if (app instanceof Context) sCtx = (Context) app;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [sys] 取系统上下文失败 " + t);
        }
        return sCtx;
    }

    private static void loadConfig() {
        long now = System.currentTimeMillis();
        if (sPrefs != null && now - sLastLoad < 1000L) return;
        sLastLoad = now;
        try {
            if (sPrefs == null) {
                sPrefs = new XSharedPreferences(MODULE_PKG, PREF_NAME);
            } else {
                sPrefs.reload();
            }
            sEnabled = sPrefs.getBoolean("enabled", true);
            String t = sPrefs.getString("target_pkg", DEFAULT_TARGET);
            if (t != null && !t.trim().isEmpty() && !BILI_PKGS.contains(t.trim())) {
                sTarget = t.trim();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [sys] 读配置失败 " + t);
        }
    }
}
