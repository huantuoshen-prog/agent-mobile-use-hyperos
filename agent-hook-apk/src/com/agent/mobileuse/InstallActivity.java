package com.agent.mobileuse;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/** Installation checks remain visible when the module gateway cannot start. */
public final class InstallActivity extends Activity {
    private LinearLayout content,checks,updates;private TextView status;private JSONObject report,release,download;
    private File stagedDownload;private boolean downloadBusy;
    private static final String REPO="https://github.com/huantuoshen-prog/agent-mobile-use-hyperos";
    protected void onCreate(Bundle state){super.onCreate(state);
        if(state!=null)try{
            File restored=new File(state.getString("staged_download",""));
            if(restored.getCanonicalFile().getParentFile().equals(getCacheDir().getCanonicalFile())&&restored.getName().startsWith("mobile-release-")){
                download=new JSONObject(state.getString("download_asset","{}"));stagedDownload=restored;downloadBusy=true;
            }
        }catch(Exception ignored){}
        setTitle("安装与更新");GatewayClient.initialize(this);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(32,24,32,24);scroll.addView(content);setContentView(scroll);
        label(content,"模块 "+ReleaseInfo.MODULE+" · 配套插件 "+ReleaseInfo.PLUGIN,20);
        status=label(content,"正在检查…",15);checks=new LinearLayout(this);checks.setOrientation(1);content.addView(checks);
        button("重新检查",()->check());button("修复 DSHA 连接",()->repair());button("通知权限",()->{
            Intent i=new Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE",getPackageName());startActivity(i);});
        button("打开 KernelSU",()->openApp(new String[]{"me.weishu.kernelsu","me.weishu.kernelsu.next"}));
        button("打开 LSPosed",()->{try{startActivity(new Intent("android.intent.action.MAIN").setPackage("org.lsposed.manager").addCategory("android.intent.category.LAUNCHER"));}catch(Exception e){status.setText("请从 LSPosed 通知或管理器入口打开");}});
        button("打开 DSHA（导入插件）",()->openApp(new String[]{"com.dsh.client"}));
        button("复制诊断报告",()->{if(report!=null){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Mobile Use 诊断",report.toString()));status.setText("已复制诊断报告");}});
        button("复制验收任务给 DSHA",()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("验收", "调用 mobile_diagnose 和 mobile_status，再使用 mobile-use 后台副屏打开系统设置，只读界面并截图，完成后 mobile_mode(idle) 和 mobile_stop。随后提出一道简短二选一问题，让我在超级岛回答。"));status.setText("已复制验收任务，粘贴到 DSHA 执行");});
        button("检查更新（预发布）",()->checkUpdate());updates=new LinearLayout(this);updates.setOrientation(1);content.addView(updates);
        check();
    }
    private TextView label(LinearLayout parent,String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setPadding(0,12,0,12);parent.addView(v);return v;}
    private void button(String text,Runnable task){addButton(content,text,task);}
    private void addButton(LinearLayout parent,String text,Runnable task){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->task.run());parent.addView(b);}
    private void openApp(String[] packages){for(String p:packages){Intent i=getPackageManager().getLaunchIntentForPackage(p);if(i!=null){i.putExtra("open_web",true);startActivity(i);return;}}status.setText("未找到应用入口，请从应用或管理器通知打开");}
    private void localCheck(JSONObject diagnostic)throws Exception{
        JSONArray list=diagnostic.getJSONArray("checks");
        list.put(new JSONObject().put("id","app_version").put("state","pass").put("detail","控制台 "+ReleaseInfo.MODULE));
        String gateway=diagnostic.optString("module_version");
        if(!gateway.isEmpty())list.put(new JSONObject().put("id","version_pair").put("state",ReleaseInfo.MODULE.equals(gateway)?"pass":"fail").put("detail",ReleaseInfo.MODULE.equals(gateway)?"控制台与网关版本一致":"控制台与网关版本不一致，请完成模块更新并重启"));
        boolean notified=((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled();
        list.put(new JSONObject().put("id","notifications").put("state",notified?"pass":"pending").put("detail",notified?"通知已允许":"请允许控制台发送通知"));
        list.put(new JSONObject().put("id","hook_runtime").put("state",SettingsActivity.isModuleActive()?"pass":"unknown").put("detail",SettingsActivity.isModuleActive()?"本应用 LSPosed Hook 已生效":"未确认本应用 Hook 生效，请核对作用域并重启"));
        list.put(new JSONObject().put("id","island").put("state","info").put("detail",HyperIsland.describeState(this)));
    }
    private void check(){status.setText("检查安装与接入状态…");new Thread(()->{
        JSONObject d;try{d=QuestionBridge.http(this,"/api/diagnostics",null);}catch(Exception e){d=new JSONObject();try{d.put("checks",new JSONArray().put(new JSONObject().put("id","gateway").put("state","fail").put("detail","网关未连接或版本尚不支持诊断").put("next","在 KernelSU 检查模块并重启，首次解锁后重试")));}catch(Exception ignored){}}
        try{localCheck(d);}catch(Exception ignored){}final JSONObject value=d;
        runOnUiThread(()->{if(isFinishing())return;report=value;checks.removeAllViews();JSONArray list=value.optJSONArray("checks");if(list!=null)for(int n=0;n<list.length();n++){JSONObject c=list.optJSONObject(n);String s=c.optString("state");String name="pass".equals(s)?"通过":"pending".equals(s)?"待处理":"fail".equals(s)?"失败":"unknown".equals(s)?"无法确认":"信息";label(checks,"["+name+"] "+c.optString("detail")+(c.optString("next").isEmpty()?"":"\n"+c.optString("next")),15);}status.setText("检查完成；副屏和超级岛需执行实际验收任务");});
    },"installation-check").start();}
    private void repair(){status.setText("重新交付连接凭据…");new Thread(()->{try{JSONObject result=QuestionBridge.http(this,"/api/integration/repair",new JSONObject());runOnUiThread(()->{status.setText(result.optBoolean("success")?"连接凭据已修复，请重启 DSHA Web 后重新检查":result.optJSONObject("check").optString("detail"));});}catch(Exception e){runOnUiThread(()->status.setText(e.getMessage()));}},"integration-repair").start();}
    private JSONObject fetchRelease()throws Exception{
        URL url=new URL("https://api.github.com/repos/huantuoshen-prog/agent-mobile-use-hyperos/contents/updates/alpha.json");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setRequestProperty("Accept","application/vnd.github.raw+json");
        try{if(c.getResponseCode()!=200)throw new Exception("更新清单暂不可用，可从 GitHub Release 查看");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>32768)throw new Exception("更新清单无效");}return new JSONObject(out.toString("UTF-8"));}}finally{c.disconnect();}
    }
    private void checkUpdate(){status.setText("正在检查预发布更新…");new Thread(()->{try{
        JSONObject r=fetchRelease();int code=r.getInt("versionCode");JSONObject plugin=r.getJSONObject("plugin");
        runOnUiThread(()->{release=r;updates.removeAllViews();status.setText(code>ReleaseInfo.CODE?"发现配套更新":"当前模块无更高版本");label(updates,"模块 "+r.optString("version")+" / 插件 "+plugin.optString("version")+"\n先安装模块，重启并解锁，再导入插件并重启 DSHA Web。",16);
            addButton(updates,"下载并校验模块",()->chooseDownload(r.optJSONObject("module")));
            addButton(updates,"下载并校验插件",()->chooseDownload(plugin));addButton(updates,"查看更新说明",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(REPO+"/releases"))));});
    }catch(Exception e){runOnUiThread(()->{status.setText(e.getMessage());updates.removeAllViews();addButton(updates,"打开 GitHub Releases",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(REPO+"/releases"))));});}},"check-release").start();}
    private void chooseDownload(JSONObject asset){
        if(downloadBusy){status.setText("已有下载或保存操作正在进行");return;}
        try{
            String url=asset.getString("url"),name=asset.getString("file");
            if(!url.startsWith(REPO+"/releases/download/")||!name.matches("[A-Za-z0-9._-]+\\.zip")||!asset.getString("sha256").matches("[a-fA-F0-9]{64}"))throw new Exception("下载元数据无效");
            download=new JSONObject(asset.toString());downloadBusy=true;status.setText("下载并核对 SHA-256…");
            new Thread(()->{
                File tmp=null;
                try{
                    tmp=File.createTempFile("mobile-release-",".zip",getCacheDir());
                    HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);
                    try{
                        if(c.getResponseCode()!=200)throw new IOException("下载失败：HTTP "+c.getResponseCode());
                        try(InputStream in=c.getInputStream()){ReleaseFile.stage(in,tmp,asset.getString("sha256"));}
                    }finally{c.disconnect();}
                    final File verified=tmp;
                    runOnUiThread(()->{
                        if(isFinishing()||isDestroyed()){verified.delete();downloadBusy=false;return;}
                        stagedDownload=verified;status.setText("校验通过，请选择保存位置");
                        try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,name),51);}
                        catch(Exception e){clearDownload();status.setText(e.getMessage());}
                    });
                }catch(Exception e){if(tmp!=null)tmp.delete();runOnUiThread(()->{downloadBusy=false;if(!isDestroyed())status.setText(e.getMessage());});}
            },"download-release").start();
        }catch(Exception e){status.setText(e.getMessage());}
    }
    protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
        if(stagedDownload!=null&&download!=null){state.putString("staged_download",stagedDownload.getAbsolutePath());state.putString("download_asset",download.toString());}
    }
    private void clearDownload(){if(stagedDownload!=null)stagedDownload.delete();stagedDownload=null;download=null;downloadBusy=false;}
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=51)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){clearDownload();status.setText("已取消保存");return;}
        if(stagedDownload==null||download==null){status.setText("下载缓存已失效，请重新下载");downloadBusy=false;return;}
        final Uri destination=data.getData();final File verified=stagedDownload;final JSONObject asset=download;status.setText("正在保存已校验文件…");
        new Thread(()->{try{
            ReleaseFile.verify(verified,asset.getString("sha256"));
            try(InputStream in=new FileInputStream(verified);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
                if(out==null)throw new IOException("无法保存文件");byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            runOnUiThread(()->status.setText("校验通过，文件已保存。请在对应管理器选择该 ZIP 安装。"));
        }catch(Exception e){runOnUiThread(()->status.setText(e.getMessage()));}finally{runOnUiThread(()->clearDownload());}},"save-release").start();
    }
    protected void onDestroy(){if(isFinishing())clearDownload();super.onDestroy();}
}
