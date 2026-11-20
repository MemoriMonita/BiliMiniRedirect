# Bili小程序重定向

还在为群友转发的B站视频在QQ小程序只能跳转到官方 App而麻烦吗？此模块可以跳转到第三方客户端！

默认目标 **PiliPlus** 。别的客户端改个包名也能用。

---

## 为什么得写模块

QQ 小程序点「打开APP」时，走的是**包名定向**的 Intent，不是 scheme 解析：

| 路径 | 实际行为 |
|---|---|
| `AppJsPlugin.startApp` 带 path | `new ComponentName(packagename, path)` + `setComponent()` |
| `AppJsPlugin.startApp` 不带 path | `getLaunchIntentForPackage(pkg)`（带 `setPackage`） |
| `openScheme` | 起 QQ 自己的 `AppBrandLaunchUI` 中转，再自己 startActivity |

Intent 里已经写死了包名，系统根本不会去 resolve 候选者。所以「把 PiliPlus 设为 bilibili.com 的默认应用」「`pm set-app-links`」这类办法**在架构上没有介入点**。

那个「即将离开QQ」弹窗也是 QQ 进程内的 `QQCustomDialog`（`JefsClass#setupDialog`，埋点页名 `pg_bas_jump_out_qq_window`），不是系统弹窗。

只能在宿主进程内 hook。

---

## 怎么做的

QQ 的跳转链路（从 QQ 的 `classes.dex` 反编译确认）：

```
JefsClass.startActivity(Context,Intent)        [PUBLIC STATIC]
  └→ checkAndDoSync(Context,Intent,Runnable)   [PUBLIC]
       └→ checkAndDoAsyn(...)                  [PUBLIC]
            └→ intercept(Context,Intent,CancelableRunnable,Interceptor)  [PRIVATE]
```

hook 点选公开的 `startActivity` / `checkAndDoSync`（签名稳定），不碰 private 的 `intercept`：

1. `JefsClass#startActivity(Context, Intent)` 和 `(Context, Intent, Bundle)` —— 静态入口
2. `JefsClass#checkAndDoSync(Context, Intent, Runnable)` —— 实例入口
3. `android.app.Instrumentation#execStartActivity` —— 所有 Activity 启动的收口，兜住绕过 JefsClass 的路径
4. `ContextWrapper` / `ContextImpl#startActivity` —— 直接调 Context 的那批

命中后自己 `ctx.startActivity(重定向后的 Intent)` 并 `setResult(null)` 掐掉原流程，连「即将离开QQ」弹窗一起跳过。

> 为什么不能只改 `param.args` 里的 Intent：原流程那个 `Runnable` 是调用方构造的闭包，
> 里面捕获的是旧 Intent 的引用，改参数对它无效。所以必须自己发起跳转 + 掐掉原流程。

重定向时保留原始 `data` URI，所以能直达对应视频（PiliPlus 的 Manifest 声明了 `bilibili://video`、`bilibili://bangumi/season*`、`https://*.bilibili.com` 等）。

### 只在小程序进程里 hook（重要）

QQ 的风控（腾讯 TuringFD + `minidetect.so`）在**主进程**里扫 `/proc/self/maps`、`/proc/self/status`、`/proc/self/mountinfo` 找注入痕迹，主进程被注入就有被踢下线的风险。

而小程序跳转发生在 `:mini` 系列进程，主进程根本不需要 hook。所以模块按**进程名白名单**决定是否注入：

| 进程 | 是否 hook |
|---|---|
| `com.tencent.mobileqq`（主进程） | ❌ 只打日志，不挂任何 hook |
| `:MSF` / `:tool` / `:qzone` / `:peak` / `:privileged_process1` / `:gameservice` | ❌ 同上 |
| `:mini` / `:mini1` / `:mini2` / `:mini3` | ✅ 注入 |
| `com.tencent.mm:appbrand*`（微信小程序） | ✅ 注入 |

实测主进程的 `/proc/self/maps` 里**看不到模块 APK 路径**（LSPosed 是内存加载 dex，不是 mmap 文件），所以 TuringFD 的 maps 扫描扫不到。

> ⚠ LSPosed 的作用域是**按应用**勾选的，没有「按进程排除」功能。
> 所以 QQ 的作用域**必须保持勾选**，靠模块自己的进程白名单来避免污染主进程。
> 如果取消勾选 QQ，模块根本不会被加载，功能直接失效。

### 实测

小米 25060RK16C / HyperOS 3.0 / Android 16，QQ 小程序里点视频，LSPosed 日志：

```
# 主进程：只跳过，不 hook
(com.tencent.mobileqq)      BiliRedirect: 跳过 com.tencent.mobileqq（非小程序进程，不注入 hook）
(com.tencent.mobileqq)      BiliRedirect: [自检] maps 里的模块痕迹 = 无（干净）

# 小程序进程：正常注入
(com.tencent.mobileqq:mini) BiliRedirect: 注入 ... hook JefsClass#checkAndDoSync(3 参) OK

# 拦截生效
(com.tencent.mobileqq:mini) BiliRedirect [Jefs.checkAndDoSync]
  pkg=tv.danmaku.bili  data=bilibili://video/BV1iSYs6SE4G?page=0&bsource=xcx&...
  =>  pkg=com.example.piliplus  comp=com.example.piliplus/.MainActivity
      data=bilibili://video/BV1iSYs6SE4G?page=0&bsource=xcx&...
```

