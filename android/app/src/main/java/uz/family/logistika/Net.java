package uz.family.logistika;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Тармоқ: GitHub'дан жорий манзилни топиш ва сервер билан JSON алмашиш. */
public final class Net {
    private Net() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("logistika", Context.MODE_PRIVATE);
    }

    public static String token(Context c) {
        return prefs(c).getString("token", "");
    }

    public static String base(Context c) {
        return prefs(c).getString("base", "");
    }

    /** GitHub'даги url.json дан жорий манзилни ўқийди (бот уни ўзи янгилаб туради). */
    public static String resolveBase(Context c) {
        String repo = BuildConfig.REPO;
        String[] sources = {
                "https://api.github.com/repos/" + repo + "/contents/url.json",
                "https://raw.githubusercontent.com/" + repo + "/HEAD/url.json?t=" + System.currentTimeMillis()
        };
        for (String src : sources) {
            try {
                HttpURLConnection h = open(src, "GET");
                h.setRequestProperty("Accept", "application/vnd.github.raw+json");
                h.setUseCaches(false);
                h.setRequestProperty("Cache-Control", "no-cache");
                if (h.getResponseCode() == 200) {
                    JSONObject j = new JSONObject(read(h.getInputStream()));
                    String u = j.optString("url", "");
                    if (u.startsWith("https://")) {
                        prefs(c).edit().putString("base", u).apply();
                        return u;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return base(c);
    }

    /** Манзил бизнинг серверми? 1 = ҳа ва код тирик, 0 = ҳа лекин код эскирган, -1 = йўқ/жавоб йўқ. */
    public static int ping(String base, String tok) {
        if (base == null || base.isEmpty()) {
            return -1;
        }
        try {
            HttpURLConnection h = open(base + "/mob/ping?t=" + java.net.URLEncoder.encode(tok == null ? "" : tok, "UTF-8"), "GET");
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setUseCaches(false);
            if (h.getResponseCode() != 200) {
                h.disconnect();
                return -1;
            }
            JSONObject j = new JSONObject(read(h.getInputStream()));
            h.disconnect();
            if (!j.optBoolean("ok") || !"family-logistika".equals(j.optString("app"))) {
                return -1;
            }
            return j.optBoolean("uid") ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Ишлаётган манзилни қайтаради: аввал сақланганини текширади, бўлмаса GitHub'дан янгисини олади. */
    public static String goodBase(Context c, int[] status) {
        String tok = token(c);
        String b = base(c);
        int st = ping(b, tok);
        if (st < 0) {
            for (int i = 0; i < 2 && st < 0; i++) {
                b = resolveBase(c);
                st = ping(b, tok);
                if (st < 0 && i == 0) {
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
                }
            }
        }
        if (status != null && status.length > 0) {
            status[0] = st;
        }
        return st < 0 ? "" : b;
    }

    public static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", "FamilyLogistika-Android");
        return h;
    }

    /** POST JSON → [код, жавоб матни]. */
    public static Object[] post(String url, String json, String mobToken) throws Exception {
        HttpURLConnection h = open(url, "POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (mobToken != null && !mobToken.isEmpty()) {
            h.setRequestProperty("X-Mob", mobToken);
        }
        OutputStream o = h.getOutputStream();
        o.write(json.getBytes(StandardCharsets.UTF_8));
        o.close();
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        String body = in == null ? "" : read(in);
        h.disconnect();
        return new Object[]{code, body};
    }

    public static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        in.close();
        return b.toString("UTF-8");
    }
}
