#!/system/bin/sh
# T3 开机钩子：SuperSU install-recovery.sh 会执行本文件（root 身份）。
# 只负责把实际执行体后台化，避免被服务生命周期终止。
nohup /system/etc/bootfix-worker.sh >/dev/null 2>&1 &
