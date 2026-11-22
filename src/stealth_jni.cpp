// BiliStealth（LSPosed 内嵌版）
//
// 原本是独立的 Zygisk 模块，现在编成 .so 打进 LSPosed 模块的 APK，
// 由模块在 QQ 进程里 System.load() 后调 start() 启动。
//
// 目的：QQ 的风控库 libfekit.so 会读 /proc/self/* 扫描注入痕迹。
// 这里把它的 fopen 换掉：
//   - /proc/self/smaps  → 直接给 /dev/null（纯检测用途）
//   - /proc/self/maps   → 过滤掉可疑行后给临时文件
//   - /proc/self/status → 过滤 TracerPid 等
//   - 其他 /proc/self/* → 只记日志（诊断用），内容原样转发

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
std::atomic<int> g_hit{0};
std::atomic<int> g_seen{0};
std::string g_lib_name;

// maps 里出现这些词的行走掉
const char *const kSuspicious[] = {
        "lsposed", "LSPosed", "lspd", "xposed", "Xposed",
        "biliminiredirect", "biliredirect",
        "zygisk", "Zygisk", "riru", "Riru",
        "magisk", "Magisk", "shamiko", "Shamiko",
        "kernelsu", "KernelSU", "apatch",
};

bool is_suspicious(const char *line) {
    for (const char *kw : kSuspicious) {
        if (strstr(line, kw) != nullptr) return true;
    }
    return false;
}

// 读原文件、滤掉可疑行、写进临时文件，返回临时文件的句柄
FILE *filtered(const char *path, const char *mode) {
    FILE *src = g_orig_fopen(path, "r");
    if (src == nullptr) return nullptr;

    FILE *tmp = tmpfile();
    if (tmp == nullptr) {
        fclose(src);
        return nullptr;
    }

    char line[8192];
    while (fgets(line, sizeof(line), src) != nullptr) {
        if (is_suspicious(line)) continue;
        fputs(line, tmp);
    }
    fclose(src);
    rewind(tmp);
    return tmp;
}

FILE *stealth_fopen(const char *path, const char *mode) {
    if (g_orig_fopen == nullptr) {
        g_orig_fopen = reinterpret_cast<FILE *(*)(const char *, const char *)>(
                dlsym(RTLD_NEXT, "fopen"));
        if (g_orig_fopen == nullptr) return nullptr;
    }

    if (path == nullptr) return g_orig_fopen(path, mode);

    // 只看 /proc/self/ 下的读取
    if (strncmp(path, "/proc/self/", 11) != 0) {
        return g_orig_fopen(path, mode);
    }

    int n = ++g_seen;
    if (n <= 40) {  // 诊断：记录它到底读了哪些文件
        LOGI("probe: %s", path);
    }

    if (strncmp(path, "/proc/self/smaps", 16) == 0) {
        int h = ++g_hit;
        if (h <= 5 || h % 100 == 0) LOGI("block smaps -> /dev/null (count=%d)", h);
        return g_orig_fopen("/dev/null", mode);
    }

    if (strncmp(path, "/proc/self/maps", 15) == 0 ||
        strncmp(path, "/proc/self/status", 17) == 0 ||
        strncmp(path, "/proc/self/mountinfo", 20) == 0) {
        int h = ++g_hit;
        if (h <= 5 || h % 100 == 0) LOGI("filter %s (count=%d)", path, h);
        return filtered(path, mode);
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
    constexpr int kMaxPolls = 1500;  // 1500 * 20ms = 30 秒
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
        usleep(20 * 1000);
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
Java_io_github_memorimonita_biliminiredirect_Stealth_nativeStart(
        JNIEnv *env, jclass /*clazz*/, jstring libName, jstring /*blockPath*/) {
    if (g_orig_fopen != nullptr) return;  // 已经启动过
    g_lib_name = jstr(env, libName);
    if (g_lib_name.empty()) g_lib_name = "libfekit.so";

    LOGI("nativeStart: lib=%s", g_lib_name.c_str());
    std::thread(wait_and_hook).detach();
}
