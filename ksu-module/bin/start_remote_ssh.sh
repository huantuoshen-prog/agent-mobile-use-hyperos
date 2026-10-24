#!/system/bin/sh
# Opt-in boot hook for an already configured Termux OpenSSH transport.
# No key generation, authentication changes, package install, or public bind.
set -eu
STATE=/data/adb/agent_mobile_use
[ -f "$STATE/remote-ssh-enabled" ] || exit 0
TERMUX_APP=/data/user/0/com.termux
TERMUX_HOME=/data/data/com.termux/files/home
TERMUX_PREFIX=/data/data/com.termux/files/usr
SSH_DIR="$TERMUX_HOME/.ssh/mobile-use"
CONFIG="$SSH_DIR/sshd_config"
PIDFILE="$SSH_DIR/sshd.pid"
if [ "${1:-}" = "--wait-for-unlock" ]; then
    # At service start the CE property can be empty; treat it as locked.
    while [ -f "$STATE/remote-ssh-enabled" ]; do
        [ "$(getprop sys.user.0.ce_available)" = true ] && [ -f "$CONFIG" ] && break
        sleep 2
    done
    [ -f "$STATE/remote-ssh-enabled" ] || exit 0
fi
[ -x "$TERMUX_PREFIX/bin/sshd" ] && [ -f "$CONFIG" ] || {
    echo "Remote SSH requires configured Termux OpenSSH and .ssh/mobile-use/sshd_config"
    exit 1
}
APP_UID=$(stat -c %u "$TERMUX_APP")
case "$APP_UID" in ''|*[!0-9]*) exit 1;; esac
[ "$APP_UID" -ge 10000 ] || exit 1
if [ -f "$PIDFILE" ]; then
    SSH_PID=$(cat "$PIDFILE")
    case "$SSH_PID" in ''|*[!0-9]*) SSH_PID=0;; esac
    if [ "$SSH_PID" -gt 1 ] && [ -r "/proc/$SSH_PID/cmdline" ] &&
       [ "$(readlink "/proc/$SSH_PID/exe")" = "$TERMUX_PREFIX/bin/sshd" ] &&
       tr '\000' '\n' < "/proc/$SSH_PID/cmdline" | grep -Fxq "$CONFIG"; then
        echo "Remote SSH already running"
        exit 0
    fi
fi
# Files written by a root setup helper need the app's SELinux categories.
restorecon -RF "$SSH_DIR" >/dev/null 2>&1
SSH_COMMAND="HOME=$TERMUX_HOME PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin:/system/bin LD_LIBRARY_PATH=$TERMUX_PREFIX/lib $TERMUX_PREFIX/bin/sshd -f $CONFIG"
su -g "$APP_UID" -G 3003 "$APP_UID" -c "$SSH_COMMAND -t"
su -g "$APP_UID" -G 3003 "$APP_UID" -c "$SSH_COMMAND"
echo "Remote SSH started; access policy is defined by the existing SSH configuration"
