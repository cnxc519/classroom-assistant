package cn.classroom.assistant.uitests;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.widget.ScrollView;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Separate test APK. Uses the target's actual HTTPS client, renderer and installed assets. */
public final class UiSmoke extends Instrumentation {
    private boolean live;
    @Override public void onCreate(Bundle args){super.onCreate(args);live=args!=null&&"true".equals(args.getString("live"));start();}
    private Class<?> app(String name)throws Exception{return getTargetContext().getClassLoader().loadClass("cn.classroom.assistant."+name);}
    private void field(String name,Object value)throws Exception{Field f=app("Session").getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    private String javascript(WebView view,String script)throws Exception{
        CountDownLatch latch=new CountDownLatch(1);String[] output={null};
        runOnMainSync(()->view.evaluateJavascript(script,result->{output[0]=result;latch.countDown();}));
        if(!latch.await(5,TimeUnit.SECONDS))throw new Exception("JavaScript callback timed out");return output[0];
    }
    private void show(String answer,String image)throws Exception{
        field("answer",answer);field("revision",100);field("status","验证回答显示");
        Intent intent=new Intent(getTargetContext(),app("MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity=startActivitySync(intent);
        Field f=app("MainActivity").getDeclaredField("answer");f.setAccessible(true);
        WebView view=(WebView)f.get(activity);if(view==null)throw new Exception("WebView unavailable");
        java.util.List<String> console=new java.util.concurrent.CopyOnWriteArrayList<>();
        runOnMainSync(()->{
            view.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){console.add(m.message()+" @"+m.sourceId()+":"+m.lineNumber());return true;}});
            WebViewClient original=view.getWebViewClient();
            view.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){WebResourceResponse response=original.shouldInterceptRequest(v,r);console.add("resource "+r.getUrl()+" status="+(response==null?"null":response.getStatusCode()));return response;}
                @Override public void onPageFinished(WebView v,String u){original.onPageFinished(v,u);}
                @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){console.add("error "+r.getUrl()+" "+e.getErrorCode()+" "+e.getDescription());}
            });view.reload();});
        String result="";
        for(int n=0;n<30;n++){
            result=javascript(view,"JSON.stringify({math:document.querySelectorAll('.katex').length,errors:document.querySelectorAll('.katex-error').length,ready:typeof window.renderAnswer==='function',text:document.body.innerText.length})");
            if(result!=null&&result.contains("\\\"math\\\":")&&!result.contains("\\\"math\\\":0"))break;
            Thread.sleep(500);
        }
        if(result==null||result.contains("\\\"math\\\":0")||!result.contains("\\\"errors\\\":0"))throw new Exception("Math not rendered: "+result+"; console="+console);
        runOnMainSync(()->{android.view.View parent=view;while(parent.getParent() instanceof android.view.View){parent=(android.view.View)parent.getParent();if(parent instanceof ScrollView){((ScrollView)parent).smoothScrollTo(0,view.getTop()+((android.view.View)view.getParent()).getTop());break;}}});
        Thread.sleep(1000);
        Bitmap screenshot=getUiAutomation().takeScreenshot();
        try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),image))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
        screenshot.recycle();
        runOnMainSync(activity::finish);
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        try(InputStream asset=getTargetContext().getAssets().open("renderer/answer.html")){if(asset.read()<0)throw new Exception("Empty renderer asset");}
        catch(IOException missing){throw new Exception("Renderer absent in "+getTargetContext().getPackageName()+" path="+getTargetContext().getPackageCodePath()+"; assets="+java.util.Arrays.toString(getTargetContext().getAssets().list(""))+"; renderer="+java.util.Arrays.toString(getTargetContext().getAssets().list("renderer")));}
        if(live){
            Constructor<?> ctor=app("AnswerClient").getDeclaredConstructor(Context.class);ctor.setAccessible(true);Object client=ctor.newInstance(getTargetContext());
            Class<?> listener=app("AnswerClient$Listener");Object callback=Proxy.newProxyInstance(listener.getClassLoader(),new Class[]{listener},(p,m,a)->null);
            Method method=app("AnswerClient").getDeclaredMethod("answer",String.class,listener);method.setAccessible(true);
            String answer=(String)method.invoke(client,"命题 p→q 与其逆否命题是否等价？请给出逻辑等价式和真值表。",callback);
            if(answer.length()<20||!answer.contains("p")||!answer.contains("q"))throw new Exception("Unexpected live answer");
            show(answer,"classroom-live.png");result.putInt("live_answer_characters",answer.length());
        }
        show("**公式显示验证**\n\n二次方程的根：\\[x=\\frac{3\\pm\\sqrt{5}}{2}\\]\n\n积分与矩阵：\\[\\int_0^1 x^2\\,dx=\\frac13\\]\n\\[A=\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}\\]\n\n- 中文说明与 **重点**\n- 行内公式 $a^2+b^2=c^2$", "classroom-math.png");
        show("```markdown\n### 离散数学显示验证\n\n**逆否等价**：$p\\to q\\equiv\\neg q\\to\\neg p$\n\n```latex\n\\forall x\\in A,\\ \\exists y\\in B:(x,y)\\in R\n```\n\n| $p$ | $q$ | $p\\to q$ |\n| --- | --- | --- |\n| 真 | 真 | 真 |\n| 真 | 假 | 假 |\n| 假 | 真 | 真 |\n| 假 | 假 | 真 |\n\n集合写法：$A=\\{x | x\\in B\\}$\n```", "classroom-discrete.png");
        result.putString("result","PASS: installed WebView assets, Markdown wrappers, math fences, logic, sets, truth tables, fractions and matrix; live HTTPS="+live);
        finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("failure",error.toString());if(error instanceof InvocationTargetException)result.putString("cause",((InvocationTargetException)error).getTargetException().toString());finish(Activity.RESULT_CANCELED,result);}}
}
