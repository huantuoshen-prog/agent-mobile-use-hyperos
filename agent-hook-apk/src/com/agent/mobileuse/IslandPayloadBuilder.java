package com.agent.mobileuse;

import java.nio.charset.Charset;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure-JSON builder for HyperOS (Xiaomi) focus-notification / super-island payloads.
 *
 * <p>This class deliberately has no Android dependencies: the JSON contract can be
 * compiled and checked on a plain JVM against org.json, which keeps the schema
 * verifiable without a phone. {@link HyperIsland} owns the Android side (extras,
 * icons, PendingIntents, ROM detection).
 *
 * <p>Schema facts are taken from the HyperIsland ToolKit data classes
 * (miui.focus.param -> {"param_v2": {...}}) and the official Xiaomi focus
 * notification guide. Reminder: the official guide shows a typo
 * "miui.focus.paramtextInfo" while the runtime schema uses "textInfo"; this
 * builder emits the runtime schema.
 */
public final class IslandPayloadBuilder {

    /** miui.focus.param must stay below 3072 bytes (official limit). */
    public static final int PARAM_MAX_BYTES = 3072;

    /** Keys under the "miui.focus.pics" Bundle; full key is PIC_KEY_PREFIX + key. */
    public static final String PIC_CAPSULE = "capsule";
    public static final String PIC_CHECKMARK = "checkmark";
    public static final String PIC_QUESTION = "question";
    public static final String PIC_ACTION_STOP = "action_stop";
    public static final String PIC_ACTION_SWITCH = "action_switch";
    public static final String PIC_ACTION_OPEN = "action_open";
    public static final String PIC_ACTION_CLEAR = "action_clear";

    /** Keys under the "miui.focus.actions" Bundle; full key is ACTION_KEY_PREFIX + key. */
    public static final String ACTION_KEY_PREFIX = "miui.focus.action_";
    public static final String PIC_KEY_PREFIX = "miui.focus.pic_";

    public static final int INTENT_ACTIVITY = 1;
    public static final int INTENT_BROADCAST = 2;
    public static final int INTENT_SERVICE = 3;

    /** One island button; key must be unique inside a single payload. */
    public static final class ActionRef {
        public final String key;
        public final String title;
        public final int intentType;

        public ActionRef(String key, String title, int intentType) {
            this.key = key;
            this.title = title;
            this.intentType = intentType;
        }
    }

    private IslandPayloadBuilder() {
    }

    /**
     * Capsule island (agent takeover / running state).
     *
     * @param protocol 2 for OS2 focus notifications, 3 for OS3 super island
     */
    public static String capsule(int protocol, String stateTitle, String sessionTitle,
                                 String hint, String picKey, ActionRef[] actions) {
        return build(protocol, "agent_capsule", stateTitle, hint, "#00D2FF",
                stateTitle, sessionTitle, 1, 3600, picKey, actions);
    }

    /** Task-completed island. */
    public static String completed(int protocol, String title, String content,
                                   String picKey, ActionRef[] actions) {
        return build(protocol, "agent_completed", "任务完成", title, "#00FF9D",
                title, content, 1, 600, picKey, actions);
    }

    /** Interactive question island. */
    public static String question(int protocol, String title, String question,
                                  String picKey, ActionRef[] actions) {
        return build(protocol, "agent_question", "需要确认", title, "#FFA726",
                title, question, 2, 1800, picKey, actions);
    }

    private static String build(int protocol, String business, String aodTitle, String ticker,
                                String highlightColor, String leftTitle, String leftContent,
                                int islandProperty, int islandTimeout, String picKey,
                                ActionRef[] actions) {
        if (picKey == null || picKey.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject v2 = new JSONObject();
            v2.put("protocol", protocol);
            v2.put("business", business);
            v2.put("updatable", true);
            v2.put("enableFloat", true);
            v2.put("isShowNotification", true);
            v2.put("islandFirstFloat", false);
            v2.put("aodTitle", cut(aodTitle, 12));
            if (ticker != null && !ticker.trim().isEmpty()) {
                v2.put("ticker", cut(ticker, 16));
            }

            JSONObject island = new JSONObject();
            island.put("islandProperty", islandProperty);
            island.put("islandPriority", 2);
            island.put("islandTimeout", islandTimeout);
            island.put("islandOrder", false);
            island.put("dismissIsland", false);
            island.put("maxSize", false);
            island.put("needCloseAnimation", true);
            island.put("highlightColor", highlightColor);

            JSONObject picInfo = new JSONObject();
            picInfo.put("type", 1);
            picInfo.put("pic", PIC_KEY_PREFIX + picKey);

            JSONObject textInfo = new JSONObject();
            textInfo.put("title", cut(leftTitle, 14));
            textInfo.put("content", cut(leftContent, 40));
            textInfo.put("showHighlightColor", true);

            JSONObject left = new JSONObject();
            left.put("type", 1);
            left.put("picInfo", picInfo);
            left.put("textInfo", textInfo);

            JSONObject big = new JSONObject();
            big.put("imageTextInfoLeft", left);
            island.put("bigIslandArea", big);

            JSONObject small = new JSONObject();
            small.put("picInfo", new JSONObject(picInfo.toString()));
            island.put("smallIslandArea", small);

            v2.put("param_island", island);

            JSONObject base = new JSONObject();
            base.put("type", 1);
            base.put("title", cut(leftTitle, 14));
            base.put("content", cut(leftContent, 40));
            base.put("colorTitle", highlightColor);
            v2.put("baseInfo", base);

            JSONArray actionArray = new JSONArray();
            if (actions != null) {
                for (ActionRef action : actions) {
                    if (action == null || action.key == null || action.key.isEmpty()) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    // 2 = title-only button (HyperAction.getType() semantics:
                    // progress -> 1, no icon + title -> 2, otherwise 0).
                    item.put("type", 2);
                    // We always ship our own PendingIntent under the
                    // "miui.focus.actions" Bundle, so the reference is an
                    // actionIntent, not a predefined system action name.
                    item.put("actionIntent", ACTION_KEY_PREFIX + action.key);
                    item.put("actionTitle", cut(action.title, 6));
                    item.put("actionIntentType", action.intentType);
                    actionArray.put(item);
                }
            }
            if (actionArray.length() > 0) {
                v2.put("actions", actionArray);
            }

            JSONObject root = new JSONObject();
            root.put("param_v2", v2);
            String json = root.toString();
            if (utf8Length(json) > PARAM_MAX_BYTES) {
                // Buttons are optional; drop them before losing the island itself.
                v2.remove("actions");
                root.put("param_v2", v2);
                json = root.toString();
            }
            if (utf8Length(json) > PARAM_MAX_BYTES) {
                return null;
            }
            return json;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Trim to a code-point budget and flatten newlines so JSON stays single-line safe. */
    static String cut(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (value.isEmpty()) {
            return "";
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    static int utf8Length(String text) {
        if (text == null) {
            return 0;
        }
        return text.getBytes(Charset.forName("UTF-8")).length;
    }
}
