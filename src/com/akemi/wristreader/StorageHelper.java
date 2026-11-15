package com.akemi.wristreader;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class StorageHelper {
    private final SharedPreferences prefs;

    public StorageHelper(Context ctx) {
        this.prefs = ctx.getSharedPreferences("wrist_prefs", Context.MODE_PRIVATE);
    }

    public List<String> getList(String key, String defCsv) {
        String raw = prefs.contains(key) ? prefs.getString(key, "") : defCsv;
        List<String> list = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String s : raw.split(",")) {
                String c = s.trim().replaceAll("^/+|/+$", "").replaceFirst("^r/", "");
                if (!c.isEmpty() && !list.contains(c)) list.add(c);
            }
        }
        return list;
    }

    public boolean containsItem(String key, String defCsv, String item) {
        String t = item.trim().replaceAll("^/+|/+$", "").replaceFirst("^r/", "");
        for (String s : getList(key, defCsv)) if (s.equalsIgnoreCase(t)) return true;
        return false;
    }

    public void toggleItem(String key, String defCsv, String item) {
        String t = item.trim().replaceAll("^/+|/+$", "").replaceFirst("^r/", "");
        Set<String> set = new LinkedHashSet<>(getList(key, defCsv));
        String found = null;
        for (String s : set) if (s.equalsIgnoreCase(t)) { found = s; break; }
        if (found != null) set.remove(found); else set.add(t);
        StringBuilder sb = new StringBuilder();
        for (String s : set) { if (sb.length() > 0) sb.append(","); sb.append(s); }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    public List<String> getFollowedSubs() { return getList("followed_subs", "italy,WearOS,technology,androiddev"); }
    public boolean isSubFollowed(String s) { return containsItem("followed_subs", "italy,WearOS,technology,androiddev", s); }
    public void toggleSub(String s) { toggleItem("followed_subs", "italy,WearOS,technology,androiddev", s); }

    public List<String> getFollowedBoards() { return getList("followed_boards", "g,v,sci,wg"); }
    public boolean isBoardFollowed(String b) { return containsItem("followed_boards", "g,v,sci,wg", b); }
    public void toggleBoard(String b) { toggleItem("followed_boards", "g,v,sci,wg", b); }

    public JSONArray getFollowedRss() {
        try { return new JSONArray(prefs.getString("followed_rss_json", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public boolean isRssFollowed(String url) {
        JSONArray arr = getFollowedRss();
        for (int i = 0; i < arr.length(); i++) {
            if (url.equalsIgnoreCase(arr.optJSONObject(i).optString("url"))) return true;
        }
        return false;
    }

    public void toggleRss(String name, String url) {
        JSONArray arr = getFollowedRss();
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && url.equalsIgnoreCase(o.optString("url"))) { removed = true; continue; }
            if (o != null) next.put(o);
        }
        if (!removed) {
            try {
                JSONObject item = new JSONObject();
                item.put("name", name.isEmpty() ? url : name);
                item.put("url", url);
                next.put(item);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString("followed_rss_json", next.toString()).apply();
    }

    public JSONArray getBookmarks() {
        try { return new JSONArray(prefs.getString("bookmarks_json", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public boolean isBookmarked(String id) {
        JSONArray arr = getBookmarks();
        for (int i = 0; i < arr.length(); i++) {
            if (id.equals(arr.optJSONObject(i).optString("id"))) return true;
        }
        return false;
    }

    public void toggleBookmark(String id, String type, String title, String sub, String url, String body) {
        JSONArray arr = getBookmarks();
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) { removed = true; continue; }
            if (o != null) next.put(o);
        }
        if (!removed) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", id); item.put("type", type); item.put("title", title);
                item.put("sub", sub); item.put("url", url);
                item.put("body", body.length() > 4000 ? body.substring(0, 4000) : body);
                JSONArray withNew = new JSONArray();
                withNew.put(item);
                for (int i = 0; i < Math.min(next.length(), 29); i++) withNew.put(next.get(i));
                next = withNew;
            } catch (Exception ignored) {}
        }
        prefs.edit().putString("bookmarks_json", next.toString()).apply();
    }
}