PiliPlus 直接在前台打开，没有弹「即将离开QQ」确认框，BV 号与全部参数原样保留。

---

## 安装

1. 装 `BiliMiniRedirect.apk`（[Releases](../../releases) 页有）
2. LSPosed 管理器 → 模块 → 启用「B站跳转重定向」
3. **作用域勾选**：
   - `QQ`（`com.tencent.mobileqq`）—— 必须，LSPosed 勾应用即覆盖 `:mini` 等子进程
   - `微信`（`com.tencent.mm`）—— 可选，微信里跳 B 站同理
4. 强制停止 QQ（设置 → 应用 → QQ → 强制停止），或重启手机
5. 打开模块 App，确认「目标应用包名」= `com.example.piliplus`，保存

---

## 设置项

| 项 | 默认 | 说明 |
|---|---|---|
| 启用重定向 | 开 | 总开关 |
| 同时接管隐式链接 | 开 | `bilibili://`、`b23.tv`、`*.bilibili.com` 这类没写死包名的 Intent |
| 跳过「即将离开QQ」确认弹窗 | 开 | 关掉则保留 QQ 的确认框，只换目标 |
| 日志取证 | 关 | 记录所有跳转 Intent，排查用 |
| 目标应用包名 | `com.example.piliplus` | 换成别的第三方客户端就填它的包名 |

---

## 排查

打开「日志取证」，复现一次跳转，去 **LSPosed 管理器 → 日志** 看 `BiliRedirect` 标签：

```
BiliRedirect: 跳过 com.tencent.mobileqq / com.tencent.mobileqq（非小程序进程，不注入 hook）
BiliRedirect: [自检] com.tencent.mobileqq maps 里的模块痕迹 = 无（干净）
BiliRedirect: 注入 com.tencent.mobileqq / com.tencent.mobileqq:mini
BiliRedirect: hook JefsClass#startActivity(2 参) OK
BiliRedirect: hook JefsClass#checkAndDoSync(3 参) OK
BiliRedirect [Jefs.checkAndDoSync] pkg=tv.danmaku.bili ...  =>  pkg=com.example.piliplus ...
```

- **一条都没有** → 作用域没勾上（QQ 必须勾），或 QQ 没重启
- **只有「跳过」没有「注入」** → 正常，说明你还没打开小程序；`:mini` 进程要打开小程序才会创建
- **只有「全部」没有「=>」** → `shouldRedirect` 没命中，把日志发 issue
- **有「=>」但没跳过去** → 看有没有 `自行跳转失败`

---

## 已知限制

- QQ 版本更新后 `JefsClass` 可能改名/改签名 → 日志会打「JefsClass 里没找到可 hook 的方法，退回通用 hook」，此时靠第 3/4 层兜底（但会保留 QQ 的确认弹窗）
- HyperOS 的「链式启动管理」（`com.miui.securitycenter`）可能在系统层再弹一次确认，本模块不处理
- 只接管 B 站系包名：`tv.danmaku.bili` / `tv.danmaku.bilibilihd` / `com.bilibili.app.in` / `com.bilibili.bilithings` / `com.bilibili.app.blue`
- **主进程仍会被 LSPosed 加载模块**（只是不挂 hook）。maps 里看不到痕迹，但 LSPosed 注入本身在 ART 层有残留，理论上仍可能被更强的检测发现。如果还是被踢，需要改成 hook `system_server`（`ActivityStarter.executeRequest`），让 QQ 进程一个模块都不注入

---

## 自己构建

不依赖 Gradle / Android Studio，只用 `aapt2` + `javac` + `d8` + `zipalign` + `apksigner`。

```bash
ANDROID_SDK=~/Android/Sdk \
XPOSED_API=~/libs/api-82.jar \
JAVA_HOME=/path/to/jdk17 \
bash build.sh
```

需要：
- Android SDK，含 `build-tools/36.0.0/` 与 `platforms/android-36/android.jar`
- [Xposed API 82](https://api.xposed.info/)（`api-82.jar`）
- JDK 17+

签名密钥默认读 `keystore.jks`（不存在会自动生成），可用 `KEYSTORE` / `KEYSTORE_PASS` / `KEYSTORE_ALIAS` 覆盖。

> Windows 上火绒 HIPS 会拦截 `aapt2.exe` / `zipalign.exe` 写文件（报「数据无效 (13)」），
> `build.sh` 里已把它们拷成 `_bt_aapt.exe` / `_bt_align.exe` 规避。

---

## 相关项目

- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) —— 第三方 B 站客户端，本模块默认目标、也是实测用的那个。感谢作者。
- [LSPosed](https://github.com/LSPosed/LSPosed) —— 模块框架。
- [QAuxiliary](https://github.com/cinit/QAuxiliary) —— 它的 `JumpController` 是 hook `JefsClass` 的早期实践，本模块的 hook 点选择参考了它。

---

## 许可

MIT
