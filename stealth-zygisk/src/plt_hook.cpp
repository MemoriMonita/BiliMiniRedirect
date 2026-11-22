// 简易 PLT/GOT hook：只处理标准 .rela.plt（Android 常见格式）
//
// 为什么不直接用 Zygisk 的 pltHookRegister？
//   实测在 ZygiskNext 上 pltHookCommit 一直返回 false（库已加载、dev/inode 也对），
//   说明那套实现不可靠。TCQT 也是因此自己实现的 PLT hook。

#include <link.h>
#include <elf.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>

#include "plt_hook.h"

namespace {

int g_page_size = 0;

// 让 [addr, addr+len) 可写，返回原始保护（失败返回 -1）
int make_writable(void *addr, size_t len) {
    if (g_page_size == 0) g_page_size = static_cast<int>(sysconf(_SC_PAGESIZE));
    uintptr_t start = reinterpret_cast<uintptr_t>(addr) & ~static_cast<uintptr_t>(g_page_size - 1);
    uintptr_t end = (reinterpret_cast<uintptr_t>(addr) + len + g_page_size - 1)
                    & ~static_cast<uintptr_t>(g_page_size - 1);
    // 先按 RW 打开（不关心原权限，hook 完按 RW 留着也安全：GOT 本来就要可写）
    if (mprotect(reinterpret_cast<void *>(start), end - start,
                 PROT_READ | PROT_WRITE) != 0) {
        return -1;
    }
    return 0;
}

}  // namespace

bool plt_patch_symbol(struct dl_phdr_info *info,
                      const char *symbol,
                      void *replacement,
                      void **original) {
    if (info == nullptr || symbol == nullptr || replacement == nullptr) return false;
    if (info->dlpi_addr == 0 && info->dlpi_phdr == nullptr) return false;

    const ElfW(Dyn) *dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const auto &ph = info->dlpi_phdr[i];
        if (ph.p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const ElfW(Dyn) *>(info->dlpi_addr + ph.p_vaddr);
            break;
        }
    }
    if (dyn == nullptr) return false;

    const char *strtab = nullptr;
    const ElfW(Sym) *symtab = nullptr;
    const ElfW(Rela) *jmprel = nullptr;
    size_t pltrelsz = 0;
    bool is_rela = true;

    for (const ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
            case DT_STRTAB:
                strtab = reinterpret_cast<const char *>(info->dlpi_addr + d->d_un.d_ptr);
                break;
            case DT_SYMTAB:
                symtab = reinterpret_cast<const ElfW(Sym) *>(info->dlpi_addr + d->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = reinterpret_cast<const ElfW(Rela) *>(info->dlpi_addr + d->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = d->d_un.d_val;
                break;
            case DT_PLTREL:
                is_rela = (d->d_un.d_val == DT_RELA);
                break;
            default:
                break;
        }
    }

    // 只支持 RELA（aarch64 标准）
    if (!is_rela || strtab == nullptr || symtab == nullptr ||
        jmprel == nullptr || pltrelsz == 0) {
        return false;
    }

    const size_t count = pltrelsz / sizeof(ElfW(Rela));
    const size_t sym_limit = ELF64_R_SYM(~0ULL);
    int patched = 0;

    for (size_t i = 0; i < count; ++i) {
        const ElfW(Rela) &r = jmprel[i];
        if (ELF64_R_TYPE(r.r_info) != R_AARCH64_JUMP_SLOT) continue;

        const size_t sym_idx = ELF64_R_SYM(r.r_info);
        if (sym_idx >= sym_limit) continue;

        const char *name = strtab + symtab[sym_idx].st_name;
        if (name == nullptr || strcmp(name, symbol) != 0) continue;

        auto **slot = reinterpret_cast<void **>(info->dlpi_addr + r.r_offset);
        if (*slot == replacement) return true;  // 已经改过

        if (make_writable(slot, sizeof(void *)) != 0) return false;
        if (original != nullptr && *original == nullptr) *original = *slot;
        __atomic_store_n(slot, replacement, __ATOMIC_RELEASE);
        if (__atomic_load_n(slot, __ATOMIC_ACQUIRE) != replacement) return false;
        ++patched;
    }

    return patched > 0;
}
