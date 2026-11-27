package studio.ellan.maid;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Maid jobs with a price: every job spends the maid's stamina (refilled only by food from her bag),
 * wears her tool and counts against the owner's daily work allowance, which lives in the player's
 * persistent data and therefore follows the player across servers. Farming and cooking need home mode,
 * the work period of the day and the owner online on the same server.
 */
final class Work {
    static final Set<UUID> PROBING = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    final EllanMaid pl;
    final NamespacedKey kStamina, kDay, kUsed, kCookId, kCookUntil;
    /** owner -> (maid -> last tick she worked), to cap the number of working maids per owner */
    final Map<UUID, Map<UUID, Long>> active = new HashMap<>();
    final Map<UUID, Long> lastNotice = new HashMap<>();
    final Map<UUID, Long> nextAction = new HashMap<>();
    final Map<UUID, Location> target = new HashMap<>();
    final Map<String, Long> skipReplant = new HashMap<>();
    final Cooking cooking;
    CropsHook crops; // null when CustomCrops is missing

    Work(EllanMaid pl) {
        this.pl = pl;
        kStamina = new NamespacedKey(pl, "stamina");
        kDay = new NamespacedKey(pl, "work_day");
        kUsed = new NamespacedKey(pl, "work_used");
        kCookId = new NamespacedKey(pl, "cook_id");
        kCookUntil = new NamespacedKey(pl, "cook_until");
        cooking = new Cooking(pl);
        if (Bukkit.getPluginManager().getPlugin("CustomCrops") != null) {
            try { crops = new CropsHook(); } catch (Throwable t) { pl.getLogger().warning("CustomCrops hook off: " + t); }
        }
    }

    ConfigurationSection cfg() { ConfigurationSection s = pl.getConfig().getConfigurationSection("work"); return s != null ? s : pl.getConfig().createSection("work"); }

    // ---------- stamina ----------
    int stamina(Llama z) { return z.getPersistentDataContainer().getOrDefault(kStamina, PersistentDataType.INTEGER, 50); }
    int maxStamina() { return cfg().getInt("stamina-max", 100); }
    void setStamina(Llama z, int v) { z.getPersistentDataContainer().set(kStamina, PersistentDataType.INTEGER, Math.max(0, Math.min(maxStamina(), v))); }

    /** eats one food from the bag to refill stamina; true when she ate */
    boolean eat(Llama z) {
        ItemStack[] bag = pl.items.readBag(z);
        int per = cfg().getInt("stamina-per-food-point", 2);
        for (int i = 0; i < bag.length; i++) {
            ItemStack s = bag[i];
            if (s == null || MaidLife.NOT_FOOD.contains(s.getType().name())) continue;
            FoodProperties f = s.getData(DataComponentTypes.FOOD);
            if (f == null || f.nutrition() <= 0) continue;
            s.setAmount(s.getAmount() - 1);
            if (s.getAmount() <= 0) bag[i] = null;
            pl.items.writeBag(z, bag);
            setStamina(z, stamina(z) + f.nutrition() * per);
            z.getWorld().playSound(z.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.6f, 1.1f);
            return true;
        }
        return false;
    }

    // ---------- owner's daily allowance (player PDC, synced between servers) ----------
    String today() { return LocalDate.now(ZoneId.of(pl.getConfig().getString("time-zone", "Asia/Shanghai"))).toString(); }
    int used(Player p) {
        PersistentDataContainer d = p.getPersistentDataContainer();
        return today().equals(d.get(kDay, PersistentDataType.STRING)) ? d.getOrDefault(kUsed, PersistentDataType.INTEGER, 0) : 0;
    }
    int dailyLimit() { return cfg().getInt("daily-limit", 160); }
    int left(Player p) { return Math.max(0, dailyLimit() - used(p)); }
    void addUsed(Player p, int n) {
        p.getPersistentDataContainer().set(kDay, PersistentDataType.STRING, today());
        p.getPersistentDataContainer().set(kUsed, PersistentDataType.INTEGER, used(p) + n);
    }

    int cost(Llama z, String job) {
        int base = cfg().getInt("cost." + job, 1);
        double off = cfg().getDouble("favor-discount-per-level", 0.1) * pl.life.level(z);
        return Math.max(1, (int) Math.round(base * (1 - off)));
    }

