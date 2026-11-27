package studio.ellan.maid;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads the exact ("perfect") pot, stockpot and steamer recipes from the CraftEngine packs
 * (config_factory instances) so a maid can cook them at a heated station. She always uses the exact
 * ingredient list, consumes everything including the carrier, and takes longer than a player.
 */
final class Cooking {
    record Recipe(String kind, String result, int count, String carrier, Map<String, Integer> need, List<String> liquid) {
        int size() { return need.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    final EllanMaid pl;
    final Map<String, List<Recipe>> byKind = new HashMap<>();

    Cooking(EllanMaid pl) { this.pl = pl; }

    void load() {
        byKind.clear();
        Path root = Path.of("plugins/CraftEngine/resources");
        int n = 0;
        if (Files.isDirectory(root)) try (Stream<Path> st = Files.walk(root)) {
            for (Path p : (Iterable<Path>) st.filter(x -> x.toString().endsWith(".yml") && x.toString().contains("configuration"))::iterator) {
                YamlConfiguration y;
                try { y = YamlConfiguration.loadConfiguration(p.toFile()); } catch (Exception e) { continue; }
                for (String key : y.getKeys(false)) {
                    if (!key.startsWith("config_factory#")) continue;
                    String name = key.substring("config_factory#".length());
                    String kind = name.startsWith("stockpot") ? "stockpot" : name.startsWith("steamer") ? "steamer" : name.startsWith("pot") ? "pot" : null;
                    if (kind == null) continue;
                    for (Map<?, ?> m : y.getMapList(key + ".instances")) {
                        Recipe r = parse(kind, m);
                        if (r != null) { byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(r); n++; }
                    }
                }
            }
        } catch (Exception e) { pl.getLogger().warning("cooking recipes: " + e); }
        // prefer dishes with more ingredients, so simple roasting does not eat a stew's meat
        byKind.values().forEach(l -> l.sort(Comparator.comparingInt(Recipe::size).reversed()));
        pl.getLogger().info("maid cooking recipes: " + n);
    }

    static Recipe parse(String kind, Map<?, ?> m) {
        Object res = m.get("result");
        if (res == null) return null;
        Map<String, Integer> need = new LinkedHashMap<>();
        if (m.get("perfect") instanceof Map<?, ?> pf) for (var e : pf.entrySet()) need.put(e.getKey().toString(), Integer.parseInt(e.getValue().toString()));
        else if (m.get("require") != null) need.put(m.get("require").toString(), 1);
        if (need.isEmpty() || need.keySet().stream().anyMatch(k -> k.startsWith("#") || k.contains("${"))) return null;
        List<String> liquid = new ArrayList<>();
        if (m.get("liquid") instanceof List<?> l) for (Object o : l) liquid.add(o.toString());
        Object carrier = m.get("carrier");
        int count = m.get("count") != null ? Integer.parseInt(m.get("count").toString()) : 1;
        return new Recipe(kind, res.toString(), count, carrier == null ? "minecraft:air" : carrier.toString(), need, liquid);
    }

    static String idOf(ItemStack s) {
        String ce = EllanMaid.ceId(s);
        return ce != null ? ce : s.getType().getKey().toString();
    }

    /** first recipe of that station whose ingredients, carrier and liquid are all in the bag */
    Recipe match(String kind, ItemStack[] bag) {
        Map<String, Integer> have = new HashMap<>();
        for (ItemStack s : bag) if (s != null && !s.getType().isAir()) have.merge(idOf(s), s.getAmount(), Integer::sum);
        for (Recipe r : byKind.getOrDefault(kind, List.of())) {
            Map<String, Integer> want = new HashMap<>(r.need());
            if (!r.carrier().equals("minecraft:air")) want.merge(r.carrier(), r.count(), Integer::sum);
            if (!r.liquid().isEmpty()) want.merge(r.liquid().get(0), 1, Integer::sum);
            boolean ok = true;
            for (var e : want.entrySet()) if (have.getOrDefault(e.getKey(), 0) < e.getValue()) { ok = false; break; }
            if (ok) return r;
        }
        return null;
    }

    boolean consume(Recipe r, ItemStack[] bag) {
        Map<String, Integer> want = new HashMap<>(r.need());
        if (!r.carrier().equals("minecraft:air")) want.merge(r.carrier(), r.count(), Integer::sum);
        String liquid = r.liquid().isEmpty() ? null : r.liquid().get(0);
        if (liquid != null) want.merge(liquid, 1, Integer::sum);
        for (var e : want.entrySet()) {
            int left = e.getValue();
            for (int i = 0; i < bag.length && left > 0; i++) {
                if (bag[i] == null || !e.getKey().equals(idOf(bag[i]))) continue;
                int take = Math.min(left, bag[i].getAmount());
                left -= take;
                bag[i].setAmount(bag[i].getAmount() - take);
                if (bag[i].getAmount() <= 0) bag[i] = null;
            }
            if (left > 0) return false;
        }
        if (liquid != null && liquid.endsWith("_bucket"))
            for (int i = 0; i < bag.length; i++) if (bag[i] == null) { bag[i] = new ItemStack(Material.BUCKET); break; }
        return true;
    }

    static ItemStack build(EllanMaid pl, String id) {
        if (id.startsWith("minecraft:")) {
            Material m = Material.matchMaterial(id);
            return m == null ? null : new ItemStack(m);
        }
        return pl.ceItem(id);
    }

    static final Set<String> STATIONS = Set.of("kaleidoscopecookery:pot", "kaleidoscopecookery:stockpot", "kaleidoscopecookery:steamer");

    static String kind(Block b) {
        String id = Work.ceBlock(b);
        if (id == null) return null;
        return id.contains("stockpot") ? "stockpot" : id.contains("steamer") ? "steamer" : id.contains("pot") ? "pot" : null;
    }

    Block findStation(Location c, int r) {
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -2; dy <= 2; dy++) {
            Block b = c.getWorld().getBlockAt(c.getBlockX() + dx, c.getBlockY() + dy, c.getBlockZ() + dz);
            if (b.getType().isAir() || b.getType().isOccluding()) continue;
            String id = Work.ceBlock(b);
            if (id == null || STATIONS.stream().noneMatch(id::startsWith)) continue;
            double d = b.getLocation().distanceSquared(c);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    /** a station is heated by a stove, fire, lava, magma or a lit campfire below it (a steamer may sit on a pot) */
    static boolean heated(Block station) {
        Block b = station.getRelative(BlockFace.DOWN);
        for (int i = 0; i < 2; i++) {
            String id = Work.ceBlock(b);
            if (id != null && id.contains("stove")) return true;
            switch (b.getType()) {
                case FIRE, SOUL_FIRE, LAVA, MAGMA_BLOCK -> { return true; }
                case CAMPFIRE, SOUL_CAMPFIRE -> { return Work.lit(b); }
                default -> { }
            }
            if (id == null || !(id.contains("pot"))) return false;
            b = b.getRelative(BlockFace.DOWN);
        }
        return false;
    }
}
