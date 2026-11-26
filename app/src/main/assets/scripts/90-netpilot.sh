#!/system/bin/sh
# Magisk service.d / module service.sh entry point. Only the child waits for framework readiness.
NP_DIR=/data/adb/netpilot
[ -r "$NP_DIR/start.sh" ] || exit 0
umask 077
mkdir -p "$NP_DIR/logs" || exit 2
(
    NP_WAIT=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$NP_WAIT" -lt 36 ]; do
        sleep 5
        NP_WAIT=$((NP_WAIT + 1))
    done
    [ "$(getprop sys.boot_completed)" = "1" ] || { echo 'BOOT_FRAMEWORK_WAIT_EXPIRED'; exit 3; }
    # Also clean a stopped/disabled installation after the APK was removed outside the app.
    if ! pm path --user 0 dev.netpilot 2>/dev/null | grep -q '^package:'; then
        /system/bin/sh "$NP_DIR/uninstall.sh"
        exit $?
    fi
    [ -f "$NP_DIR/boot.enabled" ] || exit 0
    [ ! -f "$NP_DIR/run/stopped" ] || exit 0
    /system/bin/sh "$NP_DIR/start.sh" --boot
) </dev/null >>"$NP_DIR/logs/launcher.log" 2>&1 &
exit 0
