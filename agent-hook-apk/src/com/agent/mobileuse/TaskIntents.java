package com.agent.mobileuse;

import android.content.Context;
import android.content.Intent;

final class TaskIntents {
    private TaskIntents() {}

    static Intent open(Context context, String sessionId) {
        boolean remote = sessionId != null && sessionId.startsWith("remote-");
        Intent intent = remote ? null : context.getPackageManager().getLaunchIntentForPackage("com.dsh.client");
        if (intent == null) intent = new Intent(context, DemoDialogActivity.class);
        if (remote) intent.putExtra("target_url", "http://127.0.0.1:3070/");
        else intent.putExtra("open_web", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("skip_glow", true);
        if (sessionId != null && !sessionId.isEmpty()) intent.putExtra("session_id", sessionId);
        return intent;
    }
}
