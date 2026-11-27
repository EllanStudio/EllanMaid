package studio.ellan.maid;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Reads the maid asset package jar (content/, runtime/, models/) and installs it for CraftEngine + ModelEngine:
 * CE content gets model_engine furniture elements instead of better_model, bbmodels get the fields
 * ModelEngine needs, and maid models get generated walk/idle/sit animations.
 */
final class Installer {
    private Installer() {}

    static String run(Path source, Path plugins, Path runtimeOut) throws IOException {
        Path ceDir = plugins.resolve("CraftEngine/resources/touhou_little_maid");
        Path meDir = plugins.resolve("ModelEngine/blueprints");
        Files.createDirectories(meDir);
        Files.createDirectories(runtimeOut);
        int content = 0, runtime = 0, models = 0;
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        Map<String, Set<String>> hidden = new HashMap<>();
        hidden.put("tlm_block_snack_cabinet", Set.of("full", "half"));
        try (ZipFile z = new ZipFile(source.toFile())) {
            ZipEntry me = z.getEntry("runtime/tlm_models.json");
            if (me != null) {
                for (JsonElement el : JsonParser.parseString(new String(z.getInputStream(me).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    Set<String> hs = new HashSet<>();
                    for (String k : new String[]{"hidden_bones", "self_hidden_bones"})
                        if (o.has(k)) for (JsonElement b : o.getAsJsonArray(k)) hs.add(b.getAsString());
                    if (!hs.isEmpty()) hidden.put(o.get("key").getAsString(), hs);
                }
            }
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                byte[] data = z.getInputStream(e).readAllBytes();
                if (n.startsWith("content/")) {
                    if (n.endsWith("configuration/items.yml")) {
                        String txt = new String(data, StandardCharsets.UTF_8).replace("craftengine:better_model", "craftengine:model_engine");
                        data = stripCustomBehaviors(txt, gson).getBytes(StandardCharsets.UTF_8);
                    }
                    write(ceDir.resolve(n.substring("content/".length())), data); content++;
                } else if (n.startsWith("runtime/")) {
                    write(runtimeOut.resolve(n.substring("runtime/".length())), data); runtime++;
                } else if (n.startsWith("models/") && n.endsWith(".bbmodel")) {
                    String name = n.substring("models/".length());
                    JsonObject d = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
                    fixTextures(d);
                    Set<String> hb = hidden.get(name.substring(0, name.length() - ".bbmodel".length()));
                    if (hb != null) hideGroups(d, hb);
                    if (name.startsWith("tlm_maid_")) addAnimations(d);
                    write(meDir.resolve(name), gson.toJson(d).getBytes(StandardCharsets.UTF_8)); models++;
                }
            }
        }
        return "content=" + content + " runtime=" + runtime + " models=" + models;
    }

    /** seat/board behaviors are not CraftEngine types; the plugin handles them from the runtime manifests. */
    static String stripCustomBehaviors(String json, Gson gson) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject items = root.getAsJsonObject("items");
        for (var en : items.entrySet()) {
            JsonObject b = en.getValue().getAsJsonObject().has("behavior") ? en.getValue().getAsJsonObject().getAsJsonObject("behavior") : null;
            if (b == null || !b.has("furniture")) continue;
            JsonObject f = b.getAsJsonObject("furniture");
            if (!f.has("behaviors")) continue;
            JsonArray keep = new JsonArray();
            for (JsonElement x : f.getAsJsonArray("behaviors"))
                if (!x.getAsJsonObject().get("type").getAsString().startsWith("touhou_little_maid:")) keep.add(x);
            if (keep.size() == 0) f.remove("behaviors"); else f.add("behaviors", keep);
        }
        return gson.toJson(root);
    }

