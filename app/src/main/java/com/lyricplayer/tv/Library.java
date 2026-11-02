package com.lyricplayer.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 마지막 검색 결과. id 는 경로에서 만든 고정값이라 다시 검색해도 같은 파일은 같은 id 를 가진다. */
final class Library {
    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private volatile List<Entry> last = new ArrayList<>();

    void replace(List<Entry> list) {
        byId.clear();
        for (Entry e : list) byId.put(e.id, e);
        last = list;
    }

    Entry get(String id) {
        return byId.get(id);
    }

    String json(int from, int n) {
        JSONArray arr = new JSONArray();
        List<Entry> l = last;
        int to = Math.min(l.size(), from + n);
        try {
            for (int i = Math.max(0, from); i < to; i++) {
                Entry e = l.get(i);
                JSONObject o = new JSONObject();
                o.put("i", e.id);
                o.put("n", e.name);
                o.put("d", e.dir);
                o.put("s", e.size);
                o.put("a", e.audio);
                arr.put(o);
            }
        } catch (JSONException ignored) {
        }
        return arr.toString();
    }
}
