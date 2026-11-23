package io.github.memorimonita.sysprobe;

import android.os.Process;
import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 注入探针：不 hook 任何方法，只证明 LSPosed 有没有把模块装进 system_server。
 *
 * 期望日志（LSPosed 管理器 → 日志）：
 *   SYS_PROBE/zygote：zygote 阶段加载（说明模块本身被 LSPosed 认了）
 *   SYS_PROBE/system：system_server 进程里 alive（说明「系统框架」作用域真的生效）
 *
 * 只看后一行。没有它 = system scope 注入不通，方案 C（hook system_server）就不可行。
 */
public class SysProbe implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "SYS_PROBE";

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log(TAG + "/zygote: loaded, module=" + startupParam.modulePath
                + ", pid=" + Process.myPid());
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        // system_server 在 Xposed 里叫 "android"
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + "/system: system_server alive, pid=" + Process.myPid()
                + ", uptime=" + SystemClock.elapsedRealtime() + "ms"
                + ", isFirstApplication=" + lpparam.isFirstApplication);
    }
}