    static void write(Path p, byte[] b) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, b);
    }

    static void hideGroups(JsonObject d, Set<String> names) {
        if (!d.has("groups")) return;
        for (JsonElement el : d.getAsJsonArray("groups"))
            if (el.isJsonObject() && el.getAsJsonObject().has("name") && names.contains(el.getAsJsonObject().get("name").getAsString()))
                el.getAsJsonObject().addProperty("visibility", false);
    }

    static void fixTextures(JsonObject d) {
        if (!d.has("textures")) return;
        for (JsonElement t : d.getAsJsonArray("textures")) {
            JsonObject o = t.getAsJsonObject();
            if (!o.has("namespace")) o.addProperty("namespace", "");
            if (!o.has("folder")) o.addProperty("folder", "");
        }
    }

    static JsonObject keyframe(double t, double x, double y, double z) {
        JsonObject k = new JsonObject();
        k.addProperty("channel", "rotation");
        JsonObject p = new JsonObject();
        p.addProperty("x", x); p.addProperty("y", y); p.addProperty("z", z);
        JsonArray pts = new JsonArray(); pts.add(p);
        k.add("data_points", pts);
        k.addProperty("uuid", UUID.randomUUID().toString());
        k.addProperty("time", t);
        k.addProperty("interpolation", "linear");
        return k;
    }

    /** track = {x,y,z} per time, times given separately */
    static JsonObject animation(String name, double length, Map<String, double[][]> tracks, double[] times, Map<String, String> groupIds) {
        return animation(name, length, tracks, times, groupIds, "loop");
    }

    static JsonObject animation(String name, double length, Map<String, double[][]> tracks, double[] times, Map<String, String> groupIds, String loop) {
        JsonObject animators = new JsonObject();
        for (var en : tracks.entrySet()) {
            String id = groupIds.get(en.getKey());
            if (id == null) continue;
            JsonObject a = new JsonObject();
            a.addProperty("name", en.getKey());
            a.addProperty("type", "bone");
            JsonArray kfs = new JsonArray();
            double[][] v = en.getValue();
            for (int i = 0; i < v.length; i++) kfs.add(keyframe(times[i], v[i][0], v[i][1], v[i][2]));
            a.add("keyframes", kfs);
            animators.add(id, a);
        }
        if (animators.size() == 0) return null;
        JsonObject an = new JsonObject();
        an.addProperty("uuid", UUID.randomUUID().toString());
        an.addProperty("name", name);
        an.addProperty("loop", loop);
        an.addProperty("override", false);
        an.addProperty("length", length);
        an.addProperty("snapping", 20);
        an.addProperty("selected", false);
        an.addProperty("anim_time_update", "");
        an.addProperty("blend_weight", "");
        an.addProperty("start_delay", "");
        an.addProperty("loop_delay", "");
        an.add("animators", animators);
        return an;
    }

    static void addAnimations(JsonObject d) {
        if (!d.has("groups")) return;
        Map<String, String> g = new HashMap<>();
        Set<String> want = Set.of("legLeft", "legRight", "armLeft", "armRight", "body", "head");
        for (JsonElement el : d.getAsJsonArray("groups")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("name") && want.contains(o.get("name").getAsString())) g.put(o.get("name").getAsString(), o.get("uuid").getAsString());
        }
        if (!g.containsKey("legLeft") && !g.containsKey("armLeft")) return;
        double A = 30, B = 22;
        JsonArray out = new JsonArray();
        double[] t3 = {0, 0.5, 1.0};
        Map<String, double[][]> w = new LinkedHashMap<>();
        w.put("legLeft", new double[][]{{A, 0, 0}, {-A, 0, 0}, {A, 0, 0}});
        w.put("legRight", new double[][]{{-A, 0, 0}, {A, 0, 0}, {-A, 0, 0}});
        w.put("armLeft", new double[][]{{-B, 0, 0}, {B, 0, 0}, {-B, 0, 0}});
        w.put("armRight", new double[][]{{B, 0, 0}, {-B, 0, 0}, {B, 0, 0}});
        add(out, animation("walk", 1.0, w, t3, g));
        Map<String, double[][]> i = new LinkedHashMap<>();
        i.put("armLeft", new double[][]{{0, 0, 2}, {0, 0, -2}, {0, 0, 2}});
        i.put("armRight", new double[][]{{0, 0, -2}, {0, 0, 2}, {0, 0, -2}});
        i.put("head", new double[][]{{0, 0, 0}, {1.5, 0, 0}, {0, 0, 0}});
        add(out, animation("idle", 3.0, i, new double[]{0, 1.5, 3.0}, g));
        Map<String, double[][]> s = new LinkedHashMap<>();
        s.put("legLeft", new double[][]{{-85, 10, 0}, {-85, 10, 0}});
        s.put("legRight", new double[][]{{-85, -10, 0}, {-85, -10, 0}});
        add(out, animation("sit", 1.0, s, new double[]{0, 1.0}, g));
        add(out, attackAnimation(g));
        d.add("animations", out);
    }

    private static void add(JsonArray a, JsonObject o) { if (o != null) a.add(o); }

    /** one swing of the right arm with a little body twist, played once per hit (tools/patch_attack.py does the same) */
    static JsonObject attackAnimation(Map<String, String> g) {
        Map<String, double[][]> a = new LinkedHashMap<>();
        a.put("armRight", new double[][]{{0, 0, 0}, {150, 0, -15}, {40, 0, 0}, {0, 0, 0}});
        a.put("body", new double[][]{{0, 0, 0}, {0, -15, 0}, {0, 10, 0}, {0, 0, 0}});
        return animation("attack", 0.4, a, new double[]{0, 0.1, 0.25, 0.4}, g, "once");
    }
}
