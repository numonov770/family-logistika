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
                "https://raw.githubusercontent.com/" + repo + "/HEAD/url.json"
        };
        for (String src : sources) {
            try {
                HttpURLConnection h = open(src, "GET");
                h.setRequestProperty("Accept", "application/vnd.github.raw+json");
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
