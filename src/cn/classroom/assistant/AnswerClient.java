package cn.classroom.assistant;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONArray;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;

/** App-specific sessions are issued by the server; there is no provider token in the APK. */
final class AnswerClient {
    interface Listener {void update(String text);}
    private final Context context;
    private volatile boolean cancelled;
    private volatile HttpsURLConnection connection;
    AnswerClient(Context context){this.context=context.getApplicationContext();}
    void cancel(){cancelled=true;HttpsURLConnection current=connection;if(current!=null)current.disconnect();}
    private HttpsURLConnection open(String path,JSONObject body,String token)throws Exception{
        if(cancelled)throw new Exception("已取消");
        HttpsURLConnection c=(HttpsURLConnection)new URL(AppSettings.API+path).openConnection();connection=c;
        c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","text/event-stream, application/json");
        if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
        byte[] bytes=body.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream out=c.getOutputStream()){out.write(bytes);}return c;
    }
    private String session(boolean refresh)throws Exception{
        if(!refresh&&!AppSettings.token(context).isEmpty()&&AppSettings.expires(context)>System.currentTimeMillis()/1000+300)return AppSettings.token(context);
        HttpsURLConnection c=open("/v1/session",new JSONObject().put("install_id",AppSettings.installId(context)),null);
        try{
            if(c.getResponseCode()!=200)throw new Exception(error(c));
            JSONObject value=new JSONObject(read(c.getInputStream(),4096));
            String token=value.getString("token");long expires=value.getLong("expires_at");
            if(token.length()>512)throw new Exception("服务返回异常，请稍后重试");
            AppSettings.token(context,token,expires);return token;
        }finally{c.disconnect();connection=null;}
    }
    String answer(String transcript,Listener listener)throws Exception{
        for(int attempt=0;attempt<2;attempt++){
            String token=session(attempt>0);
            HttpsURLConnection c=open("/v1/answer",new JSONObject().put("transcript",transcript),token);
            try{
                int code=c.getResponseCode();
                if(code==401&&attempt==0){AppSettings.token(context,"",0);continue;}
                if(code!=200)throw new Exception(error(c));
                StringBuilder answer=new StringBuilder();boolean done=false;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"))){
                    String line;
                    while((line=reader.readLine())!=null){
                        if(cancelled)throw new Exception("已取消");
                        if(!line.startsWith("data:"))continue;
                        String data=line.substring(5).trim();
                        if(data.equals("[DONE]")){done=true;break;}
                        if(data.isEmpty())continue;
                        JSONObject item=new JSONObject(data);
                        if(item.has("error"))throw new Exception("回答可能不完整，请检查网络后重试");
                        JSONArray choices=item.optJSONArray("choices");if(choices==null||choices.length()==0)continue;
                        JSONObject choice=choices.getJSONObject(0);JSONObject delta=choice.optJSONObject("delta");
                        if(delta!=null&&!delta.isNull("content")){answer.append(delta.optString("content",""));listener.update(answer.toString());}
                        if("length".equals(choice.optString("finish_reason")))answer.append("\n\n（回答达到长度上限）");
                    }
                }
                if(!done)throw new Exception("回答可能不完整，请检查网络后重试");
                if(answer.toString().trim().isEmpty())throw new Exception("没有收到回答，请稍后重试");
                return answer.toString();
            }finally{c.disconnect();connection=null;}
        }
        throw new Exception("服务连接失败，请稍后重试");
    }
    private String error(HttpsURLConnection c)throws Exception{
        try{
            InputStream stream=c.getErrorStream();if(stream!=null){
                JSONObject json=new JSONObject(read(stream,4096));String detail=json.optString("detail","");
                if(!detail.isEmpty()&&detail.length()<120)return detail;
            }
        }catch(Exception ignored){}
        return c.getResponseCode()==429?"当前使用较多，请稍后重试":"共享服务暂时不可用，请稍后重试";
    }
    private String read(InputStream stream,int limit)throws Exception{
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Response too large");out.write(buffer,0,n);}return out.toString("UTF-8");
        }
    }
}
