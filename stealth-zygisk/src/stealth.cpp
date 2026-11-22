// BiliStealth —— 屏蔽 QQ 风控库对 /proc/self/smaps 的读取
//
// 背景：QQ 的风控库 libfekit.so 通过 fopen("/proc/self/smaps") 扫描进程内存映射，
//       用来检测 Xposed/LSPosed 注入痕迹。LSPosed 的 Java hook 拦不住 native fopen，
//       所以必须在 native 层做 PLT hook。
//
// 做法：Zygisk 注入 → 轮询等 libfekit.so 加载 → 解析它的 .rela.plt，
//       把 fopen 的 GOT 槽位改成本文件的 stealth_fopen，smaps 读取重定向到 /dev/null。
//
// 注：没有用 Zygisk 自带的 pltHookRegister —— 实测在 ZygiskNext 上
//     pltHookCommit 一直返回 false（库已加载、dev/inode 也对），不可靠。
//
// 思路参考 TCQT（https://github.com/callng/TCQT）。

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include "zygisk.hpp"
#include "plt_hook.h"

#define LOG_TAG "BiliStealth"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::Option;

namespace {

constexpr const char *TARGET_PKG = "com.tencent.mobileqq";
constexpr const char *TARGET_LIB = "libfekit.so";
constexpr const char *BLOCK_PATH = "/proc/self/smaps";

Api *g_api = nullptr;
FILE *(*g_orig_fopen)(const char *, const char *) = nullptr;
std::atomic<int> g_block_count{0};

// fopen 替身：libfekit 读 smaps 时改喂 /dev/null
FILE *stealth_fopen(const char *path, const char *mode) {
    if (g_orig_fopen == nullptr) {
        g_orig_fopen = reinterpret_cast<FILE *(*)(const char *, const char *)>(
                dlsym(RTLD_NEXT, "fopen"));
        if (g_orig_fopen == nullptr) return nullptr;
    }

    if (path != nullptr && strncmp(path, BLOCK_PATH, strlen(BLOCK_PATH)) == 0) {
        int n = ++g_block_count;
        if (n <= 5 || n % 100 == 0) {
            LOGI("intercept fopen(%s) -> /dev/null (count=%d)", path, n);
        }
        return g_orig_fopen("/dev/null", mode);
    }
    return g_orig_fopen(path, mode);
}

// 在已加载的 ELF 里找 libfekit.so
int find_lib_cb(struct dl_phdr_info *info, size_t /*size*/, void *data) {
    auto *out = static_cast<bool *>(data);
    if (info->dlpi_name != nullptr && strstr(info->dlpi_name, TARGET_LIB) != nullptr) {
        if (plt_patch_symbol(info, "fopen",
                             reinterpret_cast<void *>(&stealth_fopen),
                             reinterpret_cast<void **>(&g_orig_fopen))) {
            *out = true;
            return 1;  // 停止遍历
        }
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
            LOGI("PLT hook installed for %s (poll #%d)", TARGET_LIB, i);
            return;
        }
        if (i - lastLogged >= 250) {  // 每 10 秒提示一次还在等
            LOGI("still waiting for %s ... (poll #%d)", TARGET_LIB, i);
            lastLogged = i;
        }
        usleep(40 * 1000);
    }
    LOGE("%s not found / no fopen PLT slot within timeout", TARGET_LIB);
}

}  // namespace

class StealthModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        is_target_ = name != nullptr && strncmp(name, TARGET_PKG, strlen(TARGET_PKG)) == 0;
        if (is_target_) LOGI("preAppSpecialize: target process %s", name);
        env_->ReleaseStringUTFChars(args->nice_name, name);

        if (!is_target_) {
            api_->setOption(Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        if (!is_target_) return;
        g_api = api_;

        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        std::string proc = name != nullptr ? name : "?";
        env_->ReleaseStringUTFChars(args->nice_name, name);

        LOGI("postAppSpecialize[%s]: waiting for %s", proc.c_str(), TARGET_LIB);
        std::thread(wait_and_hook).detach();
    }

private:
    Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool is_target_ = false;
};

REGISTER_ZYGISK_MODULE(StealthModule)
