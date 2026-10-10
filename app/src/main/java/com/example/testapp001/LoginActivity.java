package com.example.testapp001;

import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 既存の棚卸アプリのログイン画面を WebView で出し、<b>ログインできたらその Cookie を借りる</b>。
 *
 * <p>★ サーバ側に新しい入口（API やトークン）を作らずに済むのが狙い。
 * 以降の照会・送信は、この Cookie を付けて同じ画面の口を叩く。
 *
 * <p>★ 開く先は必ず https。http だとログインが平文で流れる。
 */
public class LoginActivity extends AppCompatActivity {

    private WebView web;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        setTitle(R.string.login_title);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.login_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        status = findViewById(R.id.login_status);
        web = findViewById(R.id.login_web);

        String base = ServerConfig.base(this);
        if (!base.startsWith("https://")) {
            // 平文のまま送らせない
            Toast.makeText(this, R.string.login_need_https, Toast.LENGTH_LONG).show();
        }
        status.setText(getString(R.string.login_status, base));

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                // 同じサーバの中だけを開く。外のリンクはこの画面では開かない。
                String u = req.getUrl().toString();
                return !u.startsWith(ServerConfig.base(LoginActivity.this));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // ログイン画面から抜けた（＝ログインできた）ら Cookie を借りる
                if (url != null && !url.contains(ServerConfig.PAGE_LOGIN)) {
                    grabCookie(url);
                }
            }
        });

        web.loadUrl(ServerConfig.loginUrl(this));
    }

    private void grabCookie(String url) {
        String c = CookieManager.getInstance().getCookie(url);
        if (TextUtils.isEmpty(c)) {
            return;
        }
        ServerConfig.setCookie(this, c);
        Toast.makeText(this, R.string.login_done, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
