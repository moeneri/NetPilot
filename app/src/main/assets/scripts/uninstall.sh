#!/system/bin/sh
# Explicitly invoked by the app, a module uninstaller, or the user.
NP_DIR=/data/adb/netpilot
NP_MAIN=dev.netpilot.service.RootSupervisor.RootSupervisorMain
umask 077
mkdir -p "$NP_DIR/run"
touch "$NP_DIR/run/stopped"
if [ -f "$NP_DIR/runtime/netpilot.apk" ]; then
    CLASSPATH="$NP_DIR/runtime/netpilot.apk" app_process /system/bin --nice-name=netpilot-cleanup "$NP_MAIN" --request-cleanup
    NP_RESULT=$?
    # A failed restore preserves files and the cached APK for a later retry.
    [ "$NP_RESULT" = "0" ] || { echo 'Restore incomplete; retained NetPilot files.'; exit "$NP_RESULT"; }
fi
rm -f /data/adb/service.d/90-netpilot.sh
if [ -f /data/adb/modules/netpilot-guardian/module.prop ] && grep -q '^id=netpilot-guardian$' /data/adb/modules/netpilot-guardian/module.prop; then
    rm -rf /data/adb/modules/netpilot-guardian
fi
# Backup retention must be chosen before removal. Exported app backups are unaffected.
case "$NP_DIR" in /data/adb/netpilot) rm -rf "$NP_DIR" ;; *) exit 9 ;; esac
echo 'NetPilot guardian removed; owned system changes restored.'
