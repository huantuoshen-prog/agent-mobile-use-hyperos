package com.agent.mobileuse;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;

public class GlowService extends Service {
    public static final String ACTION_TOUCH = "com.agent.mobileuse.ACTION_TOUCH";

    private static final String CHANNEL_ID = "agent_capsule_channel_v3";
    private static final int NOTIFICATION_ID = 10086;
    private WindowManager mWindowManager;
    private GlowView mGlowView;
    private boolean mIsShowing = false;
    private BroadcastReceiver mTouchReceiver;
    private Handler mMainHandler;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        try {
            android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
            String savedMode = sp.getString("mode", "");
            if (!savedMode.isEmpty() && !"STOP".equals(savedMode)) {
                String savedSid = sp.getString("session_id", "");
                String savedTitle = sp.getString("session_title", "");
                android.util.Log.i("AgentGlowService", "onCreate auto-restoring capsule from crash/recents kill: mode=" + savedMode);
                promoteToForeground(savedMode, savedSid, savedTitle);
                if ("FOREGROUND".equals(savedMode)) {
                    showGlow();
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Create crisp vector-drawn Single Cyber Blue Eye with 100% transparent background (no circle, no background).
     */
    public static Bitmap createSingleBlueEyeBitmap(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float cx = size / 2.0f;
        float cy = size / 2.0f;

        // 100% Transparent background (no white circle, no dark circle, purely transparent)
        int cyan = 0xFF00D2FF;

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(cyan);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(size * 0.08f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(cyan);
        fillPaint.setStyle(Paint.Style.FILL);

        float w = size * 0.32f;
        float h = size * 0.18f;

        // 1. Upper eyelid arc
        android.graphics.Path upperPath = new android.graphics.Path();
        upperPath.moveTo(cx - w, cy - h * 0.1f);
        upperPath.quadTo(cx, cy - h * 1.5f, cx + w, cy - h * 0.1f);
        canvas.drawPath(upperPath, strokePaint);

        // 2. Lower eyelid arc
        android.graphics.Path lowerPath = new android.graphics.Path();
        float lowerW = w * 0.65f;
        lowerPath.moveTo(cx - lowerW, cy + h * 0.65f);
        lowerPath.quadTo(cx, cy + h * 1.25f, cx + lowerW, cy + h * 0.65f);
        canvas.drawPath(lowerPath, strokePaint);

        // 3. Center Pupil
        float pupilR = size * 0.09f;
        canvas.drawCircle(cx, cy + h * 0.05f, pupilR, fillPaint);

        // 4. Subtle white glint / reflection
        Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        whitePaint.setColor(0xFFFFFFFF);
        whitePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx - pupilR * 0.32f, cy - pupilR * 0.25f, pupilR * 0.32f, whitePaint);

        return bitmap;
    }

    /**
     * Create crisp vector-drawn Cyber Terminal (>_) in vivid cyber cyan/blue (#00D2FF) with 100% transparent background.
     */
    public static Bitmap createCyberTerminalBitmap(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float cx = size / 2.0f;
        float cy = size / 2.0f;

        // Vivid Cyber Cyan / Blue (#00D2FF) - matches takeover eye icon
        int cyberCyan = 0xFF00D2FF;

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(cyberCyan);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(size * 0.08f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        float w = size * 0.74f;
        float h = size * 0.54f;
        float r = size * 0.12f;

        // 1. Terminal window frame
        RectF frame = new RectF(cx - w / 2.0f, cy - h / 2.0f, cx + w / 2.0f, cy + h / 2.0f);
        canvas.drawRoundRect(frame, r, r, strokePaint);

        // 2. Terminal prompt chevron `>`
        android.graphics.Path path = new android.graphics.Path();
        float pX1 = cx - w * 0.22f;
        float pY1 = cy - h * 0.20f;
        float pX2 = cx - w * 0.05f;
        float pY2 = cy;
        float pX3 = cx - w * 0.22f;
        float pY3 = cy + h * 0.20f;

        path.moveTo(pX1, pY1);
        path.lineTo(pX2, pY2);
        path.lineTo(pX3, pY3);
        canvas.drawPath(path, strokePaint);

        // 3. Terminal cursor `_`
        float cX1 = cx + w * 0.06f;
        float cX2 = cx + w * 0.25f;
        float cY = cy + h * 0.20f;
        canvas.drawLine(cX1, cY, cX2, cY, strokePaint);

        return bitmap;
    }

    private void promoteToForeground(String mode, String sid, String sessionTitle) {
        getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE).edit()
                .putString("mode", mode).putString("session_id", sid == null ? "" : sid)
                .putString("session_title", sessionTitle == null ? "" : sessionTitle).apply();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Dismiss completed notification if the same session resumes running / takeover
            if (sid != null && !sid.isEmpty()) {
                try {
                    android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
                    String lastCompletedSid = sp.getString("last_completed_sid", "");
                    if (sid.equals(lastCompletedSid)) {
                        nm.cancel(NotifyReceiver.DEFAULT_TAG, NotifyReceiver.DEFAULT_ID);
                        sp.edit().remove("last_completed_sid").apply();
                        android.util.Log.i("AgentGlowService", "Auto-dismissed completed notification for resumed session: " + sid);
                    }
                } catch (Throwable ignored) {}
            }

            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    java.lang.reflect.Method deleteMethod = nm.getClass().getMethod("deleteNotificationChannel", String.class);
                    deleteMethod.invoke(nm, "agent_capsule_channel_v2");
                } catch (Throwable ignored) {}

                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                java.lang.reflect.Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                Object channel = ctor.newInstance(CHANNEL_ID, "Agent Foreground Activity", 3);
                try {
                    java.lang.reflect.Method setSoundMethod = channelClass.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class);
                    setSoundMethod.invoke(channel, null, null);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Method enableVibrationMethod = channelClass.getMethod("enableVibration", boolean.class);
                    enableVibrationMethod.invoke(channel, false);
                } catch (Throwable ignored) {}
                java.lang.reflect.Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);
            }

