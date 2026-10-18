#!/system/bin/sh
# 实际执行体：由 install-recovery-2.sh 以 nohup 后台拉起，避免被 flash_recovery 服务生命周期掐死
LOG=/data/local/tmp/tp-boot.log
echo "=== bootfix-worker $(date) ===" >> $LOG

i=0
while [ $i -lt 90 ]; do
    done_flag=$(getprop sys.boot_completed 2>/dev/null)
    if [ "$done_flag" = "1" ]; then break; fi
    sleep 1
    i=$((i+1))
done
sleep 5

wm overscan 0,0,0,100 >> $LOG 2>&1
echo "overscan rc=$?" >> $LOG
wm density 133 >> $LOG 2>&1
echo "density rc=$?" >> $LOG

/data/local/tmp/tpi2c write /data/local/tmp/y500.bin >> $LOG 2>&1
echo "tpi2c rc=$?" >> $LOG

# 黑条兜底拉起（BOOT_COMPLETED 若被 stopped-state 拦截；重启后 toggle 复位=显示）
am start -n com.shilapi.blackbar/.MainActivity >> $LOG 2>&1
echo "blackbar rc=$?" >> $LOG
echo "=== bootfix-worker done ===" >> $LOG
