package com.agent.mobileuse;
import org.json.JSONObject;
import org.json.JSONArray;
public final class QuestionPayloadSelftest {
 public static void main(String[] args)throws Exception {
  String raw=IslandPayloadBuilder.answerQuestion(3,"选择路线","现在继续还是稍后？",new String[]{"现在继续","稍后"});
  JSONObject v=new JSONObject(raw).getJSONObject("param_v2");JSONArray buttons=v.getJSONArray("textButton");
  if(buttons.length()!=2 || v.has("actions") || v.has("baseInfo"))throw new AssertionError("wrong button template");
  for(int i=0;i<2;i++)if(!buttons.getJSONObject(i).getString("action").equals("miui.focus.action_answer_"+i))throw new AssertionError("action not bound");
  if(!v.getJSONObject("iconTextInfo").getString("content").contains("继续"))throw new AssertionError("question lost");
  if(IslandPayloadBuilder.utf8Length(raw)>3072)throw new AssertionError("payload too large");
  if(v.getJSONObject("param_island").getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft").has("textInfo"))throw new AssertionError("waiting pill must remain compact");
  System.out.println("QuestionPayloadSelftest PASS: two component-4 buttons and compact pill");
 }
}
