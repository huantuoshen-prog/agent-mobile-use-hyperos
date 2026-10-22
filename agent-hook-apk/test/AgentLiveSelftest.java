package com.agent.mobileuse;
import org.json.JSONObject;
import java.util.HashSet;

public final class AgentLiveSelftest {
    public static void main(String[] args) throws Exception {
        String[] states = {"running", "foreground", "background", "waiting", "completed", "failed", "cancelled"};
        HashSet<String> titles = new HashSet<>(), colors = new HashSet<>();
        for (String state : states) {
            titles.add(AgentState.title(state)); colors.add(AgentState.color(state));
            String json = IslandPayloadBuilder.live(3, state, "测试会话", "执行步骤", 2, 1, null);
            if (json == null || IslandPayloadBuilder.utf8Length(json) > 3072) throw new AssertionError("payload invalid: " + state);
            JSONObject v2 = new JSONObject(json).getJSONObject("param_v2");
            if (!state.equals(v2.getJSONObject("extraInfo").getString("agentState"))) throw new AssertionError("state lost");
            boolean glow = v2.getJSONObject("param_island").has("outEffectSrc");
            if (glow != "background".equals(state)) throw new AssertionError("glow state mismatch");
            if (!v2.getJSONObject("baseInfo").getString("content").contains("1/2")) throw new AssertionError("progress missing");
        }
        if (titles.size() != 7 || colors.size() != 7) throw new AssertionError("states look identical");
        if (HookEntry.isAgentFocusCall(new Object[]{"other.app"})) throw new AssertionError("unrelated application allowed");
        if (!HookEntry.isAgentFocusCall(new Object[]{"com.agent.mobileuse"})) throw new AssertionError("own application denied");
        if (HookEntry.isAgentFocusCall(new Object[]{new RuntimeException("com.agent.mobileuse")})) throw new AssertionError("untrusted error text grants permission");
        System.out.println("AgentLiveSelftest PASS: seven distinct states, progress, glow, scoped permissions");
    }
}
