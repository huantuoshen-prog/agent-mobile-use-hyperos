package com.agent.mobileuse;

import android.graphics.Bitmap;
import android.app.Notification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;

/**
 * Plain-JVM self test for the HyperOS island payload layer.
 *
 * <p>Runs without a device or an Android runtime: it executes the pure-JSON
 * builder for real (against org.json) and checks the Android glue class by
 * reflection. Android framework classes are only referenced as Class objects,
 * never instantiated.
 *
 * <p>Invoke through D:/DSH/.work/verify/run-selftest.sh. Exit code 0 = all pass.
 */
public final class IslandPayloadSelftest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testHyperIslandApi();
        testCapsulePayload();
        testCompletedAndQuestionPayloads();
        testActionRefs();
        testByteBudget();
        testNullPic();

        System.out.println("RESULT: " + (failed == 0 ? "PASS" : "FAIL")
                + " passed=" + passed + " failed=" + failed);
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static JSONObject parse(String json, String name) throws Exception {
        check(name + " payload not null", json != null);
        if (json == null) {
            return null;
        }
        int bytes = json.getBytes(Charset.forName("UTF-8")).length;
        check(name + " payload <= 3072 bytes (got " + bytes + ")", bytes <= 3072);
        JSONObject root = new JSONObject(json);
        check(name + " has param_v2", root.has("param_v2"));
        return root.optJSONObject("param_v2");
    }

    private static JSONObject requireObj(JSONObject parent, String key, String name) {
        JSONObject obj = parent == null ? null : parent.optJSONObject(key);
        check(name + " has object '" + key + "'", obj != null);
        return obj;
    }

    private static String requireString(JSONObject parent, String key, String name) {
        String value = parent == null ? null : parent.optString(key, null);
        check(name + " has non-empty string '" + key + "'", value != null && !value.isEmpty());
        return value;
    }

    private static boolean hasStaticMethod(Class<?> type, String name, Class<?>... params) {
        try {
            Method method = type.getDeclaredMethod(name, params);
            return Modifier.isStatic(method.getModifiers());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ tests

    private static void testHyperIslandApi() throws Exception {
        Class<?> type;
        try {
            type = Class.forName("com.agent.mobileuse.HyperIsland");
        } catch (Throwable t) {
            check("HyperIsland class exists (" + t + ")", false);
            return;
        }
        check("HyperIsland class exists", true);

        check("HyperIsland.isHyperOs()", hasStaticMethod(type, "isHyperOs"));
        check("HyperIsland.isIslandSupported(android.content.Context)",
                hasStaticMethod(type, "isIslandSupported", Class.forName("android.content.Context")));
        check("HyperIsland.isEnabled(android.content.Context)",
                hasStaticMethod(type, "isEnabled", Class.forName("android.content.Context")));
        check("HyperIsland.attachCapsule(Context, Notification.Builder, String, String, String, Bitmap)",
                hasStaticMethod(type, "attachCapsule",
                        Class.forName("android.content.Context"),
                        Class.forName("android.app.Notification$Builder"),
                        String.class, String.class, String.class, Bitmap.class));
        check("HyperIsland.attachCompleted(Context, Notification.Builder, String, String, Bitmap, String)",
                hasStaticMethod(type, "attachCompleted",
                        Class.forName("android.content.Context"),
                        Class.forName("android.app.Notification$Builder"),
                        String.class, String.class, Bitmap.class, String.class));
        check("HyperIsland.attachQuestion(Context, Notification.Builder, String, String, Bitmap, String)",
                hasStaticMethod(type, "attachQuestion",
                        Class.forName("android.content.Context"),
                        Class.forName("android.app.Notification$Builder"),
                        String.class, String.class, Bitmap.class, String.class));

        check("EXTRA_PARAM value", "miui.focus.param".equals(
                type.getField("EXTRA_PARAM").get(null)));
        check("EXTRA_PICS value", "miui.focus.pics".equals(
                type.getField("EXTRA_PICS").get(null)));
        check("EXTRA_ACTIONS value", "miui.focus.actions".equals(
                type.getField("EXTRA_ACTIONS").get(null)));
    }

    private static void testCapsulePayload() throws Exception {
        String json = IslandPayloadBuilder.capsule(3, "前台接管", "会话 A", "点击切换为后台接管",
                IslandPayloadBuilder.PIC_CAPSULE, null);
        JSONObject v2 = parse(json, "capsule");
        if (v2 == null) {
            return;
        }
        check("capsule protocol==3", v2.optInt("protocol") == 3);
        check("capsule business", "agent_capsule".equals(v2.optString("business")));
        requireString(v2, "aodTitle", "capsule");
        check("capsule islandFirstFloat false", v2.has("islandFirstFloat")
                && !v2.optBoolean("islandFirstFloat", true));

        JSONObject island = requireObj(v2, "param_island", "capsule");
        check("capsule islandProperty==1", island != null && island.optInt("islandProperty") == 1);
        check("capsule islandTimeout>0", island != null && island.optInt("islandTimeout", 0) > 0);

        JSONObject big = requireObj(island, "bigIslandArea", "capsule");
        JSONObject left = requireObj(big, "imageTextInfoLeft", "capsule");
        JSONObject textInfo = requireObj(left, "textInfo", "capsule");
        check("capsule bigIslandArea.textInfo.title", textInfo != null
                && "前台接管".equals(textInfo.optString("title")));
        check("capsule bigIslandArea.textInfo.content", textInfo != null
                && "会话 A".equals(textInfo.optString("content")));

        JSONObject small = requireObj(island, "smallIslandArea", "capsule");
        JSONObject picInfo = requireObj(small, "picInfo", "capsule");
        check("capsule smallIslandArea.picInfo.pic", picInfo != null
                && "miui.focus.pic_capsule".equals(picInfo.optString("pic")));

        JSONObject base = requireObj(v2, "baseInfo", "capsule");
        check("capsule baseInfo.type==1", base != null && base.optInt("type") == 1);
        requireString(base, "title", "capsule");
        check("capsule no actions when null", !v2.has("actions"));
    }

    private static void testCompletedAndQuestionPayloads() throws Exception {
        JSONObject done = parse(IslandPayloadBuilder.completed(3, "已完成", "3 步全部完成",
                IslandPayloadBuilder.PIC_CHECKMARK, null), "completed");
        if (done != null) {
            check("completed business", "agent_completed".equals(done.optString("business")));
            JSONObject base = requireObj(done, "baseInfo", "completed");
            check("completed baseInfo.title", base != null && "已完成".equals(base.optString("title")));
        }

        JSONObject ask = parse(IslandPayloadBuilder.question(3, "需要确认", "是否继续执行？",
                IslandPayloadBuilder.PIC_QUESTION, null), "question");
        if (ask != null) {
            check("question business", "agent_question".equals(ask.optString("business")));
            JSONObject island = requireObj(ask, "param_island", "question");
            check("question islandProperty==2", island != null && island.optInt("islandProperty") == 2);
        }
    }

    private static void testActionRefs() throws Exception {
        IslandPayloadBuilder.ActionRef[] actions = new IslandPayloadBuilder.ActionRef[]{
                new IslandPayloadBuilder.ActionRef("action_stop", "停止",
                        IslandPayloadBuilder.INTENT_BROADCAST),
                new IslandPayloadBuilder.ActionRef("action_switch", "切后台",
                        IslandPayloadBuilder.INTENT_BROADCAST),
        };
        JSONObject v2 = parse(IslandPayloadBuilder.capsule(3, "运行中", "会话 B", "hint",
                IslandPayloadBuilder.PIC_CAPSULE, actions), "capsule-actions");
        if (v2 == null) {
            return;
        }
        JSONArray array = v2.optJSONArray("actions");
        check("capsule actions present", array != null && array.length() == 2);
        if (array == null || array.length() != 2) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            check("action[" + i + "] actionIntent prefix", item.optString("actionIntent")
                    .startsWith(IslandPayloadBuilder.ACTION_KEY_PREFIX));
            check("action[" + i + "] title", !item.optString("actionTitle").isEmpty());
            check("action[" + i + "] type title-only", item.optInt("type") == 2);
            check("action[" + i + "] intentType broadcast",
                    item.optInt("actionIntentType") == IslandPayloadBuilder.INTENT_BROADCAST);
        }
        check("action constants", IslandPayloadBuilder.INTENT_ACTIVITY == 1
                && IslandPayloadBuilder.INTENT_BROADCAST == 2
                && IslandPayloadBuilder.INTENT_SERVICE == 3);
    }

    private static void testByteBudget() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            huge.append("超长会话标题");
        }
        String json = IslandPayloadBuilder.capsule(3, huge.toString(), huge.toString(),
                huge.toString(), IslandPayloadBuilder.PIC_CAPSULE,
                new IslandPayloadBuilder.ActionRef[]{
                        new IslandPayloadBuilder.ActionRef("action_stop", "停止",
                                IslandPayloadBuilder.INTENT_BROADCAST)});
        if (json != null) {
            int bytes = json.getBytes(Charset.forName("UTF-8")).length;
            check("oversized input still fits 3072 bytes (got " + bytes + ")", bytes <= 3072);
        }
        check("cut() truncates long text", IslandPayloadBuilder.cut("一二三四五六", 3).length() <= 3);
        check("cut() flattens newlines", !IslandPayloadBuilder.cut("a\nb", 10).contains("\n"));
        check("utf8Length counts bytes", IslandPayloadBuilder.utf8Length("中") == 3);
    }

    private static void testNullPic() {
        check("null pic key returns null",
                IslandPayloadBuilder.capsule(3, "t", "c", "h", null, null) == null);
        check("blank pic key returns null",
                IslandPayloadBuilder.capsule(3, "t", "c", "h", "  ", null) == null);
    }
}
