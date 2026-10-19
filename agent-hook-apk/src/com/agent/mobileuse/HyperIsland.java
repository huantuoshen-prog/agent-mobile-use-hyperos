package com.agent.mobileuse;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * Android glue for HyperOS / MIUI focus notifications (焦点通知) and the
 * OS3+ super island (超级岛).
 *
 * <p>Design constraints:
 * <ul>
 *   <li>The super island only renders when SystemUI accepts the notification as
 *       a focus notification. That requires <em>two</em> independent checks to
 *       pass: the app-side {@code canShowFocus} query and SystemUI's own
 *       signature / whitelist verification. The app cannot grant this itself;
 *       {@link #isIslandActive(Context)} therefore fails closed, and the hooks in
 *       {@code HookEntry} are what unlock it on a rooted/LSPosed device.</li>
 *   <li>Every payload is a plain JSON string in the {@code miui.focus.param}
 *       extra, built by {@link IslandPayloadBuilder}. Pictures and actions are
 *       separate Bundles; {@code miui.focus.pics} maps
 *       {@code miui.focus.pic_<name>} to {@link Icon}, and
 *       {@code miui.focus.actions} maps {@code miui.focus.action_<name>} to
 *       {@link Notification.Action}.</li>
 *   <li>The feature is opt-in: {@code agent_capsule_state/enable_super_island}
 *       defaults to false, so a HyperOS device gets the plain notification until
 *       the user enables it in SettingsActivity.</li>
 * </ul>
 *
 * <p>Everything is defensive: unknown ROMs, missing classes and denied provider
 * calls degrade to "no island" instead of breaking the notification.
 */
public final class HyperIsland {

    /** JSON payload extra (official key). */
    public static final String EXTRA_PARAM = "miui.focus.param";
    /** Icon bundle extra (official key). */
    public static final String EXTRA_PICS = "miui.focus.pics";
    /** Action bundle extra (official key). */
    public static final String EXTRA_ACTIONS = "miui.focus.actions";

    /** Preference that turns the island on; default false. */
    public static final String PREF_ENABLE = "enable_super_island";

    /** Extra carried by the island buttons' broadcast intents. */
    public static final String EXTRA_ISLAND_ACTION = "island_action";

    public static final String ACTION_STOP = "stop";
    public static final String ACTION_SWITCH_BACKGROUND = "background";
    public static final String ACTION_OPEN = "open";
    public static final String ACTION_CLEAR = "clear";
    public static final String ACTION_IGNORE = "ignore";

    private static final String TAG = "AgentHyperIsland";
    private static final String PREFS = "agent_capsule_state";
    private static final String PROP_ISLAND_FEATURE = "persist.sys.feature.island";
    private static final String FOCUS_PERMISSION_URI = "content://miui.statusbar.notification.public";
    private static final int PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? 0x04000000 : 0); // FLAG_IMMUTABLE

    private HyperIsland() {
    }

    // -------------------------------------------------------------- ROM probes

    /** True when this is a Xiaomi ROM (MIUI / HyperOS), regardless of island support. */
    public static boolean isHyperOs() {
        try {
            String miOs = getStringProperty("ro.mi.os.version.name");
            if (miOs != null && !miOs.trim().isEmpty()) {
                return true;
            }
            String miui = getStringProperty("ro.miui.ui.version.name");
            if (miui != null && !miui.trim().isEmpty()) {
                return true;
            }
            String manufacturer = Build.MANUFACTURER;
            return manufacturer != null && "xiaomi".equalsIgnoreCase(manufacturer.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when this ROM exposes the super-island machinery <em>and</em> the OS
     * says our package may post focus notifications.
     *
     * <p>Two independent facts are checked: the system feature flag and the
     * {@code canShowFocus} provider call. A false negative only costs the island;
     * it never breaks the normal notification.
     */
    public static boolean isIslandSupported(Context context) {
        try {
            if (!isHyperOs()) {
                return false;
            }
            if (!getBooleanProperty(PROP_ISLAND_FEATURE, false)) {
                return false;
            }
            return canShowFocus(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** App-side focus-notification permission query (the only official one). */
    public static boolean canShowFocus(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Bundle arg = new Bundle();
            arg.putString("package", context.getPackageName());
            Bundle result = context.getContentResolver().call(
                    android.net.Uri.parse(FOCUS_PERMISSION_URI), "canShowFocus", null, arg);
            boolean allowed = result != null && result.getBoolean("canShowFocus", false);
            android.util.Log.i(TAG, "canShowFocus(" + context.getPackageName() + ") = " + allowed);
            return allowed;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "canShowFocus query failed: " + t);
            return false;
        }
    }

    /** User switch (default off). */
    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_ENABLE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Gate used by every attach call: switch on + ROM supports + OS permits. */
    public static boolean isIslandActive(Context context) {
        boolean enabled = isEnabled(context);
        boolean active = enabled && isIslandSupported(context);
        if (!active) {
            boolean rom = isHyperOs();
            boolean feature = getBooleanProperty(PROP_ISLAND_FEATURE, false);
            android.util.Log.i(TAG, "island gate closed: pref=" + enabled + " hyperOs=" + rom
                    + " feature=" + feature + " canShowFocus=" + canShowFocus(context));
        }
        return active;
    }

    /**
     * Focus-notification protocol version: 1 = OS1, 2 = OS2, 3 = OS3 (super
     * island). We emit at least 2 and default to 3 because that is what a
     * HyperOS 3/4 device reports; an older ROM that only speaks OS2 would reject
     * protocol 3 and fall back to a normal notification, never crash.
     */
    public static int protocolVersion(Context context) {
        try {
            int detected = android.provider.Settings.System.getInt(
                    context.getContentResolver(), "notification_focus_protocol", 0);
            if (detected >= 3) {
                return 3;
            }
            if (detected == 2) {
                return 2;
            }
            return 3;
        } catch (Throwable t) {
            return 3;
        }
    }

    /** Readable tag used by the status card in SettingsActivity. */
    public static String describeState(Context context) {
        if (!isHyperOs()) {
            return "非 HyperOS";
        }
        if (!isEnabled(context)) {
            return "已关闭";
        }
        if (!isIslandSupported(context)) {
            return "系统未授权焦点通知";
        }
        return "已开启 (protocol " + protocolVersion(context) + ")";
    }

    // ------------------------------------------------------------- attachments

    /** Attach the running / foreground / background capsule island. */
    public static void attachCapsule(Context context, Notification.Builder builder,
                                     String stateTitle, String sessionTitle, String hint,
                                     Bitmap icon) {
        attachCapsule(context, builder, stateTitle, sessionTitle, hint, icon, false);
    }

    /**
     * Attach the capsule island. The capsule always uses the compact (text-only)
     * layout; when {@code virtualDisplayRunning} is true it also gets the rainbow
     * outer-glow effect (official {@code outEffectColor} / {@code outEffectSrc}).
     */
    public static void attachCapsule(Context context, Notification.Builder builder,
                                     String stateTitle, String sessionTitle, String hint,
                                     Bitmap icon, boolean virtualDisplayRunning) {
        if (!isIslandActive(context) || builder == null) {
            return;
        }
        try {
            IslandPayloadBuilder.ActionRef[] actions = new IslandPayloadBuilder.ActionRef[]{
                    new IslandPayloadBuilder.ActionRef(IslandPayloadBuilder.PIC_ACTION_STOP,
                            "停止", IslandPayloadBuilder.INTENT_BROADCAST),
                    new IslandPayloadBuilder.ActionRef(IslandPayloadBuilder.PIC_ACTION_SWITCH,
                            "切后台", IslandPayloadBuilder.INTENT_BROADCAST),
            };
            String glowPicKey = null;
            Bitmap glowBitmap = null;
            if (virtualDisplayRunning) {
                glowBitmap = createRainbowGlowBitmap(256);
                if (glowBitmap != null) {
                    glowPicKey = IslandPayloadBuilder.PIC_KEY_PREFIX
                            + IslandPayloadBuilder.PIC_EFFECT;
                }
            }
            String json = IslandPayloadBuilder.capsule(protocolVersion(context), stateTitle,
                    sessionTitle, hint, IslandPayloadBuilder.PIC_CAPSULE, true,
                    glowPicKey == null ? null : "#32ADE6", glowPicKey, actions);
            if (json == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString(EXTRA_PARAM, json);
            Icon iconObject = icon == null ? null : Icon.createWithBitmap(icon);
            Bundle pics = new Bundle();
            if (iconObject != null) {
                pics.putParcelable(IslandPayloadBuilder.PIC_KEY_PREFIX
                        + IslandPayloadBuilder.PIC_CAPSULE, iconObject);
            }
            if (glowBitmap != null) {
                pics.putParcelable(glowPicKey, Icon.createWithBitmap(glowBitmap));
            }
            if (!pics.isEmpty()) {
                extras.putBundle(EXTRA_PICS, pics);
            }
            Bundle actionBundle = new Bundle();
            if (iconObject != null) {
                actionBundle.putParcelable(IslandPayloadBuilder.ACTION_KEY_PREFIX
                                + IslandPayloadBuilder.PIC_ACTION_STOP,
                        buildBroadcastAction(context, iconObject, "停止", ACTION_STOP, 0));
                actionBundle.putParcelable(IslandPayloadBuilder.ACTION_KEY_PREFIX
                                + IslandPayloadBuilder.PIC_ACTION_SWITCH,
                        buildBroadcastAction(context, iconObject, "切后台",
                                ACTION_SWITCH_BACKGROUND, 0));
            }
            if (!actionBundle.isEmpty()) {
                extras.putBundle(EXTRA_ACTIONS, actionBundle);
            }
            builder.addExtras(extras);
            android.util.Log.i(TAG, "Super island capsule attached (" + json.length()
                    + " chars, compact, glow=" + virtualDisplayRunning + ")");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "attachCapsule failed: " + t.getMessage());
        }
    }

    /** Attach the task-completed island. */
    public static void attachCompleted(Context context, Notification.Builder builder,
                                       String title, String content, Bitmap icon,
                                       String sessionId) {
        if (!isIslandActive(context) || builder == null) {
            return;
        }
        try {
            IslandPayloadBuilder.ActionRef[] actions = new IslandPayloadBuilder.ActionRef[]{
                    new IslandPayloadBuilder.ActionRef(IslandPayloadBuilder.PIC_ACTION_OPEN,
                            "打开", IslandPayloadBuilder.INTENT_ACTIVITY),
                    new IslandPayloadBuilder.ActionRef(IslandPayloadBuilder.PIC_ACTION_CLEAR,
                            "清除", IslandPayloadBuilder.INTENT_BROADCAST),
            };
            String json = IslandPayloadBuilder.completed(protocolVersion(context), title, content,
                    IslandPayloadBuilder.PIC_CHECKMARK, actions);
            if (json == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString(EXTRA_PARAM, json);
            Icon iconObject = icon == null ? null : Icon.createWithBitmap(icon);
            Bundle pics = new Bundle();
            if (iconObject != null) {
                pics.putParcelable(IslandPayloadBuilder.PIC_KEY_PREFIX
                        + IslandPayloadBuilder.PIC_CHECKMARK, iconObject);
            }
            if (!pics.isEmpty()) {
                extras.putBundle(EXTRA_PICS, pics);
            }
            Bundle actionBundle = new Bundle();
            actionBundle.putParcelable(IslandPayloadBuilder.ACTION_KEY_PREFIX
                            + IslandPayloadBuilder.PIC_ACTION_OPEN,
                    buildActivityAction(context, "打开", sessionId));
            actionBundle.putParcelable(IslandPayloadBuilder.ACTION_KEY_PREFIX
                            + IslandPayloadBuilder.PIC_ACTION_CLEAR,
                    buildBroadcastAction(context, null, "清除", ACTION_CLEAR, 0));
            extras.putBundle(EXTRA_ACTIONS, actionBundle);
            builder.addExtras(extras);
            android.util.Log.i(TAG, "Super island completed attached (" + json.length() + " chars)");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "attachCompleted failed: " + t.getMessage());
        }
    }

    /** Attach the interactive question island. */
    public static void attachQuestion(Context context, Notification.Builder builder,
                                      String header, String questionText, Bitmap icon,
                                      String sessionId) {
        if (!isIslandActive(context) || builder == null) {
            return;
        }
        try {
            IslandPayloadBuilder.ActionRef[] actions = new IslandPayloadBuilder.ActionRef[]{
                    new IslandPayloadBuilder.ActionRef(IslandPayloadBuilder.PIC_ACTION_OPEN,
                            "打开", IslandPayloadBuilder.INTENT_ACTIVITY),
                    new IslandPayloadBuilder.ActionRef(IslandPayloadBuilder.PIC_ACTION_CLEAR,
                            "忽略", IslandPayloadBuilder.INTENT_BROADCAST),
            };
            String json = IslandPayloadBuilder.question(protocolVersion(context), header,
                    questionText, IslandPayloadBuilder.PIC_QUESTION, actions);
            if (json == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString(EXTRA_PARAM, json);
            Icon iconObject = icon == null ? null : Icon.createWithBitmap(icon);
            Bundle pics = new Bundle();
            if (iconObject != null) {
                pics.putParcelable(IslandPayloadBuilder.PIC_KEY_PREFIX
                        + IslandPayloadBuilder.PIC_QUESTION, iconObject);
            }
            if (!pics.isEmpty()) {
                extras.putBundle(EXTRA_PICS, pics);
            }
            Bundle actionBundle = new Bundle();
            actionBundle.putParcelable(IslandPayloadBuilder.ACTION_KEY_PREFIX
                            + IslandPayloadBuilder.PIC_ACTION_OPEN,
                    buildActivityAction(context, "打开", sessionId));
            actionBundle.putParcelable(IslandPayloadBuilder.ACTION_KEY_PREFIX
                            + IslandPayloadBuilder.PIC_ACTION_CLEAR,
                    buildBroadcastAction(context, null, "忽略", ACTION_IGNORE, 0));
            extras.putBundle(EXTRA_ACTIONS, actionBundle);
            builder.addExtras(extras);
            android.util.Log.i(TAG, "Super island question attached (" + json.length() + " chars)");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "attachQuestion failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ action utils

    private static Notification.Action buildBroadcastAction(Context context, Icon icon,
                                                            String title, String islandAction,
                                                            int requestCodeBase) {
        Intent intent = new Intent(NotifyReceiver.ACTION_ISLAND);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_ISLAND_ACTION, islandAction);
        PendingIntent pi = PendingIntent.getBroadcast(context,
                requestCodeBase + islandAction.hashCode(), intent, PENDING_FLAGS);
        Notification.Action.Builder b = new Notification.Action.Builder(icon, title, pi);
        return b.build();
    }

    private static Notification.Action buildActivityAction(Context context, String title,
                                                           String sessionId) {
        Intent intent = new Intent(context, DemoDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("skip_glow", true);
        if (sessionId != null && !sessionId.isEmpty()) {
            intent.putExtra("session_id", sessionId);
        }
        PendingIntent pi = PendingIntent.getActivity(context,
                21000 + (sessionId == null ? 0 : sessionId.hashCode()), intent, PENDING_FLAGS);
        Notification.Action.Builder b = new Notification.Action.Builder(null, title, pi);
        return b.build();
    }

    // ------------------------------------------------------------- bitmaps

    /**
     * Rainbow gradient bitmap used as {@code param_v2.outEffectSrc} so the island
     * gets the official outer-glow effect while the virtual display is running.
     * A gradient (instead of a flat {@code outEffectColor}) is what produces the
     * multi-colour ring; unknown ROMs simply ignore the key.
     */
    public static Bitmap createRainbowGlowBitmap(int size) {
        try {
            if (size <= 0) {
                size = 256;
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            int[] colors = new int[]{
                    Color.parseColor("#FF3B30"), // red
                    Color.parseColor("#FF9500"), // orange
                    Color.parseColor("#FFCC00"), // yellow
                    Color.parseColor("#34C759"), // green
                    Color.parseColor("#32ADE6"), // cyan
                    Color.parseColor("#5856D6"), // indigo
                    Color.parseColor("#AF52DE"), // purple
            };
            float[] stops = new float[]{0f, 0.17f, 0.33f, 0.5f, 0.67f, 0.83f, 1f};
            paint.setShader(new LinearGradient(0, 0, size, size, colors, stops,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, size, size, paint);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- system props

    private static String getStringProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class);
            Object value = get.invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean getBooleanProperty(String key, boolean defaultValue) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("getBoolean", String.class, boolean.class);
            Object value = get.invoke(null, key, defaultValue);
            return value instanceof Boolean ? ((Boolean) value) : defaultValue;
        } catch (Throwable t) {
            return defaultValue;
        }
    }
}
