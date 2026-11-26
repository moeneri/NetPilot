#!/system/bin/sh
NP_DIR=/data/adb/netpilot
umask 077
mkdir -p "$NP_DIR/run" "$NP_DIR/logs" || exit 2
chmod 700 "$NP_DIR" "$NP_DIR/run" "$NP_DIR/logs" || exit 2
NP_LOG="$NP_DIR/logs/launcher.log"
printf '%s START_REQUEST pid=%s mode=%s\n' "$(date +%s)" "$$" "${1:-manual}" >>"$NP_LOG"
[ -r "$NP_DIR/runtime/netpilot.apk" ] || { echo 'RUNTIME_APK_MISSING_OR_UNREADABLE' >>"$NP_LOG"; exit 2; }
[ -r "$NP_DIR/supervise.sh" ] || { echo 'SUPERVISE_SCRIPT_MISSING_OR_UNREADABLE' >>"$NP_LOG"; exit 2; }
if [ "$1" = "--boot" ]; then
    [ -f "$NP_DIR/boot.enabled" ] || exit 0
    [ ! -f "$NP_DIR/run/stopped" ] || exit 0
else
    rm -f "$NP_DIR/run/stopped"
fi
if [ -x /system/bin/setsid ]; then
    nohup /system/bin/setsid /system/bin/sh "$NP_DIR/supervise.sh" "$1" </dev/null >>"$NP_LOG" 2>&1 &
else
    nohup /system/bin/sh "$NP_DIR/supervise.sh" "$1" </dev/null >>"$NP_LOG" 2>&1 &
fi
printf '%s LAUNCHER_REQUESTED pid=%s\n' "$(date +%s)" "$!" >>"$NP_LOG"
exit 0
