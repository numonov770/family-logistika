package uz.family.logistika;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Кириш (бот берган код) → WebView (раҳбар: панел, экспедитор: заказлар) + экспедитор учун GPS хизмати. */
public class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(28, 125, 191);
    private static final int REQ_LOC = 11;

    private WebView web;
    private ProgressBar bar;
    private int errors;
    private boolean failed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BLUE);
        }
        if (Net.token(this).isEmpty()) {
            showLogin(null);
        } else {
            openApp();
        }
    }

    // ------------------------------------------------------------------ кириш экрани
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showLogin(String err) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(48), dp(28), dp(28));
        box.setBackgroundColor(Color.WHITE);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        box.addView(logo, new LinearLayout.LayoutParams(dp(140), dp(140)));

        TextView t = new TextView(this);
        t.setText("Family Logistika");
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(BLUE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(6));
        box.addView(t);

        TextView hint = new TextView(this);
        hint.setText("Ботдаги «📲 Android илова» тугмасини босинг ва келган 6 рақамли кодни киритинг.");
        hint.setTextSize(15);
        hint.setTextColor(Color.rgb(84, 91, 105));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(18));
        box.addView(hint);

        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setHint("Код: 123456");
        code.setTextSize(26);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.2f);
        box.addView(code, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        Button go = new Button(this);
        go.setText("Кириш");
        go.setTextSize(18);
        go.setTextColor(Color.WHITE);
        go.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BLUE);
        bg.setCornerRadius(dp(12));
        go.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(16);
        box.addView(go, lp);

        TextView msg = new TextView(this);
        msg.setTextColor(Color.rgb(211, 47, 47));
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(14), 0, 0);
        msg.setTextSize(15);
        if (err != null) {
            msg.setText(err);
        }
        box.addView(msg);

        go.setOnClickListener(v -> {
            String c = code.getText().toString().trim();
            if (c.length() != 6) {
                msg.setText("Код 6 та рақамдан иборат.");
                return;
            }
            go.setEnabled(false);
            msg.setTextColor(Color.rgb(84, 91, 105));
            msg.setText("⏳ Текширилмоқда…");
            new Thread(() -> login(c, msg, go)).start();
        });
        setContentView(box);
    }

    private void login(String code, TextView msg, Button go) {
        String err;
        try {
            String base = Net.resolveBase(this);
            if (base.isEmpty()) {
                err = "Серверга уланиб бўлмади. Интернетни текширинг.";
            } else {
                Object[] r = Net.post(base + "/mob/login", "{\"code\":\"" + code + "\"}", null);
                JSONObject j = new JSONObject((String) r[1]);
                if ((Integer) r[0] == 200 && j.has("token")) {
                    Net.prefs(this).edit()
                            .putString("token", j.getString("token"))
                            .putString("role", j.optString("role", ""))
                            .putString("name", j.optString("name", ""))
                            .apply();
                    runOnUiThread(this::openApp);
                    return;
                }
                err = j.optString("error", "Код нотўғри.");
            }
        } catch (Exception e) {
            err = "Хато: интернет ёки сервер. Бир оздан кейин қайта уриниб кўринг.";
        }
        String e2 = err;
        runOnUiThread(() -> {
            msg.setTextColor(Color.rgb(211, 47, 47));
            msg.setText(e2);
            go.setEnabled(true);
        });
    }

    // ------------------------------------------------------------------ асосий экран
    private void openApp() {
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " FamilyLogistika");
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return external(req.getUrl());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                if (!failed) {
                    errors = 0;
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError e) {
                if (req.isForMainFrame() && !failed) {
                    failed = true;
                    retry();
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                bar.setProgress(p);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean user, Message resultMsg) {
                // window.open (Яндекс / Google харита, телефон) → ташқи илова
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                        openOutside(r.getUrl());
                        return true;
                    }
                });
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        load();
        if ("driver".equals(Net.prefs(this).getString("role", ""))) {
            askLocation();
        }
    }

    private void load() {
        failed = false;
        new Thread(() -> {
            String base = errors == 0 && !Net.base(this).isEmpty() ? Net.base(this) : Net.resolveBase(this);
            String url = base + "/mob/open?t=" + Uri.encode(Net.token(this));
            runOnUiThread(() -> {
                if (base.isEmpty()) {
                    retry();
                } else {
                    web.loadUrl(url);
                }
            });
        }).start();
    }

    private void retry() {
        errors++;
        if (errors > 6) {
            web.loadData("<html><body style='font-family:sans-serif;text-align:center;padding:40px'>"
                    + "<h2>⚠️ Сервер билан алоқа йўқ</h2><p>Офисдаги компьютер ёқиқ ва интернетга уланганини текширинг.</p>"
                    + "<p>Илова 30 сониядан кейин ўзи қайта уринади.</p></body></html>", "text/html; charset=utf-8", "utf-8");
            web.postDelayed(() -> {
                errors = 1;
                load();
            }, 30_000);
            return;
        }
        web.postDelayed(this::load, 3_000L * errors);
    }

    private boolean external(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost();
        String base = Net.base(this);
        if (host.endsWith("trycloudflare.com") || (!base.isEmpty() && base.contains(host))) {
            return false;
        }
        openOutside(u);
        return true;
    }

    private void openOutside(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ GPS рухсати
    private void askLocation() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            GpsService.start(this);
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_LOC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_LOC) {
            return;
        }
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            GpsService.start(this);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("📍 Жойлашув керак")
                    .setMessage("Заказлар очилиши учун жойлашувга рухсат беринг: Созламалар → Иловалар → Family Logistika → Рухсатлар → Жойлашув → «Илова ишлаётганда».")
                    .setPositiveButton("Созламалар", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("Кейин", null)
                    .show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null && "driver".equals(Net.prefs(this).getString("role", ""))
                && (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            GpsService.start(this);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);       // илова ёпилмасин — GPS ишлашда давом этади
        }
    }
}
