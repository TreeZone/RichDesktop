#!/system/bin/sh
# ============================================================
# 只做一件事：给本机 seapp_contexts 补一条 uid 1000 的兜底规则
#
# 原生规则只有:
#     user=system seinfo=platform domain=system_app type=system_app_data_file
# 它要求 seinfo=platform，而 seinfo 来自签名证书映射。
# 后装 APK 用的是自己的证书 → seinfo=default → 没有规则匹配
# → zygote 报 "No match for app with uid 1000" → SIGABRT。
#
# 补一条不带 seinfo 条件的规则，让 uid 1000 直接进 system_app 域。
# 不碰系统分区定值，按本机现有文件重建，ROM 升级也不会错位。
# ============================================================

command -v ui_print >/dev/null 2>&1 || ui_print() { echo "$@"; }

SRC=/system/etc/selinux/plat_seapp_contexts
DST=$MODPATH/system/etc/selinux/plat_seapp_contexts
RULE="user=system domain=system_app type=system_app_data_file"

mkdir -p "$MODPATH/system/etc/selinux"

if [ ! -f "$SRC" ]; then
    ui_print "! 找不到 $SRC，模块无法生效"
    abort 1 2>/dev/null || exit 1
fi

# 已经补过就原样拷贝，避免重复追加
if grep -q "^$RULE$" "$SRC"; then
    cp -f "$SRC" "$DST"
    ui_print "- 规则已存在，直接沿用"
else
    LINE=$(grep -n "^user=system seinfo=platform" "$SRC" | head -1 | cut -d: -f1)
    if [ -n "$LINE" ]; then
        head -n $((LINE - 1)) "$SRC" > "$DST"
        echo "$RULE" >> "$DST"
        tail -n +"$LINE" "$SRC" >> "$DST"
        ui_print "- 已在第 $LINE 行前插入兜底规则"
    else
        cp -f "$SRC" "$DST"
        echo "$RULE" >> "$DST"
        ui_print "- 未找到 system 规则，已追加到末尾"
    fi
fi

if grep -q "^$RULE$" "$DST"; then
    ui_print "- seapp_contexts 就绪（$(wc -l < "$DST") 行）"
else
    ui_print "! 写入失败"
    exit 1
fi

# 顺手修正已存在的数据目录标签（未安装则跳过，post-fs-data 每次开机也会补）
for D in /data/user/0/com.cz.richdesk /data/data/com.cz.richdesk; do
    if [ -d "$D" ]; then
        chcon -R u:object_r:system_app_data_file:s0 "$D" 2>/dev/null
        ui_print "- 已修正数据目录标签: $D"
        break
    fi
done

ui_print "- 重启后生效。APK 保持普通安装，不需要进 priv-app"
