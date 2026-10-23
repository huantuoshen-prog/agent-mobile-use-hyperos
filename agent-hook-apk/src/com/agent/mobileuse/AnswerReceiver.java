package com.agent.mobileuse;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AnswerReceiver extends BroadcastReceiver {
    public void onReceive(Context c,Intent i){
        if(!QuestionBridge.ACTION.equals(i.getAction()))return;
        if(((KeyguardManager)c.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked()){
            Toast.makeText(c,"请解锁后回答",Toast.LENGTH_SHORT).show();return;
        }
        PendingResult pending=goAsync();
        new Thread(()->{
            try{
                JSONObject k=new JSONObject(i.getStringExtra("key"));
                JSONObject q=QuestionBridge.http(c,"/api/question?"+QuestionBridge.query(k),null);
                if(!"open".equals(q.optString("state")))throw new Exception("问题已结束或正在提交");
                k.put("question_id",i.getStringExtra("question_id")).put("option_index",i.getIntExtra("option",-1));
                JSONObject result=QuestionBridge.http(c,"/api/question/select",k);
                if(k.getString("request_id").equals(QuestionBridge.current(c).optString("request_id"))){
                    c.getSharedPreferences(QuestionBridge.PREFS,0).edit().putString("data",result.toString()).putString("feedback","submitted".equals(result.optString("state"))?"答案已提交，等待 Agent 接收":"").apply();QuestionBridge.refresh(c);
                }
            }catch(Exception e){new Handler(Looper.getMainLooper()).post(()->Toast.makeText(c,e.getMessage(),Toast.LENGTH_LONG).show());}
            finally{pending.finish();}
        },"island-answer").start();
    }
}
