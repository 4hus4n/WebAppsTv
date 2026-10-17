package com.ahusan.webapps;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Domain block lists for kid-safe browsing.
 * Lists are downloaded at build time into assets (see scripts/fetch-blocklists.sh).
 */
public class Blocker {

    public static final int OK = 0, AD = 1, ADULT = 2;

    // A few extra ad / pop-up networks on top of the downloaded list.
    private static final String[] EXTRA_ADS = {
            "adsterra.com", "adsterratools.com", "popcash.net", "popunder.net", "onclickads.net",
            "hilltopads.net", "clickadu.com", "juicyads.com", "trafficjunky.net", "adcash.com",
            "a-ads.com", "monetag.com", "galaksion.com", "admaven.com", "richpush.co",
            "pushground.com", "evadav.com", "rollerads.com", "adskeeper.com", "mgid.com",
            "revcontent.com", "zergnet.com"
    };

    private static final Set<String> ads = new HashSet<>(80000);
    private static final Set<String> adult = new HashSet<>(90000);
    private static final CountDownLatch ready = new CountDownLatch(1);
    private static boolean started;

    /** Starts loading the lists in the background (safe to call many times). */
    public static synchronized void init(Context c) {
        if (started) return;
        started = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            load(app, "block_ads.txt", ads);
            load(app, "block_adult.txt", adult);
            for (String d : EXTRA_ADS) ads.add(d);
            ready.countDown();
        }, "blocklist-loader").start();
    }

    private static void load(Context c, String file, Set<String> into) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(file)), 65536)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) into.add(line);
            }
        } catch (Exception ignored) {
        }
    }

    /** Waits (up to 15 s) for the lists to finish loading. Call off the UI thread when possible. */
    public static void await() {
        try {
            ready.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** Checks a host and all its parent domains: a.b.example.com, b.example.com, example.com. */
    public static int check(String host) {
        if (host == null) return OK;
        String h = host.toLowerCase();
        while (true) {
            if (adult.contains(h)) return ADULT;
            if (ads.contains(h)) return AD;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return OK;   // stop at "example.com"
            h = h.substring(dot + 1);
        }
    }

    public static int checkUrl(String url) {
        try {
            return check(Uri.parse(url).getHost());
        } catch (Exception e) {
            return OK;
        }
    }

    /** Forces safe search on Google, Bing and DuckDuckGo. Returns the rewritten URL, or null if unchanged. */
    public static String safeSearch(Uri u) {
        String h = u.getHost();
        if (h == null) return null;
        h = h.toLowerCase();
        String path = u.getPath() == null ? "" : u.getPath();

        if ((h.startsWith("google.") || h.startsWith("www.google."))
                && (path.startsWith("/search") || path.startsWith("/images"))) {
            return withParam(u, "safe", "active");
        }
        if ((h.equals("bing.com") || h.endsWith(".bing.com"))
                && (path.startsWith("/search") || path.startsWith("/images") || path.startsWith("/videos"))) {
            return withParam(u, "adlt", "strict");
        }
        if ((h.equals("duckduckgo.com") || h.endsWith(".duckduckgo.com")) && u.getQueryParameter("q") != null) {
            return withParam(u, "kp", "1");
        }
        return null;
    }

    private static String withParam(Uri u, String key, String value) {
        if (value.equals(u.getQueryParameter(key))) return null;
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String name : u.getQueryParameterNames()) {
            if (name.equals(key)) continue;
            for (String v : u.getQueryParameters(name)) b.appendQueryParameter(name, v);
        }
        b.appendQueryParameter(key, value);
        return b.build().toString();
    }

    /** Simple page shown instead of a blocked website. */
    public static String blockedPage(int kind, String host) {
        String why = kind == ADULT
                ? "This website is not suitable for kids."
                : "This page was blocked by the ad blocker.";
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#0E1116;color:#fff;font-family:sans-serif;text-align:center}"
                + ".b{max-width:640px;padding:24px}.i{font-size:72px}h1{font-size:34px;margin:12px 0}"
                + "p{color:#8B95A5;font-size:20px}small{color:#5b6472}</style></head><body><div class='b'>"
                + "<div class='i'>&#128737;</div><h1>Blocked</h1><p>" + why + "</p>"
                + "<p>Press BACK to go back.</p><small>" + (host == null ? "" : host.replace("<", "")) + "</small>"
                + "</div></body></html>";
    }

    /** CSS that hides common ad boxes left empty on the page. */
    public static final String HIDE_ADS_JS = "(function(){if(document.getElementById('__wa_noads'))return;"
            + "var s=document.createElement('style');s.id='__wa_noads';s.textContent="
            + "'ins.adsbygoogle,[id^=\"google_ads\"],[id^=\"div-gpt-ad\"],[data-ad-slot],[data-ad-client],"
            + "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication\"],iframe[id^=\"google_ads\"],"
            + "amp-ad,amp-embed,amp-sticky-ad,.adsbygoogle,.ad-container,.ad-banner,.ad-slot,.adsbox,.advertisement,"
            + "#ad-banner,.sponsored-ad,.taboola,[id^=\"taboola-\"],.OUTBRAIN,[data-widget-id*=\"outbrain\"]"
            + "{display:none!important;height:0!important}';"
            + "(document.head||document.documentElement).appendChild(s);})()";
}