            Notification.Builder builder = new Notification.Builder(this);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    java.lang.reflect.Method setChannelMethod = builder.getClass().getMethod("setChannelId", String.class);
                    setChannelMethod.invoke(builder, CHANNEL_ID);
                } catch (Throwable ignored) {}
            }

            String displayTitle = (sessionTitle != null && !sessionTitle.trim().isEmpty()) ? sessionTitle.trim() : null;

            // Compact Capsule Right Ear (3~4 chars): "前台接管", "后台接管", "运行中"
            String capsuleTitle;
            String cardHeader;
            String bigText;
            Bitmap iconBitmap;

            if ("FOREGROUND".equals(mode)) {
                capsuleTitle = AgentState.title("foreground");
                cardHeader = (displayTitle != null) ? displayTitle : "Agent 正在前台接管";
                bigText = "点击打开 DSHA 会话。";
                iconBitmap = createSingleBlueEyeBitmap(192);
            } else if ("BACKGROUND".equals(mode)) {
                capsuleTitle = AgentState.title("background");
                cardHeader = (displayTitle != null) ? displayTitle : "Agent 正在后台副屏运行";
                bigText = "点击打开副屏监控。";
                iconBitmap = AgentIcons.create("background", 192);
            } else if ("WAITING".equals(mode)) {
                capsuleTitle = AgentState.title("waiting");
                cardHeader = displayTitle != null ? displayTitle : "等待你的回答";
                bigText = "打开 DSHA 回答后继续";
                iconBitmap = AgentIcons.create("waiting", 192);
            } else {
                // RUNNING
                capsuleTitle = AgentState.title("running");
                cardHeader = (displayTitle != null) ? displayTitle : "Agent 正在运行中";
                bigText = "点击进入会话。";
                iconBitmap = AgentIcons.create("running", 192);
            }

            android.content.SharedPreferences livePrefs = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
            String liveDetail = livePrefs.getString("live_detail", "");
            if (!liveDetail.isEmpty()) bigText = liveDetail;
            int total = livePrefs.getInt("live_total", 0);
            int completed = livePrefs.getInt("live_completed", 0);
            builder.setContentTitle(capsuleTitle);
            builder.setContentText(bigText);
            builder.setColor(Color.parseColor(AgentState.color(AgentState.fromMode(mode))));
            if (total > 0) builder.setProgress(total, Math.min(completed, total), false);

            Icon capsuleIcon = null;
            if (Build.VERSION.SDK_INT >= 23 && iconBitmap != null) {
                capsuleIcon = Icon.createWithBitmap(iconBitmap);
                builder.setSmallIcon(capsuleIcon);
                builder.setLargeIcon(iconBitmap);
            } else {
                builder.setSmallIcon(R.drawable.dsh_whale_icon);
            }

            // Oplus Fluid Cloud Icon slot & personalization
            android.content.SharedPreferences spPref = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
            boolean enableFluidCloud = spPref.getBoolean("enable_fluid_cloud", true);
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean("enable_fluid_cloud", enableFluidCloud);
            if (enableFluidCloud && capsuleIcon != null) {
                extras.putParcelable("oplus_small_icon", capsuleIcon);
            }
            builder.addExtras(extras);

            // Eliminate Android 12+ 10-second FGS notification deferral (FOREGROUND_SERVICE_IMMEDIATE = 1)
            try {
                java.lang.reflect.Method setBehavior = builder.getClass().getMethod("setForegroundServiceBehavior", int.class);
                setBehavior.invoke(builder, 1);
            } catch (Throwable ignored) {}

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= 0x04000000; // FLAG_IMMUTABLE
            }

            if ("FOREGROUND".equals(mode)) {
                // Foreground: Click pendingIntent -> Handoff to background
                Intent handoffIntent = getPackageManager().getLaunchIntentForPackage("com.dsh.client");
                if (handoffIntent == null) handoffIntent = new Intent(this, DemoDialogActivity.class);
                handoffIntent.putExtra("open_web", true);
                PendingIntent pi = PendingIntent.getActivity(this, 2028, handoffIntent, flags);
                builder.setContentIntent(pi);
            } else if ("BACKGROUND".equals(mode) && !"dsha".equals(livePrefs.getString("display_backend", ""))) {
                // root 副屏打开模块监控页；DSHA 副屏进入宿主会话。
                Intent consoleIntent = new Intent(this, DemoDialogActivity.class);
                // NO_ANIMATION is best-effort only; ColorOS runs its capsule-expand remote
                // transition regardless. skip_glow is what actually avoids the stretched rim.
                consoleIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                consoleIntent.putExtra("skip_glow", true);
                consoleIntent.putExtra("target_url", "http://127.0.0.1:3070/");
                if (sid != null && !sid.isEmpty()) {
                    consoleIntent.putExtra("session_id", sid);
                }
                int reqCode = 2030;
                PendingIntent pi = PendingIntent.getActivity(this, reqCode, consoleIntent, flags);
                builder.setContentIntent(pi);
            } else {
                // Running (idle session active, etc.): Click pendingIntent -> Open DemoDialogActivity (Web Console) targeting specific session!
                Intent consoleIntent = getPackageManager().getLaunchIntentForPackage("com.dsh.client");
                if (consoleIntent == null) consoleIntent = new Intent(this, DemoDialogActivity.class);
                consoleIntent.putExtra("open_web", true);
                // NO_ANIMATION: see the BACKGROUND branch above.
                consoleIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                consoleIntent.putExtra("skip_glow", true);
                if (sid != null && !sid.isEmpty()) {
                    consoleIntent.putExtra("session_id", sid);
                }
                int reqCode = (sid != null && !sid.isEmpty()) ? sid.hashCode() : 2029;
                PendingIntent pi = PendingIntent.getActivity(this, reqCode, consoleIntent, flags);
                builder.setContentIntent(pi);
            }

            builder.setOngoing(true);
            // HyperOS 会把 promoted ongoing 转成 liveupdate 模板，覆盖 miui.focus 的按钮与外圈。
            try { builder.getClass().getMethod("setRequestPromotedOngoing", boolean.class).invoke(builder, !HyperIsland.isHyperOs()); }
            catch (Throwable ignored) {}
            builder.setAutoCancel(false);
            builder.setOnlyAlertOnce(true);
            builder.setDefaults(0);
            builder.setSound(null);
            builder.setVibrate(null);
            builder.setPriority(2); // Notification.PRIORITY_MAX = 2
            builder.setShowWhen(true);

            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(cardHeader);
            bigStyle.bigText(bigText);
            builder.setStyle(bigStyle);

            // HyperOS / MIUI focus-notification super island (opt-in, no-op elsewhere).
            HyperIsland.addLiveActions(this, builder, capsuleIcon);
            boolean virtualDisplayRunning = "BACKGROUND".equals(mode);
            boolean questionAttached = "WAITING".equals(mode) && HyperIsland.isIslandActive(this)
                && QuestionBridge.attach(this, builder, iconBitmap, sid == null ? "" : sid,
                    getSharedPreferences("agent_capsule_state", 0).getString("turn_id", ""));
            if (!questionAttached) HyperIsland.attachCapsule(this, builder, capsuleTitle, displayTitle, bigText,
                    iconBitmap, virtualDisplayRunning);

            startForeground(NOTIFICATION_ID, builder.build());
            try {
                android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
                sp.edit()
                  .putString("mode", mode)
                  .putString("session_id", sid != null ? sid : "")
                  .putString("session_title", displayTitle != null ? displayTitle : "")
                  .apply();
            } catch (Throwable ignored) {}
            android.util.Log.i("AgentGlowService", "Capsule notification promoted (" + capsuleTitle + ") for session: " + sid + " (" + displayTitle + ")");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to promote to foreground: " + t.getMessage(), t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        String sid = (intent != null) ? intent.getStringExtra("session_id") : null;
        String title = (intent != null) ? intent.getStringExtra("session_title") : null;
        if (intent != null) {
            android.content.SharedPreferences.Editor edit = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE).edit();
            if (intent.hasExtra("live_detail")) edit.putString("live_detail", intent.getStringExtra("live_detail"));
            if (intent.hasExtra("turn_id")) edit.putString("turn_id", intent.getStringExtra("turn_id"));
            if (intent.hasExtra("display_backend")) edit.putString("display_backend", intent.getStringExtra("display_backend"));
            if (intent.hasExtra("total")) edit.putInt("live_total", intent.getIntExtra("total", 0));
            if (intent.hasExtra("completed")) edit.putInt("live_completed", intent.getIntExtra("completed", 0));
            edit.apply();
        }

        // Auto-recover state if intent is null (e.g. killed by user clearing recent apps, restarted by START_STICKY)
        if (intent == null || action == null) {
            try {
                android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
                String savedMode = sp.getString("mode", "");
                if (!savedMode.isEmpty() && !"STOP".equals(savedMode)) {
                    String savedSid = sp.getString("session_id", "");
                    String savedTitle = sp.getString("session_title", "");
                    android.util.Log.i("AgentGlowService", "onStartCommand intent=null, auto-restoring capsule: mode=" + savedMode);
                    promoteToForeground(savedMode, savedSid, savedTitle);
                    if ("FOREGROUND".equals(savedMode)) {
                        showGlow();
                    } else {
                        hideGlow();
                    }
                    return START_STICKY;
                } else {
                    hideGlow();
                    stopForeground(true);
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.cancel(NOTIFICATION_ID);
                    } catch (Throwable ignored) {}
                    stopSelf();
                    return START_NOT_STICKY;
                }
            } catch (Throwable ignored) {}
        }

        if ("START_FOREGROUND".equals(action) || "START".equals(action)) {
            promoteToForeground("FOREGROUND", sid, title);
            showGlow();
        } else if ("START_BACKGROUND".equals(action)) {
            hideGlow();
            promoteToForeground("BACKGROUND", sid, title);
        } else if ("START_RUNNING".equals(action)) {
            hideGlow();
            promoteToForeground("RUNNING", sid, title);
        } else if ("START_WAITING".equals(action)) {
            hideGlow();
            promoteToForeground("WAITING", sid, title);
        } else if ("REFRESH".equals(action) || "REFRESH_QUESTION".equals(action)) {
            try {
                android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
                String savedMode = sp.getString("mode", "");
                if (!savedMode.isEmpty() && !"STOP".equals(savedMode)) {
                    String savedSid = sp.getString("session_id", "");
                    String savedTitle = sp.getString("session_title", "");
                    // Explicitly tear down existing notification to detach SystemUI punch-hole capsule
                    if ("REFRESH".equals(action)) {
                        stopForeground(true);
                        try {
                            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                            if (nm != null) nm.cancel(NOTIFICATION_ID);
                        } catch (Throwable ignored) {}
                    }
                    promoteToForeground(savedMode, savedSid, savedTitle);
                }
                else { stopSelf(); return START_NOT_STICKY; }
            } catch (Throwable ignored) {}
        } else if ("SHOW_GLOW".equals(action)) {
            try {
                android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
                if ("FOREGROUND".equals(sp.getString("mode", ""))) {
                    showGlow();
                }
            } catch (Throwable ignored) {}
        } else if ("HIDE_GLOW".equals(action)) {
            hideGlow();
        } else if ("STOP".equals(action)) {
            try {
                clearRuntimeState();
            } catch (Throwable ignored) {}
            hideGlow();
            stopForeground(true);
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIFICATION_ID);
            } catch (Throwable ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void showGlow() {
        if (mIsShowing) return;

        try {
            android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
            if (!sp.getBoolean("enable_glow_frame", true)) return;
        } catch (Throwable ignored) {}

        try {
            mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (mWindowManager == null) return;

            int width = 1272;
            int height = 2800;
            try {
                android.view.Display display = mWindowManager.getDefaultDisplay();
                android.util.DisplayMetrics realMetrics = new android.util.DisplayMetrics();
                java.lang.reflect.Method getRealMetricsMethod = android.view.Display.class.getMethod("getRealMetrics", android.util.DisplayMetrics.class);
                getRealMetricsMethod.invoke(display, realMetrics);
                if (realMetrics.widthPixels > 0) width = realMetrics.widthPixels;
                if (realMetrics.heightPixels > 0) height = realMetrics.heightPixels;
            } catch (Throwable t) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                if (dm.widthPixels > 0) width = dm.widthPixels;
                if (dm.heightPixels > 0) height = dm.heightPixels;
            }

            int cornerRadius = 135;
            int strokeWidth = 24; // Bold cyber glow edge (elevated presence)

            mGlowView = new GlowView(this, width, height, cornerRadius, strokeWidth);
            mGlowView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );

            // TYPE_APPLICATION_OVERLAY = 2038
            int windowType = 2038;
            int winFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                         | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                         | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                         | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                winFlags,
                PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.setTitle("AgentMobileEdgeGlow");
            // Android 12+ 的穿透触摸要求 SAW 窗口不透明度不超过系统阈值。
            lp.alpha = 0.79f;
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    Object input = getSystemService("input");
                    float maximum = ((Number) input.getClass().getMethod("getMaximumObscuringOpacityForTouch").invoke(input)).floatValue();
                    lp.alpha = Math.min(lp.alpha, maximum);
                } catch (Throwable ignored) {}
            }

            // Allow overlay to extend into cutout / notch / status bar area (LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3)
            try {
                java.lang.reflect.Field cutoutField = WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode");
                cutoutField.setInt(lp, 3);
            } catch (Throwable ignored) {}

            // Ignore system bar insets so window covers 100% of physical display edges
            try {
                java.lang.reflect.Method fitInsetsMethod = WindowManager.LayoutParams.class.getMethod("setFitInsetsTypes", int.class);
                fitInsetsMethod.invoke(lp, 0);
            } catch (Throwable ignored) {}

            mWindowManager.addView(mGlowView, lp);
            mGlowView.startPulseAnimation();

            registerTouchReceiver();
            mIsShowing = true;
            android.util.Log.i("AgentGlowService", "Glow Overlay added (full screen edge glow + touch indicator enabled).");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to add glow view: " + t.getMessage(), t);
        }
    }

    private void registerTouchReceiver() {
        if (mTouchReceiver != null) return;
        mTouchReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!mIsShowing || mGlowView == null || intent == null) return;
                int type = intent.getIntExtra("type", 1);
                if (type == 1) {
                    // Click
                    final int x = intent.getIntExtra("x", 0);
                    final int y = intent.getIntExtra("y", 0);
                    android.util.Log.i("AgentGlowService", "Trigger ripple at (" + x + ", " + y + ")");
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mGlowView != null) mGlowView.addRipple(x, y);
                        }
                    });
                } else if (type == 2) {
                    // Swipe
                    final int x1 = intent.getIntExtra("x1", 0);
                    final int y1 = intent.getIntExtra("y1", 0);
                    final int x2 = intent.getIntExtra("x2", 0);
                    final int y2 = intent.getIntExtra("y2", 0);
                    final int duration = intent.getIntExtra("duration", 300);
                    android.util.Log.i("AgentGlowService", "Trigger swipe from (" + x1 + ", " + y1 + ") to (" + x2 + ", " + y2 + ")");
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mGlowView != null) mGlowView.addSwipe(x1, y1, x2, y2, duration);
                        }
                    });
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_TOUCH);
        try {
            // Android 14+ RECEIVER_EXPORTED = 2
            java.lang.reflect.Method regMethod = Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
            regMethod.invoke(this, mTouchReceiver, filter, 2);
            android.util.Log.i("AgentGlowService", "TouchReceiver registered with RECEIVER_EXPORTED (2)");
        } catch (Throwable t) {
            android.util.Log.w("AgentGlowService", "Fallback registerReceiver: " + t.getMessage());
            try {
                registerReceiver(mTouchReceiver, filter);
            } catch (Throwable t2) {
                android.util.Log.e("AgentGlowService", "Failed to register TouchReceiver: " + t2.getMessage(), t2);
            }
        }
    }

    private void unregisterTouchReceiver() {
        if (mTouchReceiver != null) {
            try {
                unregisterReceiver(mTouchReceiver);
            } catch (Throwable ignored) {}
            mTouchReceiver = null;
        }
    }

    private void hideGlow() {
        if (!mIsShowing) return;
        try {
            unregisterTouchReceiver();
            if (mGlowView != null) {
                mGlowView.stopAnimation();
                if (mWindowManager != null) {
                    mWindowManager.removeView(mGlowView);
                }
                mGlowView = null;
            }
            mIsShowing = false;
            android.util.Log.i("AgentGlowService", "Glow Overlay removed.");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to remove views: " + t.getMessage(), t);
        }
    }

    @Override
    public void onDestroy() {
        hideGlow();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {}
        try {
            clearRuntimeState();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /**
     * 只清理本次运行态（模式/会话），保留用户在控制台里设置的开关，
     * 否则任何一次 STOP / onDestroy 都会把「超级岛通知」等开关一起清掉。
     */
    private void clearRuntimeState() {
        android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
        sp.edit()
                .remove("mode")
                .remove("session_id")
                .remove("session_title")
                .remove("last_completed_sid")
                .remove("turn_id").remove("live_detail").remove("live_total").remove("live_completed").remove("display_backend")
                .apply();
    }

    private static class GlowView extends View {
        private final Paint mPaintOuter;
        private final Paint mPaintInner;
        private final Paint mPaintCore;
        private final Paint mRippleOuterPaint;
        private final Paint mRippleInnerPaint;
        private final Paint mRippleCorePaint;
        private final Paint mSwipeTrailPaint;
        private final Paint mSwipeCorePaint;
        private final Paint mSwipePointPaint;

        private final RectF mRect;
        private final float mCornerRadius;
        private final float mStrokeWidth;
        private ValueAnimator mAnimator;
        private float mAlphaScale = 1.0f;

        private final List<RippleItem> mRipples = new ArrayList<>();
        private final List<SwipeItem> mSwipes = new ArrayList<>();

        public GlowView(Context context, int w, int h, int radius, int stroke) {
            super(context);
            mCornerRadius = radius;
            mStrokeWidth = stroke;
            float halfStroke = stroke / 2.0f;
            mRect = new RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

            // Edge Layer 1: Electric cyan aura (broader and richer)
            mPaintOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintOuter.setStyle(Paint.Style.STROKE);
            mPaintOuter.setStrokeWidth(stroke * 2.5f);
            mPaintOuter.setColor(Color.argb(120, 0, 210, 255));

            // Edge Layer 2: Deep indigo/purple transition
            mPaintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintInner.setStyle(Paint.Style.STROKE);
            mPaintInner.setStrokeWidth(stroke * 1.4f);
            mPaintInner.setColor(Color.argb(180, 110, 70, 255));

            // Edge Layer 3: Sharp core bright line
            mPaintCore = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintCore.setStyle(Paint.Style.STROKE);
            mPaintCore.setStrokeWidth(stroke * 0.55f);
            mPaintCore.setColor(Color.argb(255, 230, 245, 255));

            // Ripple Paints (bold impact rings)
            mRippleOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRippleOuterPaint.setStyle(Paint.Style.STROKE);
            mRippleOuterPaint.setStrokeWidth(12.0f);

            mRippleInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRippleInnerPaint.setStyle(Paint.Style.STROKE);
            mRippleInnerPaint.setStrokeWidth(7.0f);

            mRippleCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRippleCorePaint.setStyle(Paint.Style.FILL);

            // Swipe Paints (laser-bold trail)
            mSwipeTrailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mSwipeTrailPaint.setStyle(Paint.Style.STROKE);
            mSwipeTrailPaint.setStrokeCap(Paint.Cap.ROUND);
            mSwipeTrailPaint.setStrokeWidth(32.0f);

            mSwipeCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mSwipeCorePaint.setStyle(Paint.Style.STROKE);
            mSwipeCorePaint.setStrokeCap(Paint.Cap.ROUND);
            mSwipeCorePaint.setStrokeWidth(14.0f);

            mSwipePointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mSwipePointPaint.setStyle(Paint.Style.FILL);
        }

        public void addRipple(float x, float y) {
            final RippleItem item = new RippleItem(x, y);
            mRipples.add(item);
            ValueAnimator va = ValueAnimator.ofFloat(0.0f, 1.0f);
            va.setDuration(450);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    item.progress = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mRipples.remove(item);
                    invalidate();
                }
            });
            va.start();
        }

        public void addSwipe(float x1, float y1, float x2, float y2, int duration) {
            final SwipeItem item = new SwipeItem(x1, y1, x2, y2);
            mSwipes.add(item);
            int animDuration = Math.max(duration + 150, 450);
            ValueAnimator va = ValueAnimator.ofFloat(0.0f, 1.0f);
            va.setDuration(animDuration);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    item.progress = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mSwipes.remove(item);
                    invalidate();
                }
            });
            va.start();
        }

        public void startPulseAnimation() {
            mAnimator = ValueAnimator.ofFloat(0.55f, 1.0f);
            mAnimator.setDuration(1200);
            mAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mAlphaScale = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            mAnimator.start();
        }

        public void stopAnimation() {
            if (mAnimator != null) {
                mAnimator.cancel();
            }
            mRipples.clear();
            mSwipes.clear();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                float halfStroke = mStrokeWidth / 2.0f;
                mRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // 1. Draw edge glow frame
            mPaintOuter.setAlpha((int) (120 * mAlphaScale));
            mPaintInner.setAlpha((int) (180 * mAlphaScale));
            mPaintCore.setAlpha((int) (255 * mAlphaScale));

            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintOuter);
            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintInner);
            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintCore);

            // 2. Draw active click ripples
            for (int i = 0; i < mRipples.size(); i++) {
                RippleItem r = mRipples.get(i);
                float p = r.progress;
                float radius = 20.0f + 130.0f * p;
                int alpha = (int) (230 * (1.0f - p));

                // Outer cyan ring
                mRippleOuterPaint.setColor(Color.argb(alpha, 0, 210, 255));
                canvas.drawCircle(r.x, r.y, radius, mRippleOuterPaint);

                // Inner purple ring
                mRippleInnerPaint.setColor(Color.argb((int) (alpha * 0.8f), 110, 70, 255));
                canvas.drawCircle(r.x, r.y, radius * 0.65f, mRippleInnerPaint);

                // Center bright white core
                if (p < 0.7f) {
                    int coreAlpha = (int) (255 * (1.0f - p / 0.7f));
                    mRippleCorePaint.setColor(Color.argb(coreAlpha, 255, 255, 255));
                    canvas.drawCircle(r.x, r.y, 16.0f * (1.0f - p), mRippleCorePaint);
                }
            }

            // 3. Draw active swipe trails
            for (int i = 0; i < mSwipes.size(); i++) {
                SwipeItem s = mSwipes.get(i);
                float p = s.progress;

                // Motion progress: reaches end at p = 0.65, then fades out
                float moveP = Math.min(1.0f, p / 0.65f);
                float curX = s.x1 + (s.x2 - s.x1) * moveP;
                float curY = s.y1 + (s.y2 - s.y1) * moveP;

                float fade = (p < 0.65f) ? 1.0f : (1.0f - (p - 0.65f) / 0.35f);
                int alpha = (int) (220 * fade);

                // Outer cyan aura line
                mSwipeTrailPaint.setColor(Color.argb((int) (alpha * 0.7f), 0, 210, 255));
                canvas.drawLine(s.x1, s.y1, curX, curY, mSwipeTrailPaint);

                // Inner bright white/cyan core line
                mSwipeCorePaint.setColor(Color.argb(alpha, 220, 245, 255));
                canvas.drawLine(s.x1, s.y1, curX, curY, mSwipeCorePaint);

                // Leading head dot
                mSwipePointPaint.setColor(Color.argb(alpha, 0, 230, 255));
                canvas.drawCircle(curX, curY, 24.0f, mSwipePointPaint);
                mSwipePointPaint.setColor(Color.argb(alpha, 255, 255, 255));
                canvas.drawCircle(curX, curY, 12.0f, mSwipePointPaint);

                // Start point small anchor dot
                mSwipePointPaint.setColor(Color.argb((int) (alpha * 0.5f), 110, 70, 255));
                canvas.drawCircle(s.x1, s.y1, 14.0f, mSwipePointPaint);
            }
        }
    }

    private static class RippleItem {
        final float x;
        final float y;
        float progress = 0.0f;

        RippleItem(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class SwipeItem {
        final float x1, y1, x2, y2;
        float progress = 0.0f;

        SwipeItem(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }
}
