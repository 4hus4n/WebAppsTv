package com.ahusan.webapps;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the GitHub repo's latest release and installs it.
 * Release tags are "v1.0.N" where N == the app's versionCode (set from the build number in CI).
 */
public class Updater {

    public static final String REPO = "4hus4n/WebAppsTv";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long AUTO_CHECK_EVERY_MS = 3 * 60 * 60 * 1000L;   // 3 hours

    public static class Release {
        public int number;
        public String tag, notes, apkUrl;
        public long size;
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    public static int currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    /** True if enough time has passed since the last automatic check. */
    public static boolean dueForAutoCheck(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("last", 0) > AUTO_CHECK_EVERY_MS;
    }

    /** Remembered result of the last check, so the Update button survives app restarts. */
    public static Release cached(Context c) {
        SharedPreferences p = prefs(c);
        Release r = new Release();
        r.number = p.getInt("num", 0);
        r.tag = p.getString("tag", null);
        r.notes = p.getString("notes", "");
        r.apkUrl = p.getString("url", null);
        r.size = p.getLong("size", 0);
        return (r.tag != null && r.apkUrl != null && r.number > currentVersion(c)) ? r : null;
    }

    /** Network call: returns the newer release, or null if already up to date. Throws on network errors. */
    public static Release check(Context c) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "WebAppsTV-updater");
        int code = con.getResponseCode();
        if (code != 200) throw new Exception("GitHub returned " + code);

        JSONObject o = new JSONObject(readAll(con.getInputStream()));
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.notes = cleanNotes(o.optString("body", ""));
        r.number = parseNumber(r.tag);
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").endsWith(".apk")) {
                    r.apkUrl = a.optString("browser_download_url", null);
                    r.size = a.optLong("size", 0);
                    break;
                }
            }
        }

        prefs(c).edit()
                .putLong("last", System.currentTimeMillis())
                .putInt("num", r.number)
                .putString("tag", r.tag)
                .putString("notes", r.notes)
                .putString("url", r.apkUrl)
                .putLong("size", r.size)
                .apply();

        return (r.apkUrl != null && r.number > currentVersion(c)) ? r : null;
    }

    /** Drops commit trailer lines (Co-Authored-By etc.) from the release notes. */
    static String cleanNotes(String body) {
        StringBuilder b = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("Co-Authored-By:") || t.startsWith("Claude-Session:") || t.startsWith("Signed-off-by:")) continue;
            b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    /** "v1.0.12" -> 12 */
    static int parseNumber(String tag) {
        try {
            String t = tag.replaceAll("[^0-9.]", "");
            return Integer.parseInt(t.substring(t.lastIndexOf('.') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Android 8+: the user must allow this app to install updates once. */
    public static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Downloads the APK straight into an Android install session and asks the system to install it.
     * Runs on a background thread. Android then shows its "Update this app?" confirmation.
     */
    public static void downloadAndInstall(Context c, Release r, Progress progress) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        boolean ok = false;
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(r.apkUrl).openConnection();
            con.setInstanceFollowRedirects(true);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "WebAppsTV-updater");
            // GitHub redirects to its file server; follow manually in case the host changes.
            for (int i = 0; i < 5; i++) {
                int code = con.getResponseCode();
                if (code / 100 != 3) break;
                String next = con.getHeaderField("Location");
                con.disconnect();
                con = (HttpURLConnection) new URL(next).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
            }
            if (con.getResponseCode() != 200) throw new Exception("Download failed (" + con.getResponseCode() + ")");
            long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : r.size;

            try (InputStream in = con.getInputStream();
                 OutputStream out = s.openWrite("update.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    progress.onProgress(done, total);
                }
                s.fsync(out);
            }

            Intent i = new Intent(c, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(p.getIntentSender());
            ok = true;
        } finally {
            if (!ok) s.abandon();
            s.close();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
