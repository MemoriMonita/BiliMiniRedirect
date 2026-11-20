package com.cx.biliredirect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 把 QQ / 微信里「开启哔哩哔哩应用」的跳转重定向到第三方客户端（默认 PiliPlus）。
 *
 * QQ 的跳转链路（classes.dex 反编译确认）：
 *   JefsClass.startActivity(Context,Intent)      [PUBLIC STATIC]
 *     -> checkAndDoSync(Context,Intent,Runnable) [PUBLIC]
 *        -> checkAndDoAsyn(...)                   [PUBLIC]
 *           -> intercept(Context,Intent,CancelableRunnable,Interceptor) [PRIVATE]
 *
 * 所以 hook 点选公开的 startActivity / checkAndDoSync（签名稳定），
 * 命中后自己发起跳转并 setResult(null) 掐掉原流程（含「即将离开QQ」弹窗）。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "BiliRedirect";
    private static final String MODULE_PKG = "com.cx.biliredirect";
    private static final String PREF_NAME = "config";
    private static final String DEFAULT_TARGET = "com.example.piliplus";

    /** 宿主：QQ 系 + 微信（微信里跳 B 站同理）。 */
    private static final Set<String> HOST_PKGS = new HashSet<>(Arrays.asList(
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.tencent.qqlite",
            "com.tencent.mobileqqi",
            "com.tencent.mm"));

    /** 官方 B 站系 App 的包名，命中即视为「要跳官方 App」。 */
    private static final Set<String> BILI_PKGS = new HashSet<>(Arrays.asList(
            "tv.danmaku.bili",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.in",
            "com.bilibili.bilithings",
            "com.bilibili.app.blue"));

    private static XSharedPreferences sPrefs;
    private static long sLastLoad;
    private static boolean sEnabled = true;
    private static String sTargetPkg = DEFAULT_TARGET;
    private static boolean sImplicitToo = true;
    private static boolean sSkipDialog = true;
    private static boolean sLogAll = false;

    /**
     * 只在这些进程里 hook。
     *
     * QQ 的风控（TuringFD + minidetect.so）在主进程里扫 /proc/self/maps 找注入痕迹，
     * 主进程一旦被注入就有被踢下线的风险。而小程序跳转发生在 :mini 系列进程，
     * 主进程根本不需要 hook —— 所以这里按进程名白名单，主进程直接不注入。
     *
     * 微信同理：跳转发生在 :appbrand 系列进程。
     */
    private static final String[] QQ_PROC_SUFFIX = {":mini", ":mini1", ":mini2", ":mini3"};
    private static final String[] WX_PROC_SUFFIX = {":appbrand", ":appbrand0", ":appbrand1", ":appbrand2"};

    private static boolean shouldHookProcess(String pkg, String proc) {
        if (proc == null) return false;
        // 微信
        if ("com.tencent.mm".equals(pkg)) {
            for (String s : WX_PROC_SUFFIX) {
                if (proc.endsWith(s)) return true;
            }
            return false;
        }
        // QQ 系：只 hook 小程序容器进程
        for (String s : QQ_PROC_SUFFIX) {
            if (proc.endsWith(s)) return true;
        }
        return false;
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!HOST_PKGS.contains(lp.packageName)) return;

        if (!shouldHookProcess(lp.packageName, lp.processName)) {
            // 主进程等：不 hook，避免被风控扫到
            XposedBridge.log(TAG + ": 跳过 " + lp.packageName + " / " + lp.processName
                    + "（非小程序进程，不注入 hook）");
            // 自检：模块 APK 是否仍出现在本进程的 maps 里（LSPosed 加载模块就会留痕）
            XposedBridge.log(TAG + ": [自检] " + lp.processName + " maps 里的模块痕迹 = "
                    + selfTrace());
            return;
        }

        XposedBridge.log(TAG + ": 注入 " + lp.packageName + " / " + lp.processName);

        hookJefsClass(lp);
        hook(lp, "android.app.Instrumentation", "execStartActivity", "Instrumentation");
        hook(lp, "android.content.ContextWrapper", "startActivity", "ContextWrapper");
        hook(lp, "android.app.ContextImpl", "startActivity", "ContextImpl");
    }

    /** 读 /proc/self/maps，看有没有我们自己的包名 / LSPosed 痕迹。取证用。 */
    private static String selfTrace() {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase();
                if (low.contains("biliredirect")) {
                    sb.append("[模块APK]");
                    n++;
                } else if (low.contains("lspd") || low.contains("lsposed")) {
                    sb.append("[LSPosed]");
                    n++;
                }
                if (n > 6) break;
            }
        } catch (Throwable t) {
            return "读取失败 " + t;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
        return sb.length() == 0 ? "无（干净）" : sb.toString();
    }

    private void hook(LoadPackageParam lp, String cls, String method, String label) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, lp.classLoader);
            XposedBridge.hookAllMethods(c, method, new StartHook(label));
            XposedBridge.log(TAG + ": hook " + cls + "#" + method + " OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + cls + "#" + method + " 失败 " + t);
        }
    }

    /**
     * QQ 的跳转风控类。只 hook 公开方法（签名稳定）：
     *   startActivity(Context, Intent) / (Context, Intent, Bundle)  —— 静态入口
     *   checkAndDoSync(Context, Intent, Runnable)                   —— 实例入口
     */
    private void hookJefsClass(LoadPackageParam lp) {
        Class<?> jefs;
        try {
            jefs = XposedHelpers.findClass(
                    "com.tencent.mobileqq.haoliyou.JefsClass", lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 无 JefsClass（非 QQ 或版本不同）" + t);
            return;
        }

        int n = 0;
        for (Method m : jefs.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            boolean isStart = m.getName().equals("startActivity")
                    && p.length >= 2 && p[0] == Context.class && p[1] == Intent.class;
            boolean isSync = m.getName().equals("checkAndDoSync")
                    && p.length == 3 && p[0] == Context.class && p[1] == Intent.class;
            if (!isStart && !isSync) continue;

            XposedBridge.hookMethod(m, new JefsHook(m.getName()));
            XposedBridge.log(TAG + ": hook JefsClass#" + m.getName()
                    + "(" + p.length + " 参) OK");
            n++;
        }
        if (n == 0) {
            XposedBridge.log(TAG + ": JefsClass 里没找到可 hook 的方法，退回通用 hook");
        }
    }

    // ---------------------------------------------------------------- config

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
            if (t != null && !t.trim().isEmpty()) sTargetPkg = t.trim();
            sImplicitToo = sPrefs.getBoolean("implicit_too", true);
            sSkipDialog = sPrefs.getBoolean("skip_dialog", true);
            sLogAll = sPrefs.getBoolean("log_all", false);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读配置失败 " + t);
        }
    }

    // ----------------------------------------------------------------- hook

    /** QQ 的 JefsClass 入口。 */
    static class JefsHook extends XC_MethodHook {
        private final String name;

        JefsHook(String name) {
            this.name = name;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            loadConfig();
            if (!sEnabled) return;

            Context ctx = null;
            Intent src = null;
            for (Object a : param.args) {
                if (ctx == null && a instanceof Context) ctx = (Context) a;
                else if (src == null && a instanceof Intent) src = (Intent) a;
            }
            if (ctx == null || src == null) return;

            if (sLogAll) XposedBridge.log(TAG + " [Jefs." + name + "] " + describe(src));
            if (!shouldRedirect(src)) return;

            Intent repl = buildRedirect(ctx, src);
            if (repl == null) return;

            XposedBridge.log(TAG + " [Jefs." + name + "] " + describe(src) + "  =>  " + describe(repl));

            if (!sSkipDialog) {
                // 只换目标，保留 QQ 的确认弹窗
                replaceIntent(param.args, src, repl);
                return;
            }

            // 自己发起跳转，再掐掉原流程（原流程里的 Runnable 捕获的是旧 Intent，
            // 改 args 对它无效，所以必须自己跳）。
            try {
                ctx.startActivity(repl);
                param.setResult(null);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 自行跳转失败，放行原流程 " + t);
            }
        }
    }

    /** 通用兜底：Instrumentation / ContextWrapper / ContextImpl。 */
    static class StartHook extends XC_MethodHook {
        private final String label;

        StartHook(String label) {
            this.label = label;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            loadConfig();
            if (!sEnabled) return;

            Intent src = findIntent(param.args);
            if (src == null) return;
            if (sLogAll) XposedBridge.log(TAG + " [" + label + "] " + describe(src));
            if (!shouldRedirect(src)) return;

            Context ctx = findContext(param);
            if (ctx == null) return;

            Intent repl = buildRedirect(ctx, src);
            if (repl == null) return;

            replaceIntent(param.args, src, repl);
            XposedBridge.log(TAG + " [" + label + "] " + describe(src) + "  =>  " + describe(repl));
        }
    }

    private static Intent findIntent(Object[] args) {
        for (Object a : args) {
            if (a instanceof Intent) return (Intent) a;
        }
        return null;
    }

    private static void replaceIntent(Object[] args, Intent old, Intent repl) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] == old) args[i] = repl;
        }
    }

    private static Context findContext(XC_MethodHook.MethodHookParam param) {
        if (param.thisObject instanceof Context) return (Context) param.thisObject;
        for (Object a : param.args) {
            if (a instanceof Context) return (Context) a;
        }
        return null;
    }

    // ------------------------------------------------------------- redirect

    /** 取证用：把 Intent 的关键字段摊平，方便在 LSPosed 日志里看清 QQ 到底发了什么。 */
    private static String describe(Intent it) {
        try {
            return "pkg=" + it.getPackage()
                    + " comp=" + it.getComponent()
                    + " action=" + it.getAction()
                    + " data=" + it.getData()
                    + " flags=" + Integer.toHexString(it.getFlags());
        } catch (Throwable t) {
            return "describe 失败 " + t;
        }
    }

    private static boolean shouldRedirect(Intent it) {
        String pkg = it.getPackage();
        ComponentName cn = it.getComponent();
        if (cn != null) pkg = cn.getPackageName();

        if (pkg != null) {
            // 显式指定了别的 App：只接管官方 B 站
            return BILI_PKGS.contains(pkg);
        }

        // 隐式 Intent：看 data 是不是 B 站链接
        if (!sImplicitToo) return false;
        Uri d = it.getData();
        if (d == null) return false;
        String s = d.toString().toLowerCase();
        return s.startsWith("bilibili://")
                || s.contains("bilibili.com")
                || s.contains("bilibili.cn")
                || s.contains("b23.tv");
    }

    private static Intent buildRedirect(Context ctx, Intent src) {
        if (sTargetPkg == null || sTargetPkg.isEmpty()) return null;
        if (BILI_PKGS.contains(sTargetPkg)) return null;

        try {
            PackageManager pm = ctx.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(sTargetPkg);
            if (launch == null) {
                XposedBridge.log(TAG + ": 目标未安装 " + sTargetPkg);
                return null;
            }

            Uri data = src.getData();
            if (data != null) {
                if (src.getType() != null) {
                    launch.setDataAndType(data, src.getType());
                } else {
                    launch.setData(data);
                }
            }
            Bundle ex = src.getExtras();
            if (ex != null) launch.putExtras(ex);

            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return launch;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 构造跳转失败 " + t);
            return null;
        }
    }
}
