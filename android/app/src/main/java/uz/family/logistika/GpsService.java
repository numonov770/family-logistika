package uz.family.logistika;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Олд режимдаги хизмат: GPS нуқталарини ҳар 30 сонияда серверга юборади (Telegram жонли жойлашуви ўрнига). */
public class GpsService extends Service implements LocationListener {
    private static final String CH = "gps";
    private static final int NID = 7;
    private static final long SEND_MS = 30_000;

    private LocationManager lm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<double[]> queue = new ArrayList<>();
    private Location last;
    private long lastQueued;
    private int fails;
    private PowerManager.WakeLock wake;

    public static void start(Context c) {
        Intent i = new Intent(c, GpsService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, GpsService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "GPS кузатув", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification n = b.setContentTitle("Family Logistika")
                .setContentText("📍 GPS ёқилган — жойлашув юборилмоқда")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NID, n);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "logistika:gps");
        wake.setReferenceCounted(false);
        wake.acquire(12 * 60 * 60 * 1000L);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000, 0f, this, Looper.getMainLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 20_000, 0f, this, Looper.getMainLooper());
            }
            Location g = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (g != null) {
                last = g;
            }
        } catch (SecurityException ignored) {
        }
        h.postDelayed(tick, 5_000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null) {
            return;
        }
        // GPS аниқроқ: тармоқ нуқтаси фақат GPS бир дақиқа жим бўлса олинади
        if (last != null && LocationManager.NETWORK_PROVIDER.equals(loc.getProvider())
                && LocationManager.GPS_PROVIDER.equals(last.getProvider())
                && loc.getTime() - last.getTime() < 60_000) {
            return;
        }
        last = loc;
        enqueue(loc, System.currentTimeMillis());
    }

    private void enqueue(Location loc, long ts) {
        synchronized (queue) {
            queue.add(new double[]{ts, loc.getLatitude(), loc.getLongitude(), loc.hasAccuracy() ? loc.getAccuracy() : -1});
            if (queue.size() > 2000) {
                queue.remove(0);
            }
        }
        lastQueued = ts;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            // турган пайтда ҳам «тирик» эканини билдириш учун охирги нуқтани қайта юборамиз
            if (last != null && now - lastQueued > SEND_MS) {
                enqueue(last, now);
            }
            new Thread(GpsService.this::send).start();
            h.postDelayed(this, SEND_MS);
        }
    };

    private void send() {
        String tok = Net.token(this);
        if (tok.isEmpty()) {
            stopSelf();
            return;
        }
        List<double[]> batch;
        synchronized (queue) {
            if (queue.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(queue);
        }
        try {
            JSONArray pts = new JSONArray();
            for (double[] p : batch) {
                JSONArray a = new JSONArray();
                a.put((long) p[0]);
                a.put(p[1]);
                a.put(p[2]);
                if (p[3] >= 0) {
                    a.put(Math.round(p[3]));
                } else {
                    a.put(JSONObject.NULL);
                }
                pts.put(a);
            }
            String base = Net.base(this);
            if (base.isEmpty() || fails >= 3) {
                base = Net.resolveBase(this);
                fails = 0;
            }
            Object[] r = Net.post(base + "/mob/gps", "{\"pts\":" + pts + "}", tok);
            int code = (Integer) r[0];
            if (code == 200) {
                synchronized (queue) {
                    queue.subList(0, Math.min(batch.size(), queue.size())).clear();
                }
                fails = 0;
            } else if (code == 401) {
                Net.prefs(this).edit().remove("token").apply();
                stopSelf();
            } else {
                fails++;
            }
        } catch (Exception e) {
            fails++;
        }
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try {
            lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
