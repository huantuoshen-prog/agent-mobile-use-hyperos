#!/system/bin/sh
MODDIR="/data/adb/modules/agent_mobile_use"
if [ ! -d "$MODDIR" ]; then
    MODDIR="$(cd "$(dirname "$0")/.." && pwd)"
fi

DEX_PATH="$MODDIR/bin/agent_vd.dex"
if [ ! -f "$DEX_PATH" ]; then
    DEX_PATH="/data/local/tmp/agent_vd.dex"
fi

# Auto detect physical screen metrics
WIDTH=1080
HEIGHT=2400
DPI=420

PHYS_SIZE=$(/system/bin/wm size 2>/dev/null | grep "Physical size:" | awk '{print $3}')
if [ -n "$PHYS_SIZE" ]; then
    W=$(echo "$PHYS_SIZE" | cut -d'x' -f1)
    H=$(echo "$PHYS_SIZE" | cut -d'x' -f2)
    if [ -n "$W" ] && [ -n "$H" ]; then
        WIDTH="$W"
        HEIGHT="$H"
    fi
fi

# Fix Issue #14: Prefer Override density over Physical density
OVERRIDE_DPI=$(/system/bin/wm density 2>/dev/null | grep "Override density:" | awk '{print $3}')
if [ -n "$OVERRIDE_DPI" ]; then
    DPI="$OVERRIDE_DPI"
else
    PHYS_DPI=$(/system/bin/wm density 2>/dev/null | grep "Physical density:" | awk '{print $3}')
    if [ -n "$PHYS_DPI" ]; then
        DPI="$PHYS_DPI"
    fi
fi

# Allow override from CLI args if provided
if [ -n "$1" ] && [ -n "$2" ]; then
    WIDTH="$1"
    HEIGHT="$2"
fi
if [ -n "$3" ]; then
    DPI="$3"
fi

echo "[run_daemon] Auto detected screen metrics: ${WIDTH}x${HEIGHT} @ ${DPI} DPI"

export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export ANDROID_ART_ROOT=/apex/com.android.art
export ANDROID_I18N_ROOT=/apex/com.android.i18n
export ANDROID_TZDATA_ROOT=/apex/com.android.tzdata

# ---------------------------------------------------------------------------
# BOOTCLASSPATH discovery (AOSP / ColorOS / HyperOS-MIUI)
# ---------------------------------------------------------------------------
# app_process started outside zygote needs the runtime boot class path in the
# environment or the VM aborts during startup.  Upstream hard-coded a
# ColorOS/QCOM list: on HyperOS several of those jars are missing and the MIUI
# jars are absent, so discover the real value instead.

bootclasspath_from_environ() {
    if [ -r "$1" ]; then
        tr '\0' '\n' < "$1" 2>/dev/null | grep -m1 '^BOOTCLASSPATH=' | cut -d= -f2-
    fi
}

bootclasspath_from_rc() {
    for rc in /system/etc/init/hw/init.environ.rc /init.environ.rc; do
        if [ -r "$rc" ]; then
            line=$(grep -m1 'BOOTCLASSPATH' "$rc" 2>/dev/null)
            if [ -n "$line" ]; then
                echo "$line" | sed -e 's/.*BOOTCLASSPATH[[:space:]=]*//' -e "s/[\"']//g" -e 's/[[:space:]]*$//'
            fi
        fi
    done
}

if [ -z "$BOOTCLASSPATH" ]; then
    # 1) A live process environment is the most authoritative source.
    for pidfile in /proc/1/environ \
                   /proc/$(pidof zygote64 2>/dev/null)/environ \
                   /proc/$(pidof zygote 2>/dev/null)/environ; do
        CANDIDATE=$(bootclasspath_from_environ "$pidfile")
        if [ -n "$CANDIDATE" ]; then
            BOOTCLASSPATH="$CANDIDATE"
            echo "[run_daemon] BOOTCLASSPATH <- $pidfile"
            break
        fi
    done
fi

if [ -z "$BOOTCLASSPATH" ]; then
    # 2) init.environ.rc (Android 14+ moved it under /system/etc/init/hw).
    CANDIDATE=$(bootclasspath_from_rc | head -n1)
    if [ -n "$CANDIDATE" ]; then
        BOOTCLASSPATH="$CANDIDATE"
        echo "[run_daemon] BOOTCLASSPATH <- init.environ.rc"
    fi
fi

if [ -z "$BOOTCLASSPATH" ]; then
    # 3) Last resort: candidate list filtered by existence on this ROM.
    BOOT_JARS=""
    for jar in /apex/com.android.art/javalib/core-oj.jar \
               /apex/com.android.art/javalib/core-libart.jar \
               /apex/com.android.art/javalib/okhttp.jar \
               /apex/com.android.art/javalib/bouncycastle.jar \
               /apex/com.android.art/javalib/apache-xml.jar \
               /apex/com.android.i18n/javalib/core-icu4j.jar \
               /system/framework/framework.jar \
               /system/framework/framework-graphics.jar \
               /system/framework/framework-location.jar \
               /system/framework/ext.jar \
               /system/framework/telephony-common.jar \
               /system/framework/voip-common.jar \
               /system/framework/ims-common.jar \
               /system/framework/miui-framework.jar \
               /system/framework/framework-ondeviceintelligence-platform.jar \
               /system/framework/framework-nfc.jar \
               /system/framework/oplus-framework.jar; do
        [ -e "$jar" ] && BOOT_JARS="$BOOT_JARS:$jar"
    done
    BOOTCLASSPATH=$(echo "$BOOT_JARS" | sed 's/^://')
    echo "[run_daemon] BOOTCLASSPATH <- fallback jar list"
fi

if [ -z "$BOOTCLASSPATH" ]; then
    echo "[run_daemon] FATAL: cannot determine BOOTCLASSPATH; aborting before app_process" >&2
    exit 1
fi

if [ -z "$DEX2OATBOOTCLASSPATH" ]; then
    DEX2OATBOOTCLASSPATH="$BOOTCLASSPATH"
fi
export BOOTCLASSPATH DEX2OATBOOTCLASSPATH

export CLASSPATH="$DEX_PATH"
exec /system/bin/app_process /system/bin com.agent.DaemonMain "$WIDTH" "$HEIGHT" "$DPI"
