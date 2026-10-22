package com.agent.mobileuse;

import android.content.Context;
import android.webkit.CookieManager;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;

/** 本机控制凭据仅由模块交付到 App 私有 files，不写日志或共享存储。 */
public final class GatewayClient {
    private static volatile Context application;
    private GatewayClient() {}
    static void initialize(Context context) { if (context != null) application = context.getApplicationContext(); }
    static String token(Context context) {
        if (context == null) context = application;
        if (context == null) return "";
        try (FileInputStream input = new FileInputStream(new File(context.getFilesDir(), "gateway-token"))) {
            byte[] bytes = new byte[256];
            int n = input.read(bytes);
            return n > 0 ? new String(bytes, 0, n, "UTF-8").trim() : "";
        } catch (Exception ignored) { return ""; }
    }
    static void authorize(Context context, HttpURLConnection connection) {
        if (connection.getURL().getPort() != 3070) return;
        String value = token(context);
        if (!value.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + value);
    }
    static void authorizeWeb(Context context) {
        String value = token(context);
        if (value.isEmpty()) return;
        CookieManager cm = CookieManager.getInstance();
        cm.setCookie("http://127.0.0.1:3070/", "mobile_use=" + value + "; Path=/; HttpOnly; SameSite=Strict");
        cm.flush();
    }
}
