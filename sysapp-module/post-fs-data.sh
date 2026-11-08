#!/system/bin/sh
# 开机两件事，都在 zygote 启动之前做完：
#
# 1) 给 seapp_contexts 补一条不带 seinfo 的规则，让 uid 1000 的后装应用能进 system_app 域。
#    为什么不用模块的 system/ 目录覆盖：部分 ROM（本机 HyperOS 13）不会挂载模块对
#    /system/etc 的覆盖，模块文件躺在 /data/adb/modules 里但系统看不见。
#    post-fs-data 阶段我们有 root 且早于 zygote，直接 bind mount 一定生效。
#
# 2) 修正 PipDesk 数据目录的 SELinux 标签：PMS 给 uid 1000 的应用打的是
#    system_data_file，而 system_app 域只能写 system_app_data_file（否则 mkdir 报 EACCES，
#    SharedPreferences 存不住，Dock 绑定会丢）。重装 APK 后标签会复位，所以每次开机补。

RULE="user=system domain=system_app type=system_app_data_file"
SRC=/system/etc/selinux/plat_seapp_contexts
WORK=/data/adb/pipdesk

mkdir -p "$WORK" 2>/dev/null

# ---------------- 1. seapp_contexts ----------------
if [ -f "$SRC" ]; then
    if grep -qx "$RULE" "$SRC"; then
        : # 已经是补过的（模块覆盖生效或上次已挂），不用动
    else
        cp -f "$SRC" "$WORK/seapp_base" 2>/dev/null
        if [ -s "$WORK/seapp_base" ]; then
            awk -v rule="$RULE" '
                BEGIN { done = 0 }
                /^user=system seinfo=platform domain=system_app/ && !done {
                    print rule; done = 1
                }
                { print }
                END { if (!done) print rule }
            ' "$WORK/seapp_base" > "$WORK/seapp_patched"
            chmod 644 "$WORK/seapp_patched" 2>/dev/null
            chcon u:object_r:system_file:s0 "$WORK/seapp_patched" 2>/dev/null
            if mount -o bind "$WORK/seapp_patched" "$SRC" 2>/dev/null; then
                echo "pipdesk: seapp_contexts patched (bind mount)"
            else
                echo "pipdesk: seapp_contexts bind mount FAILED"
            fi
        fi
    fi
fi

# ---------------- 2. 数据目录标签 ----------------
for D in /data/user/0/com.cz.richdesk /data/data/com.cz.richdesk; do
    [ -d "$D" ] || continue
    if ls -Zd "$D" 2>/dev/null | grep -q "system_app_data_file"; then
        break
    fi
    chcon -R u:object_r:system_app_data_file:s0 "$D" 2>/dev/null
    echo "pipdesk: data dir relabeled $D"
    break
done
