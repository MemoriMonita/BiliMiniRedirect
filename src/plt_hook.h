#pragma once

#include <link.h>

// 把 info 对应 ELF 里所有指向 symbol 的 PLT 槽位改成 replacement。
// 成功改写至少一个槽位返回 true；original 只在首次成功时写入原值。
bool plt_patch_symbol(struct dl_phdr_info *info,
                      const char *symbol,
                      void *replacement,
                      void **original);
