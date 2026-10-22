package com.agent.mobileuse;

/** 通知状态是任务事实；停止与失败不转换成完成。 */
public final class AgentState {
    private AgentState() {}
    public static String title(String state) {
        if ("foreground".equals(state)) return "前台控制";
        if ("background".equals(state)) return "后台副屏";
        if ("waiting".equals(state)) return "等待回答";
        if ("completed".equals(state)) return "任务完成";
        if ("failed".equals(state)) return "执行失败";
        if ("cancelled".equals(state)) return "任务已停止";
        return "任务进行中";
    }
    public static String color(String state) {
        if ("foreground".equals(state)) return "#32ADE6";
        if ("background".equals(state)) return "#BF5AF2";
        if ("waiting".equals(state)) return "#FFB340";
        if ("completed".equals(state)) return "#30D158";
        if ("failed".equals(state)) return "#FF453A";
        if ("cancelled".equals(state)) return "#A0A6B0";
        return "#40DCC8";
    }
    public static String fromMode(String mode) {
        if ("FOREGROUND".equals(mode)) return "foreground";
        if ("BACKGROUND".equals(mode)) return "background";
        if ("WAITING".equals(mode)) return "waiting";
        return "running";
    }
}
