package cn.classroom.assistant;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.webkit.*;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.util.Locale;

/** Offline Markdown/KaTeX. No JS bridge, file access, external navigation, or remote resources. */
final class AnswerView extends WebView {
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private String text=""; private boolean ready; private int generation;
    AnswerView(Context context) {
        super(context);setBackgroundColor(Color.TRANSPARENT);setOverScrollMode(OVER_SCROLL_NEVER);
        setLayoutParams(new LinearLayout.LayoutParams(-1,96));
        WebSettings settings=getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);
        // Global network blocking also blocks the virtual HTTPS asset origin before interception.
        // Every request is intercepted below; unknown resources receive 403 and never hit the network.
        settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setBlockNetworkLoads(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(Math.round(100*getResources().getConfiguration().fontScale));
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return local(request.getUrl());}
            @Override public void onPageFinished(WebView view,String url){if(url.startsWith(ORIGIN+"/assets/renderer/")){ready=true;render();}}
        });
        loadUrl(ORIGIN+"/assets/renderer/answer.html");
    }
    private WebResourceResponse local(Uri uri) {
        try {
            String path=uri.getPath();
            if(!"https".equals(uri.getScheme())||!"appassets.androidplatform.net".equals(uri.getHost())||path==null||!path.startsWith("/assets/renderer/")||path.contains(".."))throw new Exception();
            String mime=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".woff2")?"font/woff2":path.endsWith(".woff")?"font/woff":path.endsWith(".ttf")?"font/ttf":"text/html";
            return new WebResourceResponse(mime,"UTF-8",getContext().getAssets().open(path.substring(8)));
        } catch(Exception error) { return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0])); }
    }
    void setAnswer(String value){if(!value.equals(text)){text=value;generation++;if(ready)render();}}
    private void render(){
        final int expected=generation;
        String quoted=JSONObject.quote(text);
        evaluateJavascript("(function(){if(typeof window.renderAnswer==='function'){return window.renderAnswer("+quoted+");}var e=document.getElementById('answer')||document.body;e.style.whiteSpace='pre-wrap';e.textContent="+quoted+";return document.body.scrollHeight;})()",value->{resize(value,expected);});
        new Handler().postDelayed(()->{if(ready&&expected==generation)evaluateJavascript("Math.max(document.body.scrollHeight,document.getElementById('answer').scrollHeight)",value->resize(value,expected));},180);
    }
    private void resize(String value,int expected){
        if(expected!=generation)return;
        try {
            int pixels=Math.round(Math.min(50000,Math.max(40,Float.parseFloat(value)))*getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams parameters=getLayoutParams();
            if(parameters.height!=pixels){parameters.height=pixels;setLayoutParams(parameters);}
        }catch(Exception ignored){}
    }
}
