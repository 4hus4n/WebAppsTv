package com.ahusan.webapps;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Saved web apps, kept as a JSON list in SharedPreferences. */
public class Store {

    public static class App {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String url = "";
        public boolean desktop = false;
        public boolean cursor = true;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("desktop", desktop);
            o.put("cursor", cursor);
            return o;
        }

        static App from(JSONObject o) {
            App a = new App();
            a.id = o.optString("id", a.id);
            a.name = o.optString("name", "");
            a.url = o.optString("url", "");
            a.desktop = o.optBoolean("desktop", false);
            a.cursor = o.optBoolean("cursor", true);
            return a;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }

    public static List<App> load(Context c) {
        List<App> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) list.add(App.from(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    public static void save(Context c, List<App> list) {
        JSONArray a = new JSONArray();
        for (App x : list) {
            try {
                a.put(x.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs(c).edit().putString("list", a.toString()).apply();
    }

    public static App find(Context c, String id) {
        if (id == null) return null;
        for (App a : load(c)) if (a.id.equals(id)) return a;
        return null;
    }

    public static void update(Context c, App app) {
        List<App> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(app.id)) {
                list.set(i, app);
                save(c, list);
                return;
            }
        }
    }

    /** "youtube.com" -> "https://youtube.com". Returns null if it doesn't look like a website. */
    public static String normalize(String input) {
        if (input == null) return null;
        String u = input.trim();
        if (u.isEmpty()) return null;
        if (!u.contains("://")) u = "https://" + u;
        Uri uri = Uri.parse(u);
        String host = uri.getHost();
        if (host == null || host.isEmpty() || (!host.contains(".") && !host.equals("localhost"))) return null;
        return u;
    }

    public static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return url;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return url;
        }
    }

    public static String defaultName(String url) {
        String h = host(url);
        String first = h.contains(".") ? h.substring(0, h.indexOf('.')) : h;
        if (first.isEmpty()) return h;
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }
}
