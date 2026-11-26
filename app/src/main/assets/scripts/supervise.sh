#!/system/bin/sh
NP_DIR=/data/adb/netpilot
NP_LOCK="$NP_DIR/run/launcher.lock"
NP_NAME=netpilot-supervisor
NP_MAIN=dev.netpilot.service.RootSupervisor.RootSupervisorMain
umask 077
mkdir -p "$NP_DIR/run" "$NP_DIR/logs"
if ! mkdir "$NP_LOCK" 2>/dev/null; then
    NP_OLD=$(cat "$NP_LOCK/pid" 2>/dev/null)
    case "$NP_OLD" in ''|*[!0-9]*) NP_OLD=0 ;; esac
    if [ "$NP_OLD" -gt 0 ] && [ -r "/proc/$NP_OLD/cmdline" ]; then
        if tr '\000' ' ' <"/proc/$NP_OLD/cmdline" | grep -q '/data/adb/netpilot/supervise.sh'; then
            echo "LAUNCHER_ALREADY_RUNNING pid=$NP_OLD"
            exit 0
        fi
    fi
    rm -f "$NP_LOCK/pid"
    rmdir "$NP_LOCK" 2>/dev/null || exit 0
    mkdir "$NP_LOCK" 2>/dev/null || exit 0
fi
echo $$ >"$NP_LOCK/pid"
trap 'rm -f "$NP_LOCK/pid"; rmdir "$NP_LOCK" 2>/dev/null' EXIT
# Framework services may not exist at post-fs-data time. This child never blocks service.d.
NP_WAIT=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$NP_WAIT" -lt 36 ]; do
    sleep 5
    NP_WAIT=$((NP_WAIT + 1))
done
[ "$(getprop sys.boot_completed)" = "1" ] || { echo 'Framework boot wait expired'; exit 3; }
NP_ATTEMPTS=0
NP_DELAY=5
while [ "$NP_ATTEMPTS" -lt 6 ] && [ ! -f "$NP_DIR/run/stopped" ]; do
    if ! pm path --user 0 dev.netpilot 2>/dev/null | grep -q '^package:'; then
        CLASSPATH="$NP_DIR/runtime/netpilot.apk" /system/bin/app_process /system/bin --nice-name="$NP_NAME-cleanup" "$NP_MAIN" --cleanup
        exit $?
    fi
    if [ "$(wc -c <"$NP_DIR/logs/launcher.log" 2>/dev/null)" -gt 524288 ]; then
        mv "$NP_DIR/logs/launcher.log" "$NP_DIR/logs/launcher.previous.log"
        : >"$NP_DIR/logs/launcher.log"
    fi
    NP_STARTED=$(date +%s)
    echo "$NP_STARTED SUPERVISOR_START attempt=$((NP_ATTEMPTS + 1))"
    CLASSPATH="$NP_DIR/runtime/netpilot.apk" /system/bin/app_process /system/bin --nice-name="$NP_NAME" "$NP_MAIN" --daemon >>"$NP_DIR/logs/launcher.log" 2>&1
    NP_EXIT=$?
    [ "$NP_EXIT" = "0" ] && exit 0
    [ -f "$NP_DIR/run/stopped" ] && exit 0
    # Exit 75 is an explicit restart or successful APK update, not a crash.
    # Keep planned restarts outside the failure budget and reconnect promptly.
    if [ "$NP_EXIT" = "75" ]; then
        NP_ATTEMPTS=0
        NP_DELAY=5
        echo 'PLANNED_RESTART delay=1s'
        sleep 1
        continue
    fi
    NP_LIFETIME=$(( $(date +%s) - NP_STARTED ))
    [ "$NP_LIFETIME" -gt 600 ] && { NP_ATTEMPTS=0; NP_DELAY=5; }
    NP_ATTEMPTS=$((NP_ATTEMPTS + 1))
    echo "Supervisor exit=$NP_EXIT recovery=$NP_ATTEMPTS delay=${NP_DELAY}s"
    sleep "$NP_DELAY"
    NP_DELAY=$((NP_DELAY * 2))
    [ "$NP_DELAY" -gt 160 ] && NP_DELAY=160
done
echo 'Recovery budget exhausted; open NetPilot to inspect logs and restart.'
exit 4
