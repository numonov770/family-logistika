package uz.family.logistika;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;

/** Илова ўзи янгиланади: GitHub'даги охирги релизни текширади, юклаб олади ва ўрнатишни очади. */
public final class Updater {
    private Updater() {}

    private static boolean busy;
    private static long lastCheck;

    public static String apkUrl() {
        return "https://github.com/" + BuildConfig.REPO + "/releases/latest/download/family-logistika.apk";
    }

    /** Охирги версия рақами (релиз тег: vN). Хато бўлса -1. */
    public static int latest() {
        try {
            HttpURLConnection h = Net.open("https://api.github.com/repos/" + BuildConfig.REPO + "/releases/latest", "GET");
            h.setUseCaches(false);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            if (h.getResponseCode() != 200) {
                return -1;
            }
            JSONObject j = new JSONObject(Net.read(h.getInputStream()));
            String tag = j.optString("tag_name", "").replaceAll("[^0-9]", "");
            return tag.isEmpty() ? -1 : Integer.parseInt(tag);
        } catch (Exception e) {
            return -1;
        }
    }

    /** manual=false: кириш пайтида жимгина текширади (янги бўлсагина сўрайди). manual=true: ҳар доим жавоб беради. */
    public static void check(Activity a, boolean manual) {
        long now = System.currentTimeMillis();
        if (busy || (!manual && now - lastCheck < 20 * 60_000L)) {
            return;
        }
        lastCheck = now;
        if (manual) {
            Toast.makeText(a, "🔄 Янги версия текширилмоқда…", Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            int v = latest();
            a.runOnUiThread(() -> {
                if (a.isFinishing()) {
                    return;
                }
                if (v > BuildConfig.VERSION_CODE) {
                    new AlertDialog.Builder(a)
                            .setTitle("🆕 Янги версия: 1." + v)
                            .setMessage("Ҳозир сизда 1." + BuildConfig.VERSION_CODE + ". Янгилаймизми? Маълумотлар ва кириш сақланиб қолади.")
                            .setCancelable(true)
                            .setPositiveButton("Янгилаш", (d, w) -> download(a, v))
                            .setNegativeButton("Кейин", null)
                            .show();
                } else if (manual) {
                    Toast.makeText(a, v < 0 ? "Интернет йўқ — кейинроқ уриниб кўринг." : "✅ Сизда энг янги версия (1." + BuildConfig.VERSION_CODE + ").",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private static void download(Activity a, int v) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("Рухсат керак")
                    .setMessage("Янгилаш учун «Family Logistika» га «Номаълум иловаларни ўрнатиш» рухсатини ёқинг, кейин орқага қайтиб яна «Янгилаш» ни босинг.")
                    .setPositiveButton("Созламалар", (d, w) -> {
                        lastCheck = 0;
                        try {
                            a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            openBrowser(a);
                        }
                    })
                    .setNegativeButton("Браузерда юклаш", (d, w) -> openBrowser(a))
                    .show();
            return;
        }
        try {
            busy = true;
            File dir = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            String name = "family-logistika-" + v + ".apk";
            if (dir != null) {
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl()));
            r.setTitle("Family Logistika 1." + v);
            r.setMimeType("application/vnd.android.package-archive");
            r.setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            long id = dm.enqueue(r);

            ProgressDialog pd = new ProgressDialog(a);
            pd.setTitle("⬇️ Юкланмоқда…");
            pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            pd.setMax(100);
            pd.setCancelable(false);
            pd.show();

            Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                int ticks;

                @Override
                public void run() {
                    ticks++;
                    int status = -1;
                    try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                        if (c != null && c.moveToFirst()) {
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                            long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            if (total > 0) {
                                pd.setProgress((int) (done * 100 / total));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        pd.dismiss();
                        busy = false;
                        install(a, dm.getUriForDownloadedFile(id));
                    } else if (status == DownloadManager.STATUS_FAILED || ticks > 300) {
                        pd.dismiss();
                        busy = false;
                        dm.remove(id);
                        Toast.makeText(a, "Юклаб бўлмади — браузер орқали очяпман.", Toast.LENGTH_LONG).show();
                        openBrowser(a);
                    } else {
                        h.postDelayed(this, 1000);
                    }
                }
            });
        } catch (Exception e) {
            busy = false;
            openBrowser(a);
        }
    }

    private static void install(Activity a, Uri u) {
        if (u == null) {
            openBrowser(a);
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            openBrowser(a);
        }
    }

    private static void openBrowser(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl())));
        } catch (Exception ignored) {
        }
    }
}