    /** pays for one unit of work; tells the owner (rarely) why not */
    boolean pay(Llama z, Player owner, String job) {
        int c = cost(z, job);
        if (left(owner) < c) { notice(z, owner, "今天女仆已经很累了，明天再让她干活吧。（每日上限 " + dailyLimit() + "）"); return false; }
        while (stamina(z) < c) if (!eat(z)) { notice(z, owner, "女仆没力气了，往她的背包里放些食物吧。"); return false; }
        setStamina(z, stamina(z) - c);
        addUsed(owner, c);
        return true;
    }

    void notice(Llama z, Player owner, String msg) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(z.getUniqueId());
        if (last != null && now - last < 60_000) return;
        lastNotice.put(z.getUniqueId(), now);
        owner.sendMessage(Component.text("[女仆] " + msg));
    }

    Player owner(Llama z) {
        try { return Bukkit.getPlayer(UUID.fromString(z.getPersistentDataContainer().get(pl.kOwner, PersistentDataType.STRING))); }
        catch (Exception e) { return null; }
    }

    /** a maid may claim a working slot of her owner */
    boolean slot(Llama z, Player owner) {
        long now = z.getWorld().getFullTime();
        Map<UUID, Long> m = active.computeIfAbsent(owner.getUniqueId(), k -> new HashMap<>());
        m.values().removeIf(t -> now - t > 200);
        if (!m.containsKey(z.getUniqueId()) && m.size() >= cfg().getInt("max-working-maids", 2)) {
            notice(z, owner, "同时干活的女仆最多 " + cfg().getInt("max-working-maids", 2) + " 位。");
            return false;
        }
        m.put(z.getUniqueId(), now);
        return true;
    }

    // ---------- main loop, called every 0.5 s from EllanMaid.tick; true while she is busy walking ----------
    boolean tick(Llama z) {
        if (!pl.life.home(z)) return false;
        long t = z.getWorld().getTime() % 24000;
        if (t >= 12000) return false; // work period is the day
        Location work = pl.life.homePoint(z, 0);
        if (work == null || !work.getWorld().equals(z.getWorld())) return false;
        Player owner = owner(z);
        finishCooking(z);
        if (owner == null || !owner.getWorld().equals(z.getWorld())
            || owner.getLocation().distanceSquared(work) > sq(cfg().getDouble("owner-range", 128))) return false;
        long now = System.currentTimeMillis();
        if (now < nextAction.getOrDefault(z.getUniqueId(), 0L)) return walking(z);
        ItemStack[] bag = pl.items.readBag(z);
        int tool = farmTool(bag);
        if (tool >= 0) return farm(z, owner, work, bag, tool);
        Block station = cooking.findStation(work, 4);
        if (station != null) { cook(z, owner, station, bag); return false; }
        return false;
    }

    static double sq(double d) { return d * d; }

    boolean walking(Llama z) {
        Location to = target.get(z.getUniqueId());
        return to != null && z.getPathfinder().hasPath();
    }

    // ---------- farming ----------
    static final Map<Material, Material> SEEDS = Map.of(
        Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT, Material.POTATOES, Material.POTATO,
        Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.NETHER_WART);

    int farmTool(ItemStack[] bag) {
        List<String> ids = cfg().getStringList("farm-tools");
        if (ids.isEmpty()) ids = List.of("kaleidoscopecookery:sickle");
        for (int i = 0; i < bag.length; i++) {
            ItemStack s = bag[i];
            if (s == null) continue;
            String ce = EllanMaid.ceId(s);
            if (ce != null ? ids.contains(ce) : s.getType().name().endsWith("_HOE")) return i;
        }
        return -1;
    }

    static int free(ItemStack[] bag) { int n = 0; for (ItemStack s : bag) if (s == null || s.getType().isAir()) n++; return n; }

    boolean farm(Llama z, Player owner, Location work, ItemStack[] bag, int toolSlot) {
        if (free(bag) < 2) { notice(z, owner, "女仆的背包满了，收不下更多作物。"); delay(z, 10_000); return false; }
        Location to = target.get(z.getUniqueId());
        if (to == null || !ripe(to.getBlock())) {
            to = findRipe(work, cfg().getInt("farm-radius", 6));
            if (to == null) { target.remove(z.getUniqueId()); delay(z, 5_000); return false; }
            target.put(z.getUniqueId(), to);
        }
        Location c = to.clone().add(0.5, 0, 0.5);
        if (z.getLocation().distanceSquared(c) > 2.6 * 2.6) {
            z.getPathfinder().moveTo(c, 1.0);
            delay(z, 500);
            return true;
        }
        if (!slot(z, owner)) { delay(z, 10_000); return false; }
        if (!allowed(owner, to.getBlock())) { target.remove(z.getUniqueId()); skipReplant.put(key(to), System.currentTimeMillis() + 600_000); delay(z, 1_000); return false; }
        if (!pay(z, owner, "harvest")) { delay(z, 10_000); return false; }
        ItemStack tool = bag[toolSlot];
        boolean done = harvest(z, owner, to.getBlock(), tool);
        if (done) {
            bag = pl.items.readBag(z);
            int ts = farmTool(bag);
            if (ts >= 0 && !ItemFeatures.damage(bag[ts], cfg().getInt("tool-wear.harvest", 1))) {
                bag[ts] = null;
                notice(z, owner, "女仆的农具用坏了。");
            }
            pl.items.writeBag(z, bag);
            z.swingMainHand();
            pl.getServer().getRegionScheduler().runDelayed(pl, to, t -> { if (!pl.bagOpen(z)) pl.items.pickup(z, 3, 2); }, 10L);
        }
        target.remove(z.getUniqueId());
        delay(z, cfg().getLong("farm-interval-ms", 2000));
        return false;
    }

    void delay(Llama z, long ms) { nextAction.put(z.getUniqueId(), System.currentTimeMillis() + ms); }
    static String key(Location l) { return l.getWorld().getName() + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }

    Location findRipe(Location c, int r) {
        List<Location> hits = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -2; dy <= 2; dy++) {
            Block b = c.getWorld().getBlockAt(c.getBlockX() + dx, c.getBlockY() + dy, c.getBlockZ() + dz);
            if (!c.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            if (ripe(b)) hits.add(b.getLocation());
        }
        if (hits.isEmpty()) return null;
        hits.sort(Comparator.comparingDouble(l -> l.distanceSquared(c)));
        return hits.get(0);
    }

    boolean ripe(Block b) {
        if (SEEDS.containsKey(b.getType()) && b.getBlockData() instanceof Ageable a) return a.getAge() >= a.getMaximumAge();
        return crops != null && crops.ripe(b);
    }

    /** protection: ask other plugins as if the owner right-clicked this block */
    boolean allowed(Player owner, Block b) {
        PlayerInteractEvent probe = new PlayerInteractEvent(owner, Action.RIGHT_CLICK_BLOCK, null, b, BlockFace.UP, EquipmentSlot.OFF_HAND);
        PROBING.add(owner.getUniqueId());
        try { Bukkit.getPluginManager().callEvent(probe); } finally { PROBING.remove(owner.getUniqueId()); }
        return !(probe.isCancelled() || probe.useInteractedBlock() == Event.Result.DENY);
    }

    boolean harvest(Llama z, Player owner, Block b, ItemStack tool) {
        if (SEEDS.containsKey(b.getType())) {
            Material seed = SEEDS.get(b.getType());
            List<ItemStack> drops = new ArrayList<>(b.getDrops(tool));
            boolean replant = false;
            for (ItemStack d : drops) if (d.getType() == seed && d.getAmount() > 0) { d.setAmount(d.getAmount() - 1); replant = true; break; }
            if (!replant) replant = takeOne(z, s -> s.getType() == seed && EllanMaid.ceId(s) == null);
            for (ItemStack d : drops) if (d.getAmount() > 0) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.3, 0.5), d);
            if (replant) { Ageable a = (Ageable) b.getBlockData(); a.setAge(0); b.setBlockData(a); }
            else b.setType(Material.AIR);
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_CROP_BREAK, 0.8f, 1f);
            return true;
        }
        if (crops == null) return false;
        Location loc = b.getLocation();
        String seed = crops.seedOf(b);
        boolean ok = crops.harvest(owner, b, tool);
        if (ok && seed != null && System.currentTimeMillis() > skipReplant.getOrDefault(key(loc), 0L)) {
            pl.getServer().getRegionScheduler().runDelayed(pl, loc, t -> {
                if (crops.occupied(b) || pl.bagOpen(z)) return;
                ItemStack[] bag = pl.items.readBag(z);
                for (int i = 0; i < bag.length; i++) {
                    if (bag[i] == null || !seed.equals(EllanMaid.ceId(bag[i]))) continue;
                    int before = bag[i].getAmount();
                    crops.plant(owner, b, bag[i], seed);
                    if (bag[i].getAmount() < before || crops.occupied(b)) {
                        if (bag[i].getAmount() == before) bag[i].setAmount(before - 1);
                        if (bag[i].getAmount() <= 0) bag[i] = null;
                        pl.items.writeBag(z, bag);
                    } else skipReplant.put(key(loc), System.currentTimeMillis() + 600_000); // season or pot rule refused it
                    return;
                }
            }, 4L);
        }
        return ok;
    }

    boolean takeOne(Llama z, java.util.function.Predicate<ItemStack> f) {
        ItemStack[] bag = pl.items.readBag(z);
        for (int i = 0; i < bag.length; i++) {
            if (bag[i] == null || !f.test(bag[i])) continue;
            bag[i].setAmount(bag[i].getAmount() - 1);
            if (bag[i].getAmount() <= 0) bag[i] = null;
            pl.items.writeBag(z, bag);
            return true;
        }
        return false;
    }

    // ---------- cooking ----------
    void cook(Llama z, Player owner, Block station, ItemStack[] bag) {
        if (z.getPersistentDataContainer().has(kCookId, PersistentDataType.STRING)) return;
        if (!Cooking.heated(station)) { notice(z, owner, "灶台下面没有火，女仆没法做饭。"); delay(z, 10_000); return; }
        if (free(bag) < 1) { notice(z, owner, "女仆的背包满了，放不下做好的菜。"); delay(z, 10_000); return; }
        Cooking.Recipe r = cooking.match(Cooking.kind(station), bag);
        if (r == null) { delay(z, 5_000); return; }
        Location c = station.getLocation().add(0.5, 0, 0.5);
        if (z.getLocation().distanceSquared(c) > 3 * 3) { z.getPathfinder().moveTo(c, 1.0); delay(z, 1_000); return; }
        if (!slot(z, owner)) { delay(z, 10_000); return; }
        if (!allowed(owner, station)) { notice(z, owner, "女仆不能使用这个灶台。"); delay(z, 30_000); return; }
        if (!pay(z, owner, "cook")) { delay(z, 10_000); return; }
        bag = pl.items.readBag(z);
        if (!cooking.consume(r, bag)) { delay(z, 5_000); return; }
        pl.items.writeBag(z, bag);
        var pdc = z.getPersistentDataContainer();
        pdc.set(kCookId, PersistentDataType.STRING, r.result() + "*" + r.count());
        pdc.set(kCookUntil, PersistentDataType.LONG, System.currentTimeMillis() + cfg().getLong("cook-seconds", 20) * 1000);
        z.getWorld().playSound(c, Sound.BLOCK_FIRE_AMBIENT, 1f, 1f);
        z.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c.clone().add(0, 1, 0), 3, 0.2, 0.1, 0.2, 0.01);
    }

    void finishCooking(Llama z) {
        if (pl.bagOpen(z)) return;
        var pdc = z.getPersistentDataContainer();
        String id = pdc.get(kCookId, PersistentDataType.STRING);
        if (id == null) return;
        if (System.currentTimeMillis() < pdc.getOrDefault(kCookUntil, PersistentDataType.LONG, 0L)) {
            if (z.getTicksLived() % 40 < 10) z.getWorld().spawnParticle(Particle.SMOKE, z.getLocation().add(0, 1.6, 0), 2, 0.2, 0.1, 0.2, 0.01);
            return;
        }
        String[] p = id.split("\\*");
        ItemStack out = Cooking.build(pl, p[0]);
        pdc.remove(kCookId); pdc.remove(kCookUntil);
        if (out == null) return;
        out.setAmount(p.length > 1 ? Integer.parseInt(p[1]) : 1);
        ItemStack[] bag = pl.items.readBag(z);
        org.bukkit.inventory.Inventory tmp = Bukkit.createInventory(null, Math.max(9, ((bag.length + 8) / 9) * 9));
        for (int i = 0; i < bag.length; i++) tmp.setItem(i, bag[i]);
        for (ItemStack left : tmp.addItem(out).values()) z.getWorld().dropItemNaturally(z.getLocation(), left);
        for (int i = 0; i < bag.length; i++) bag[i] = tmp.getItem(i);
        pl.items.writeBag(z, bag);
        z.getWorld().playSound(z.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.6f);
    }

    String status(Llama z, Player viewer) {
        String s = "体力 " + stamina(z) + "/" + maxStamina();
        if (viewer != null) s += " · 今日还能干 " + left(viewer);
        return s;
    }

    static boolean lit(Block b) { return !(b.getBlockData() instanceof Lightable l) || l.isLit(); }
    static String ceBlock(Block b) {
        try { var st = CraftEngineBlocks.getCustomBlockState(b); return st == null ? null : st.owner().value().id().asString(); }
        catch (Throwable t) { return null; }
    }
}
