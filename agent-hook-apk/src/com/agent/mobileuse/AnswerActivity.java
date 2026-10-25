package com.agent.mobileuse;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Dialog owned by the module, independent of the DSHA WebView and navigation. */
public final class AnswerActivity extends Activity {
    private JSONObject key,question; private LinearLayout form; private TextView status;
    private final ArrayList<EditText> custom=new ArrayList<>();
    private final ArrayList<ArrayList<CompoundButton>> choices=new ArrayList<>();
    private Button send;private final android.os.Handler handler=new android.os.Handler();
    private boolean closed,terminal,pollPending,submitting,submitted;
    protected void onCreate(Bundle state){super.onCreate(state);setTitle("回答 Agent");GatewayClient.initialize(this);
        try{key=new JSONObject(getIntent().getStringExtra("key"));}catch(Exception e){finish();return;}
        ScrollView scroll=new ScrollView(this);form=new LinearLayout(this);form.setOrientation(1);form.setPadding(32,24,32,24);scroll.addView(form);setContentView(scroll);
        status=new TextView(this);status.setText("正在读取问题…");form.addView(status);load();
    }
    private void load(){new Thread(()->{try{JSONObject q=QuestionBridge.http(this,"/api/question?"+QuestionBridge.query(key),null);runOnUiThread(()->render(q));}
        catch(Exception e){runOnUiThread(()->status.setText(e.getMessage()));}},"question-load").start();}
    private void render(JSONObject q){if(closed)return;question=q;
        if(!"open".equals(q.optString("state"))){updateState(q);return;}
        form.removeAllViews();form.addView(status);status.setText("选择答案或填写其他回答");custom.clear();choices.clear();
        try{JSONArray qs=q.getJSONArray("questions");for(int n=0;n<qs.length();n++){
            JSONObject item=qs.getJSONObject(n);TextView title=new TextView(this);title.setText((n+1)+" / "+qs.length()+"  "+item.getString("question"));title.setTextSize(18);title.setPadding(0,24,0,12);form.addView(title);
            if(!item.optString("detail").isEmpty()){TextView detail=new TextView(this);detail.setText(item.optString("detail"));form.addView(detail);}
            boolean multi=item.optBoolean("multiSelect");LinearLayout group=multi?new LinearLayout(this):new RadioGroup(this);group.setOrientation(1);form.addView(group);
            ArrayList<CompoundButton> buttons=new ArrayList<>();JSONArray options=item.optJSONArray("options");
            if(options!=null)for(int j=0;j<options.length();j++){
                JSONObject o=options.getJSONObject(j);CompoundButton b=multi?new CheckBox(this):new RadioButton(this);b.setId(android.view.View.generateViewId());
                b.setText(o.getString("label")+(o.optString("description").isEmpty()?"":"\n"+o.optString("description")));b.setTag(o.getString("label"));group.addView(b);buttons.add(b);
            }
            JSONArray drafts=q.optJSONArray("drafts");
            if(drafts!=null)for(int d=0;d<drafts.length();d++){JSONObject a=drafts.getJSONObject(d);if(item.getString("id").equals(a.optString("id"))){JSONArray picked=a.optJSONArray("selected");if(picked!=null)for(CompoundButton b:buttons)for(int z=0;z<picked.length();z++)if(b.getTag().equals(picked.getString(z)))b.setChecked(true);}}
            EditText input=new EditText(this);input.setHint("其他回答");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);form.addView(input);custom.add(input);choices.add(buttons);
            if(!multi){input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){} public void onTextChanged(CharSequence s,int st,int before,int count){if(s.length()>0)((RadioGroup)group).clearCheck();}public void afterTextChanged(android.text.Editable e){}});
                for(CompoundButton b:buttons)b.setOnCheckedChangeListener((v,checked)->{if(checked)input.setText("");});}
        }
        send=new Button(this);send.setText("提交答案");send.setOnClickListener(v->submit());form.addView(send);poll();
        }catch(Exception e){status.setText("问题格式不可用，请返回 DSHA 回答");}
    }
    private void submit(){if(closed||terminal||submitting||submitted)return;try{
        JSONArray answers=new JSONArray(),qs=question.getJSONArray("questions");for(int n=0;n<qs.length();n++){
            JSONArray selected=new JSONArray();for(CompoundButton b:choices.get(n))if(b.isChecked())selected.put((String)b.getTag());String text=custom.get(n).getText().toString().trim();
            if(selected.length()==0&&text.isEmpty()){status.setText("请回答第 "+(n+1)+" 题");return;}
            JSONObject a=new JSONObject().put("id",qs.getJSONObject(n).getString("id")).put("selected",selected);if(!text.isEmpty())a.put("custom",text);answers.put(a);
        }
        JSONObject body=new JSONObject(key.toString()).put("answers",answers);submitting=true;enableInputs(false);status.setText("提交中…");
        new Thread(()->{try{QuestionBridge.http(this,"/api/question/answer",body);runOnUiThread(()->{if(closed||terminal)return;submitting=false;submitted=true;status.setText("已提交，等待 Agent 接收");poll();});}
            catch(Exception e){runOnUiThread(()->{if(closed||terminal)return;submitting=false;status.setText(e.getMessage());if(!submitted)enableInputs(true);poll();});}},"question-submit").start();
    }catch(Exception e){status.setText("答案格式错误");}}
    private void enableInputs(boolean enabled){if(send!=null)send.setEnabled(enabled);for(EditText input:custom)input.setEnabled(enabled);for(ArrayList<CompoundButton> group:choices)for(CompoundButton button:group)button.setEnabled(enabled);}
    private void updateState(JSONObject q){if(closed||terminal)return;String state=q.optString("state");
        if("open".equals(state)){poll();return;} // Keep the user's current choices and text while waiting.
        enableInputs(false);
        if("submitted".equals(state)){submitted=true;status.setText("已提交，等待 Agent 接收");poll();return;}
        terminal=true;handler.removeCallbacksAndMessages(null);pollPending=false;
        boolean answered="answered".equals(state);status.setText(answered?"Agent 已接收答案":"问题已结束，本次回答无法继续提交");
        handler.postDelayed(()->{if(!closed)finish();},answered?700:1500);
    }
    private void poll(){if(closed||terminal||pollPending)return;pollPending=true;handler.postDelayed(()->new Thread(()->{
        try{JSONObject q=QuestionBridge.http(this,"/api/question?"+QuestionBridge.query(key),null);runOnUiThread(()->{pollPending=false;updateState(q);});}
        catch(Exception e){runOnUiThread(()->{pollPending=false;if(!closed&&!terminal){if(submitted)status.setText("连接中断，尚未确认接收");poll();}});}
    },"question-result").start(),1000);}
    protected void onDestroy(){closed=true;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
