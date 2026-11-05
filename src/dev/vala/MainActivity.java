package dev.vala;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONObject;

// One WebView: shows Riot's own login page, catches the token redirect, then loads the local UI.
// The UI does all HTTP through Native.http (native side, so no CORS).
public class MainActivity extends Activity {
    static final String AUTH = "https://auth.riotgames.com/authorize?redirect_uri=https%3A%2F%2Fplayvalorant.com%2Fopt_in"
            + "&client_id=play-valorant-web-prod&response_type=token%20id_token&nonce=1&scope=account%20openid";
    static final String REDIRECT = "https://playvalorant.com/opt_in";
    static final String APP = "file:///android_asset/index.html";

    WebView web;

    // bridge only answers our own page, never Riot's login page. UI thread only.
    boolean isApp() {
        String u = web.getUrl();
        return u != null && u.startsWith(APP);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff0f1923);
        web = new WebView(this);
        web.setBackgroundColor(0xff0f1923);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "")); // some login providers block WebViews
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return intercept(r.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                if (!url.startsWith(APP)) intercept(url);
            }
        });
        // Riot's session cookie is kept, so after the first login this redirects straight back with a fresh token.
        web.loadUrl(AUTH);
    }

    boolean intercept(String url) {
        if (!url.startsWith(REDIRECT)) return false;
        CookieManager.getInstance().flush();
        int i = url.indexOf('#');
        web.stopLoading();
        web.loadUrl(APP + (i >= 0 ? url.substring(i) : ""));
        return true;
    }

    @Override
    public void onBackPressed() {
        if (!isApp()) {
            if (web.canGoBack()) web.goBack(); else super.onBackPressed();
            return;
        }
        // let the page close its overlay (match detail, logout sheet) first; exit only if nothing was open
        web.evaluateJavascript("window.goBack ? goBack() : false", r -> { if (!"true".equals(r)) finish(); });
    }

    class Bridge {
        @JavascriptInterface
        public void http(String id, String method, String url, String headers, String body) {
            // check the page actually showing (UI thread), not a flag that can go stale between navigations
            web.post(() -> { if (isApp()) new Thread(() -> send(id, method, url, headers, body)).start(); });
        }

        void send(String id, String method, String url, String headers, String body) {
            {
                int code = 0;
                String res;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    JSONObject h = new JSONObject(headers);
                    for (Iterator<String> k = h.keys(); k.hasNext(); ) {
                        String key = k.next();
                        c.setRequestProperty(key, h.getString(key));
                    }
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
                    }
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    res = in == null ? "" : read(in);
                } catch (Exception e) {
                    res = e.toString();
                }
                String js = "window.__cb(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(res) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }
        }

        @JavascriptInterface
        public void login() {
            web.post(() -> web.loadUrl(AUTH));
        }

        @JavascriptInterface
        public void logout() {
            web.post(() -> {
                WebStorage.getInstance().deleteAllData();
                web.clearCache(true);
                CookieManager cm = CookieManager.getInstance();
                // removal is async: only reload once Riot's session cookie is really gone
                cm.removeAllCookies(done -> { cm.flush(); web.clearHistory(); web.loadUrl(AUTH); });
            });
        }
    }

    static String read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
