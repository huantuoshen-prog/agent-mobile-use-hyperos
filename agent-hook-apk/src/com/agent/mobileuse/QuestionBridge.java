package com.agent.mobileuse;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Own-App PendingIntents identify the exact request. The gateway owns validity. */
final class QuestionBridge {
    static final String ACTION = "com.agent.mobileuse.ANSWER_QUESTION";
    static final String PREFS = "agent_question_state";
    static JSONObject current(Context c) {
        try { return new JSONObject(c.getSharedPreferences(PREFS,0).getString("data","{}")); }
        catch (Exception e) { return new JSONObject(); }
    }
    static void store(Context c, Intent i) {
        c.getSharedPreferences(PREFS,0).edit().putString("request_id",i.getStringExtra("request_id"))
            .putString("data",i.getStringExtra("data")).remove("feedback").apply();
    }
    static JSONObject key(JSONObject q) throws Exception {
        return new JSONObject().put("request_id",q.getString("request_id"))
            .put("session_id",q.getString("session_id")).put("turn_id",q.getString("turn_id"));
    }
    static String query(JSONObject q) throws Exception {
        return "request_id="+Uri.encode(q.getString("request_id"))+"&session_id="+Uri.encode(q.getString("session_id"))+"&turn_id="+Uri.encode(q.getString("turn_id"));
    }
    static JSONObject http(Context c,String path,JSONObject body) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL("http://127.0.0.1:3070"+path).openConnection();
        conn.setConnectTimeout(2500);conn.setReadTimeout(path.startsWith("/api/diagnostics")?20000:3500);GatewayClient.authorize(c,conn);
        try {
            if(body!=null){conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");try(java.io.OutputStream out=conn.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
            int code=conn.getResponseCode();
            if(code!=200)throw new Exception(code==409?"问题已结束或正在提交，请刷新": "连接失败（"+code+"）");
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[2048];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>131072)throw new Exception("响应过大");}
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {conn.disconnect();}
    }
    static PendingIntent panel(Context c,JSONObject q) throws Exception {
        Intent i=new Intent(c,AnswerActivity.class).putExtra("key",key(q).toString());
        i.setData(Uri.parse("agent-mobile-use://question/"+Uri.encode(q.getString("request_id"))));
        return PendingIntent.getActivity(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|0x04000000);
    }
    static boolean attach(Context c,Notification.Builder b,Bitmap icon,String sid,String turn) {
        try {
            JSONObject q=current(c);
            if(!q.optBoolean("bridge") || !sid.equals(q.optString("session_id")) || !turn.equals(q.optString("turn_id")))return false;
            JSONArray questions=q.getJSONArray("questions");int step=Math.min(q.optInt("step",0),questions.length()-1);JSONObject first=questions.getJSONObject(step);
            JSONArray options=first.optJSONArray("options");
            boolean quick="open".equals(q.optString("state")) && !first.optBoolean("multiSelect") && first.optString("detail").isEmpty()
                && first.getString("question").length()<=100 && options!=null && options.length()==2;
            String prompt=first.getString("question");
            if(quick)for(int i=0;i<2;i++){
                JSONObject option=options.getJSONObject(i);
                if(option.getString("label").length()>12)quick=false;
                if(!option.optString("description").isEmpty())prompt+="\n"+option.getString("label")+"："+option.getString("description");
            }
            if(prompt.length()>160)quick=false;
            String feedback=c.getSharedPreferences(PREFS,0).getString("feedback","");
            String[] labels=quick?new String[]{options.getJSONObject(0).getString("label"),options.getJSONObject(1).getString("label")}:new String[]{"submitted".equals(q.optString("state"))?"查看提交状态":"选择答案"};
            String text=feedback.isEmpty()?(quick?prompt:first.getString("question")):feedback;
            String title=questions.length()>1?"第 "+(step+1)+" / "+questions.length()+" 题":first.optString("header","等待回答");
            String json=IslandPayloadBuilder.answerQuestion(HyperIsland.protocolVersion(c),title,text,labels);
            if(json==null)return false;
            Bundle extras=new Bundle(),actions=new Bundle(),pics=new Bundle();
            if(icon!=null)pics.putParcelable(IslandPayloadBuilder.PIC_KEY_PREFIX+IslandPayloadBuilder.PIC_QUESTION,Icon.createWithBitmap(icon));
            for(int n=0;n<labels.length;n++){
                PendingIntent pi;
                if(quick){
                    Intent i=new Intent(c,AnswerReceiver.class).setAction(ACTION).putExtra("key",key(q).toString()).putExtra("option",n).putExtra("question_id",first.getString("id"));
                    i.setData(Uri.parse("agent-mobile-use://answer/"+Uri.encode(q.getString("request_id"))+"/"+Uri.encode(first.getString("id"))+"/"+n));
                    pi=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|0x04000000);
                }else pi=panel(c,q);
                Notification.Action action=new Notification.Action.Builder((Icon)null,labels[n],pi).build();
                String k=IslandPayloadBuilder.ACTION_KEY_PREFIX+"answer_"+n;actions.putParcelable(k,action);extras.putParcelable(k,action);
            }
            b.setContentTitle(title).setContentText(text).setContentIntent(panel(c,q));
            b.setStyle(new Notification.BigTextStyle().bigText(first.getString("question")));
            extras.putString(HyperIsland.EXTRA_PARAM,json);extras.putBundle(HyperIsland.EXTRA_ACTIONS,actions);extras.putBundle(HyperIsland.EXTRA_PICS,pics);b.addExtras(extras);return true;
        }catch(Exception e){return false;}
    }
    static void refresh(Context c) {
        Intent i=new Intent(c,GlowService.class).setAction("REFRESH_QUESTION");
        try {if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}catch(Exception ignored){}
    }
}
