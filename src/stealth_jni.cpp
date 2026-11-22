// BiliStealth（LSPosed 内嵌版）
//
// 原本是独立的 Zygisk 模块，现在编成 .so 打进 LSPosed 模块的 APK，
// 由模块在 QQ 进程里 System.load() 后调 start() 启动。
// 好处：只需要装一个 APK，不用刷 Magisk 模块、不用完整重启。
//
// 作用：QQ 的风控库 libfekit.so 会用 fopen("/proc/self/smaps") 扫描内存映射
//       检测注入痕迹。这里把它的 GOT 里 fopen 槽位改掉，smaps 读取重定向到
//       /dev/null，让风控读不到东西。
//
// 注：没有用 Zygisk 自带的 pltHookRegister —— 实测在 ZygiskNext 上
//     pltHookCommit 一直返回 false。这里自己解析 .rela.plt 改 GOT。

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include "plt_hook.h"

#define LOG_TAG "BiliStealth"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

FILE *(*g_orig_fopen)(const char *, const char *) = nullptr;
std::atomic<int> g_block_count{0};
std::string g_block_path;
std::string g_lib_name;

// fopen 替身：目标库读 smaps 时改喂 /dev/null
FILE *stealth_fopen(const char *path, const char *mode) {
    if (g_orig_fopen == nullptr) {
        g_orig_fopen = reinterpret_cast<FILE *(*)(const char *, const char *)>(
                dlsym(RTLD_NEXT, "fopen"));
        if (g_orig_fopen == nullptr) return nullptr;
    }

    if (path != nullptr && !g_block_path.empty() &&
        strncmp(path, g_block_path.c_str(), g_block_path.size()) == 0) {
        int n = ++g_block_count;
        if (n <= 5 || n % 100 == 0) {
            LOGI("intercept fopen(%s) -> /dev/null (count=%d)", path, n);
        }
        return g_orig_fopen("/dev/null", mode);
    }
    return g_orig_fopen(path, mode);
}

int find_lib_cb(struct dl_phdr_info *info, size_t /*size*/, void *data) {
    auto *done = static_cast<bool *>(data);
    if (info->dlpi_name == nullptr) return 0;
    if (g_lib_name.empty() || strstr(info->dlpi_name, g_lib_name.c_str()) == nullptr) {
        return 0;
    }
    if (plt_patch_symbol(info, "fopen",
                         reinterpret_cast<void *>(&stealth_fopen),
                         reinterpret_cast<void **>(&g_orig_fopen))) {
        *done = true;
        return 1;
    }
    return 0;
}

void wait_and_hook() {
    constexpr int kMaxPolls = 1500;  // 1500 * 40ms = 60 秒
    int lastLogged = -1;

    for (int i = 0; i < kMaxPolls; ++i) {
        bool ok = false;
        dl_iterate_phdr(find_lib_cb, &ok);
        if (ok) {
            LOGI("PLT hook installed for %s (poll #%d)", g_lib_name.c_str(), i);
            return;
        }
        if (i - lastLogged >= 250) {
            LOGI("still waiting for %s ... (poll #%d)", g_lib_name.c_str(), i);
            lastLogged = i;
        }
        usleep(40 * 1000);
    }
    LOGE("%s not found / no fopen PLT slot within timeout", g_lib_name.c_str());
}

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c != nullptr ? c : "";
    if (c != nullptr) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cx_biliredirect_Stealth_nativeStart(JNIEnv *env, jclass /*clazz*/,
                                             jstring libName, jstring blockPath) {
    if (g_orig_fopen != nullptr) return;  // 已经启动过
    g_lib_name = jstr(env, libName);
    g_block_path = jstr(env, blockPath);
    if (g_lib_name.empty()) g_lib_name = "libfekit.so";
    if (g_block_path.empty()) g_block_path = "/proc/self/smaps";

    LOGI("nativeStart: lib=%s block=%s", g_lib_name.c_str(), g_block_path.c_str());
    std::thread(wait_and_hook).detach();
}
