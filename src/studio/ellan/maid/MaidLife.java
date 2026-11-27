package studio.ellan.maid;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** Favorability levels, health, damage, home mode, tombstones, photos, statues, garage kits, shrine and maid beacon. */
final class MaidLife implements Listener {
    static final String NS = "touhou_little_maid:";
    static final int[] HP = {20, 30, 40, 80};
    static final double[] ATK = {2, 3, 4, 6};
    static final Set<String> NOT_FOOD = Set.of("PUFFERFISH", "POISONOUS_POTATO", "ROTTEN_FLESH", "SPIDER_EYE");
    final EllanMaid pl;
    final NamespacedKey kTomb, kKit, kHp, kHome, kHomeOn, kStruck, kFilm, kBeacon, kBeaconPotion, kBeaconPower;
    final Random rnd = new Random();

    MaidLife(EllanMaid pl) {
        this.pl = pl;
        kTomb = new NamespacedKey(pl, "tomb");
        kKit = new NamespacedKey(pl, "kit");
        kHp = new NamespacedKey(pl, "hp");
        kHome = new NamespacedKey(pl, "home");
        kHomeOn = new NamespacedKey(pl, "home_on");
        kStruck = new NamespacedKey(pl, "struck");
        kFilm = new NamespacedKey(pl, "film");
        kBeacon = new NamespacedKey(pl, "beacon");
        kBeaconPotion = new NamespacedKey(pl, "beacon_potion");
        kBeaconPower = new NamespacedKey(pl, "beacon_power");
    }

    // ---------- favorability and stats ----------
    int fav(Llama z) { return z.getPersistentDataContainer().getOrDefault(pl.kFav, PersistentDataType.INTEGER, 0); }
    static int level(int fav) { return fav >= 384 ? 3 : fav >= 192 ? 2 : fav >= 64 ? 1 : 0; }
    int level(Llama z) { return level(fav(z)); }
    double attack(Llama z) { return ATK[level(z)]; }

    void addFav(Llama z, int delta) {
        int v = Math.max(0, Math.min(384, fav(z) + delta));
        z.getPersistentDataContainer().set(pl.kFav, PersistentDataType.INTEGER, v);
        applyLevel(z);
    }

    void applyLevel(Llama z) {
        double max = HP[level(z)] + (z.getPersistentDataContainer().has(kStruck, PersistentDataType.BYTE) ? 20 : 0);
        var mh = z.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null && mh.getBaseValue() != max) mh.setBaseValue(max);
        if (z.getHealth() > max) z.setHealth(max);
    }

    void init(Llama z) { applyLevel(z); }

    // ---------- home mode ----------
    void setHome(Llama z, boolean on) {
        if (on) z.getPersistentDataContainer().set(kHomeOn, PersistentDataType.BYTE, (byte) 1);
        else z.getPersistentDataContainer().remove(kHomeOn);
    }
    boolean home(Llama z) { return z.getPersistentDataContainer().has(kHomeOn, PersistentDataType.BYTE); }
    void clearHome(Llama z) { z.getPersistentDataContainer().remove(kHome); setHome(z, false); }
    void writeHome(Llama z, String[] pts) { z.getPersistentDataContainer().set(kHome, PersistentDataType.STRING, String.join(";", pts)); setHome(z, true); }

    Location homePoint(Llama z, int idx) {
        String s = z.getPersistentDataContainer().get(kHome, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return null;
        String[] pts = s.split(";");
        if (idx >= pts.length) idx = idx == 2 && pts.length > 1 ? 1 : 0; // sleep falls back to idle, then work
        String[] c = pts[Math.min(idx, pts.length - 1)].split("\\|");
        World w = Bukkit.getWorld(c[0]);
        return w == null ? null : new Location(w, Integer.parseInt(c[1]) + 0.5, Integer.parseInt(c[2]), Integer.parseInt(c[3]) + 0.5);
    }

    /** returns true while walking */
    boolean navigate(Llama z, Player owner) {
        if (home(z)) {
            long t = z.getWorld().getTime() % 24000;
            int idx = t < 12000 ? 0 : t < 16000 ? 1 : 2;
            double radius = idx == 0 ? 12 : 6;
            Location c = homePoint(z, idx);
            if (c == null || !c.getWorld().equals(z.getWorld())) return false;
            double d = z.getLocation().distance(c);
            if (d > radius + 4) { z.teleportAsync(c); return false; }
            if (d > radius) { z.getPathfinder().moveTo(c, 1.0); return true; }
            z.getPathfinder().stopPathfinding();
            return false;
        }
        if (owner == null || !owner.getWorld().equals(z.getWorld()) || owner.getGameMode() == GameMode.SPECTATOR) { z.getPathfinder().stopPathfinding(); return false; }
        double d = owner.getLocation().distance(z.getLocation());
        if (d > 14) { z.teleportAsync(owner.getLocation()); return false; }
        if (d > 6) { z.getPathfinder().moveTo(owner.getLocation(), 1.1); return true; }
        if (d < 2) z.getPathfinder().stopPathfinding();
        return false;
    }

    // ---------- per-maid periodic work, every 0.5 s ----------
    void tick(Llama z) {
        double max = z.getAttribute(Attribute.MAX_HEALTH).getValue();
        long t = z.getTicksLived();
        if (z.getHealth() < max && rnd.nextDouble() < 0.0247) { z.setHealth(Math.min(max, z.getHealth() + 1)); z.getWorld().spawnParticle(Particle.HEART, z.getLocation().add(0, 1.5, 0), 1, 0.2, 0.1, 0.2, 0); }
        boolean open = pl.bagOpen(z);
        if (!open && max - z.getHealth() >= 2 && t % 50 < 10) selfHeal(z);
        if (!open) pl.items.pickup(z, 1.0, 0.5);
        for (Entity en : z.getNearbyEntities(8, 3, 8)) {
            if (!(en instanceof Monster mo)) continue;
            if (mo.getTarget() == null && mo.hasLineOfSight(z)) mo.setTarget(z);
            if (t % 20 < 10 && mo.getLocation().distanceSquared(z.getLocation()) < 6.25) mo.damage(attack(z), z);
        }
        extinguish(z);
    }

    /** eats food from the bag: heal max(total/5,1) with probability total/5 */
    void selfHeal(Llama z) {
        ItemStack[] bag = pl.items.readBag(z);
        for (int i = 0; i < bag.length; i++) {
            ItemStack s = bag[i];
            if (s == null || NOT_FOOD.contains(s.getType().name())) continue;
            FoodProperties f = s.getData(DataComponentTypes.FOOD);
            if (f == null) continue;
            int total = f.nutrition() + (int) f.saturation();
            s.setAmount(s.getAmount() - 1);
            if (s.getAmount() <= 0) bag[i] = null;
            pl.items.writeBag(z, bag);
            if (rnd.nextInt(5) < total) z.setHealth(Math.min(z.getAttribute(Attribute.MAX_HEALTH).getValue(), z.getHealth() + Math.max(total / 5.0, 1)));
            z.getWorld().playSound(z.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.6f, 1f);
            return;
        }
    }

    /** the maid puts out her owner or herself when carrying an extinguisher */
    void extinguish(Llama z) {
        if (!pl.items.hasBauble(z, "extinguisher")) return;
        Player owner = null;
        try { owner = Bukkit.getPlayer(UUID.fromString(z.getPersistentDataContainer().get(pl.kOwner, PersistentDataType.STRING))); } catch (Exception ignored) { }
        Location at = null;
        if (owner != null && owner.getFireTicks() > 0 && owner.getWorld().equals(z.getWorld())) {
            if (owner.getLocation().distanceSquared(z.getLocation()) <= 4) at = owner.getLocation();
            else { z.getPathfinder().moveTo(owner.getLocation(), 1.2); return; }
        } else if (z.getFireTicks() > 0) at = z.getLocation();
        if (at == null) return;
        pl.items.useBauble(z, "extinguisher", 1);
        for (int x = -2; x <= 2; x++) for (int y = -1; y <= 1; y++) for (int zz = -2; zz <= 2; zz++) {
            Block b = at.clone().add(x, y, zz).getBlock();
            if (b.getType() == Material.FIRE) b.setType(Material.AIR);
        }
        for (Entity en : at.getWorld().getNearbyEntities(at, 2, 1, 2)) {
            if (en instanceof LivingEntity le) { le.setFireTicks(0); if (le instanceof Monster && le.isVisualFire()) le.damage(2); }
        }
        at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 30, 1, 0.5, 1, 0.02);
        at.getWorld().playSound(at, Sound.BLOCK_WOOL_PLACE, 1f, 1f);
    }

    // ---------- damage ----------
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Llama z) || !pl.isMaid(z)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.LIGHTNING) {
            e.setCancelled(true);
            if (!z.getPersistentDataContainer().has(kStruck, PersistentDataType.BYTE)) {
                z.getPersistentDataContainer().set(kStruck, PersistentDataType.BYTE, (byte) 1);
                applyLevel(z);
            }
            return;
        }
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
            if (d instanceof Player pp && pp.getUniqueId().toString().equals(z.getPersistentDataContainer().get(pl.kOwner, PersistentDataType.STRING)))
                e.setDamage(Math.max(0, Math.min(2, e.getDamage() / 5)));
        }
        String guard = switch (c) {
            case DROWNING -> "drown_protect_bauble";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "explosion_protect_bauble";
            case FALL -> "fall_protect_bauble";
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> "fire_protect_bauble";
            case MAGIC, POISON, WITHER, DRAGON_BREATH, THORNS -> "magic_protect_bauble";
            case PROJECTILE -> "projectile_protect_bauble";
            default -> null;
        };
        if (guard != null && pl.items.hasBauble(z, guard)) { e.setCancelled(true); return; }
        if (c == EntityDamageEvent.DamageCause.PROJECTILE && pl.items.useBauble(z, "nimble_fabric", 1)) {
            e.setCancelled(true);
            for (int i = 0; i < 16; i++) {
                Location t = z.getLocation().add(rnd.nextInt(13) - 6, rnd.nextInt(5) - 2, rnd.nextInt(13) - 6);
                if (t.getBlock().isPassable() && t.clone().add(0, 1, 0).getBlock().isPassable() && !t.clone().add(0, -1, 0).getBlock().isPassable()) {
                    z.teleportAsync(t.add(0.5, 0, 0.5));
                    z.getWorld().playSound(t, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    break;
                }
            }
            return;
        }
        if (z.getHealth() - e.getFinalDamage() <= 0 && c != EntityDamageEvent.DamageCause.VOID && c != EntityDamageEvent.DamageCause.KILL
            && pl.items.useBauble(z, "ultramarine_orb_elixir", 1)) {
            e.setCancelled(true);
            z.setHealth(z.getAttribute(Attribute.MAX_HEALTH).getValue());
            z.getWorld().spawnParticle(Particle.HEART, z.getLocation().add(0, 1, 0), 10, 0.4, 0.6, 0.4, 0);
            z.getWorld().playSound(z.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 1f);
        }
    }

    // ---------- stored maid items (photo / film) ----------
    static void copy(PersistentDataContainer a, PersistentDataContainer b, EllanMaid pl) {
        String m = a.get(pl.kModel, PersistentDataType.STRING); if (m != null) b.set(pl.kModel, PersistentDataType.STRING, m);
        String o = a.get(pl.kOwner, PersistentDataType.STRING); if (o != null) b.set(pl.kOwner, PersistentDataType.STRING, o);
        Integer f = a.get(pl.kFav, PersistentDataType.INTEGER); if (f != null) b.set(pl.kFav, PersistentDataType.INTEGER, f);
        String g = a.get(pl.kBag, PersistentDataType.STRING); if (g != null) b.set(pl.kBag, PersistentDataType.STRING, g);
        String k = a.get(pl.items.kBack, PersistentDataType.STRING); if (k != null) b.set(pl.items.kBack, PersistentDataType.STRING, k);
        String h = a.get(pl.life.kHome, PersistentDataType.STRING); if (h != null) b.set(pl.life.kHome, PersistentDataType.STRING, h);
        Double hp = a.get(pl.life.kHp, PersistentDataType.DOUBLE); if (hp != null) b.set(pl.life.kHp, PersistentDataType.DOUBLE, hp);
    }

    /** full = photo (everything), otherwise film (no inventory, health or home) */
    ItemStack makeStored(Llama z, String type, boolean full) {
        ItemStack s = pl.ceItem(NS + type);
        if (s == null) return null;
        ItemMeta m = s.getItemMeta();
        var d = m.getPersistentDataContainer();
        copy(z.getPersistentDataContainer(), d, pl);
        if (full) d.set(kHp, PersistentDataType.DOUBLE, z.getHealth());
        else { d.remove(pl.kBag); d.remove(kHome); d.remove(kHp); }
        String key = z.getPersistentDataContainer().get(pl.kModel, PersistentDataType.STRING);
        m.lore(List.of(Component.text(pl.modelNames.getOrDefault(key, "女仆"))));
        s.setItemMeta(m);
        return s;
    }

    void discard(Llama z) {
        var me = ModelEngineAPI.getModeledEntity(z);
        if (me != null) me.destroy();
        z.remove();
    }

    /** brings a maid back from a film (altar, shrine) */
    boolean reviveFromFilm(ItemStack film, Location at, Player p) {
        if (film == null || !film.hasItemMeta()) return false;
        PersistentDataContainer d = film.getItemMeta().getPersistentDataContainer();
        String model = d.get(pl.kModel, PersistentDataType.STRING);
        if (model == null) return false;
        Llama m = pl.spawnMaid(at, p.getUniqueId(), model);
        copy(d, m.getPersistentDataContainer(), pl);
        m.getPersistentDataContainer().set(pl.kOwner, PersistentDataType.STRING, p.getUniqueId().toString());
        applyLevel(m);
        m.setHealth(m.getAttribute(Attribute.MAX_HEALTH).getValue());
        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at.clone().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.2);
        return true;
    }

    // ---------- death and tombstone ----------
    @EventHandler public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Llama z) || !pl.isMaid(z)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Location at = z.getLocation();
        ItemStack film = makeStored(z, "film", false);
        Interaction t = at.getWorld().spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(1.3f);
            i.setPersistent(true);
            var d = i.getPersistentDataContainer();
            copy(z.getPersistentDataContainer(), d, pl);
            d.set(kTomb, PersistentDataType.BYTE, (byte) 1);
            if (film != null) d.set(kFilm, PersistentDataType.STRING, Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(new ItemStack[]{film})));
        });
        attachTomb(t);
        String owner = z.getPersistentDataContainer().get(pl.kOwner, PersistentDataType.STRING);
        try {
            Player op = Bukkit.getPlayer(UUID.fromString(owner));
            if (op != null) op.sendMessage(Component.text("你的女仆倒下了,墓碑在 " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + "。墓碑里有她的物品和一张胶片,拿胶片到祭坛或神龛可以让她回来。"));
        } catch (Exception ignored) { }
    }

    void attachTomb(Interaction t) {
        String key = switch (t.getWorld().getEnvironment()) {
            case NETHER -> "tlm_entity_tombstone_nether";
            case THE_END -> "tlm_entity_tombstone_end";
            default -> "tlm_entity_tombstone";
        };
        ActiveModel am = ModelEngineAPI.createActiveModel(key);
        if (am == null) return;
        ModeledEntity me = ModelEngineAPI.getOrCreateModeledEntity(t);
        me.addModel(am, true);
        me.setBaseEntityVisible(false);
    }

    @EventHandler public void onTombClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Interaction t)) return;
        var pdc = t.getPersistentDataContainer();
        if (!pdc.has(kTomb, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        boolean owner = p.getUniqueId().toString().equals(pdc.get(pl.kOwner, PersistentDataType.STRING));
        boolean ntr = (NS + "owner_conversion_tool").equals(EllanMaid.ceId(p.getInventory().getItemInMainHand()));
        if (!owner && !ntr) { p.sendActionBar(Component.text("这不是你的女仆的墓碑。")); return; }
        List<ItemStack> out = new ArrayList<>();
        String bag = pdc.get(pl.kBag, PersistentDataType.STRING);
        if (bag != null && !bag.isEmpty()) for (ItemStack s : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(bag))) if (s != null) out.add(s);
        String film = pdc.get(kFilm, PersistentDataType.STRING);
        if (film != null) for (ItemStack s : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(film))) if (s != null) out.add(s);
        for (ItemStack s : out) p.getInventory().addItem(s).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        var me = ModelEngineAPI.getModeledEntity(t);
        if (me != null) me.destroy();
        t.remove();
        p.sendActionBar(Component.text("取回了女仆留下的东西。"));
    }

    // ---------- shrine: a film brings the maid back ----------
    @EventHandler public void onShrine(FurnitureInteractEvent e) {
        if (!(NS + "shrine").equals(e.furniture().id().asString())) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!(NS + "film").equals(EllanMaid.ceId(hand))) return;
        e.setCancelled(true);
        if (reviveFromFilm(hand, e.furniture().location().clone().add(0, 1.2, 0), p)) {
            hand.setAmount(hand.getAmount() - 1);
            p.getWorld().playSound(e.furniture().location(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        } else p.sendActionBar(Component.text("这张胶片里没有女仆的记录。"));
    }

    // ---------- statue (from chisel) and garage kit ----------
    void attachStatue(Interaction st) {
        String key = st.getPersistentDataContainer().get(pl.kModel, PersistentDataType.STRING);
        if (key == null) return;
        ActiveModel am = ModelEngineAPI.createActiveModel(key);
        if (am == null) return;
        ModeledEntity me = ModelEngineAPI.getOrCreateModeledEntity(st);
        am.setScale(0.5);
        me.addModel(am, true);
        me.setBaseEntityVisible(false);
        st.getScheduler().runAtFixedRate(pl, task -> {
            if (!st.isValid()) { task.cancel(); return; }
            Block clay = st.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
            if (clay.getType() != Material.CLAY) { dropModels(st); st.remove(); task.cancel(); return; }
            if (clay.getRelative(org.bukkit.block.BlockFace.DOWN).getType() != Material.FIRE) return;
            Location at = clay.getLocation().add(0.5, 0, 0.5);
            at.setYaw(st.getLocation().getYaw());
            clay.setType(Material.AIR);
            BukkitFurniture f = CraftEngineFurniture.place(at, Key.of(NS + "garage_kit"));
            if (f != null) {
                Entity base = f.baseEntity();
                copy(st.getPersistentDataContainer(), base.getPersistentDataContainer(), pl);
                base.getPersistentDataContainer().set(kKit, PersistentDataType.BYTE, (byte) 1);
                base.getScheduler().runDelayed(pl, x -> attachKit(base), null, 10L);
            }
            dropModels(st);
            st.remove();
            at.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f);
            task.cancel();
        }, null, 40L, 40L);
    }

    @EventHandler public void onKitPlace(FurniturePlaceEvent e) {
        String id = e.furniture().id().asString();
        if ((NS + "maid_beacon").equals(id)) {
            Entity base = e.furniture().baseEntity();
            base.getPersistentDataContainer().set(kBeacon, PersistentDataType.BYTE, (byte) 1);
            startBeacon(base);
            return;
        }
        if (!(NS + "garage_kit").equals(id)) return;
        ItemStack hand = e.player().getInventory().getItemInMainHand();
        if (!hand.hasItemMeta()) return;
        PersistentDataContainer d = hand.getItemMeta().getPersistentDataContainer();
        Entity base = e.furniture().baseEntity();
        if (d.has(pl.kModel, PersistentDataType.STRING)) copy(d, base.getPersistentDataContainer(), pl);
        else if (!pl.modelKeys.isEmpty()) // a blank kit from the shop is a blind box: random maid
            base.getPersistentDataContainer().set(pl.kModel, PersistentDataType.STRING, pl.modelKeys.get(rnd.nextInt(pl.modelKeys.size())));
        else return;
        base.getPersistentDataContainer().set(kKit, PersistentDataType.BYTE, (byte) 1);
        base.getScheduler().runDelayed(pl, t -> attachKit(base), null, 10L);
    }

    void attachKit(Entity base) {
        String key = base.getPersistentDataContainer().get(pl.kModel, PersistentDataType.STRING);
        if (key == null) return;
        ActiveModel am = ModelEngineAPI.createActiveModel(key);
        if (am == null) return;
        ModeledEntity me = ModelEngineAPI.getOrCreateModeledEntity(base);
        am.setScale(pl.getConfig().getDouble("kit-scale", 0.8));
        me.addModel(am, false);
        if (am.getBlueprint().getAnimations().containsKey("idle")) am.getAnimationHandler().playAnimation("idle", 0.2, 0.2, 1, true);
    }

    void dropModels(Entity base) {
        ModeledEntity me = ModelEngineAPI.getModeledEntity(base);
        if (me != null) {
            new ArrayList<>(me.getModels().keySet()).forEach(me::removeModel);
            me.destroy();
        }
        ModelEngineAPI.removeModeledEntity(base);
    }

    @EventHandler public void onKitClick(FurnitureInteractEvent e) {
        if (!(NS + "garage_kit").equals(e.furniture().id().asString())) return;
        Entity base = e.furniture().baseEntity();
        if (!base.getPersistentDataContainer().has(kKit, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("这是一座手办,只能摆着看。"));
    }

    @EventHandler public void onKitBreak(FurnitureBreakEvent e) {
        if (!(NS + "garage_kit").equals(e.furniture().id().asString())) return;
        Entity base = e.furniture().baseEntity();
        var pdc = base.getPersistentDataContainer();
        if (!pdc.has(kKit, PersistentDataType.BYTE)) return;
        e.setDropItems(false);
        dropModels(base);
        ItemStack kit = pl.ceItem(NS + "garage_kit");
        if (kit == null) return;
        ItemMeta m = kit.getItemMeta();
        copy(pdc, m.getPersistentDataContainer(), pl);
        String key = pdc.get(pl.kModel, PersistentDataType.STRING);
        m.lore(List.of(Component.text(pl.modelNames.getOrDefault(key, "女仆"))));
        kit.setItemMeta(m);
        base.getWorld().dropItemNaturally(base.getLocation().add(0, 0.3, 0), kit);
    }

    // ---------- maid beacon ----------
    static final PotionEffectType[] BEACON = {PotionEffectType.SPEED, PotionEffectType.FIRE_RESISTANCE, PotionEffectType.STRENGTH, PotionEffectType.RESISTANCE, PotionEffectType.REGENERATION};
    static final String[] BEACON_NAME = {"速度", "抗火", "力量", "抗性", "生命恢复"};

    void startBeacon(Entity base) {
        base.getScheduler().runAtFixedRate(pl, task -> {
            if (!base.isValid()) { task.cancel(); return; }
            var d = base.getPersistentDataContainer();
            int idx = d.getOrDefault(kBeaconPotion, PersistentDataType.INTEGER, -1);
            double store = d.getOrDefault(kBeaconPower, PersistentDataType.DOUBLE, 0.0);
            double cost = pl.getConfig().getDouble("beacon-cost-per-hour", 0.9) / 900;
            if (idx < 0 || store < cost) return;
            d.set(kBeaconPower, PersistentDataType.DOUBLE, store - cost);
            for (Entity en : base.getNearbyEntities(8, 8, 8))
                if (en instanceof Llama m && pl.isMaid(m)) m.addPotionEffect(new PotionEffect(BEACON[idx], 100, 1, true, true));
        }, null, 80L, 80L);
    }

    @EventHandler public void onBeaconClick(FurnitureInteractEvent e) {
        if (!(NS + "maid_beacon").equals(e.furniture().id().asString())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        var d = e.furniture().baseEntity().getPersistentDataContainer();
        double store = d.getOrDefault(kBeaconPower, PersistentDataType.DOUBLE, 0.0);
        if (p.isSneaking()) {
            double move = Math.min(Math.min(0.5, pl.items.power(p)), 100 - store);
            if (move <= 0) { p.sendActionBar(Component.text("你没有力量可以存入。")); return; }
            pl.items.setPower(p, pl.items.power(p) - move);
            d.set(kBeaconPower, PersistentDataType.DOUBLE, store + move);
            p.sendActionBar(Component.text(String.format("已存入 %.2f,神灯里有 %.2f 的力量。", move, store + move)));
        } else {
            int idx = d.getOrDefault(kBeaconPotion, PersistentDataType.INTEGER, -1) + 1;
            if (idx >= BEACON.length) idx = -1;
            d.set(kBeaconPotion, PersistentDataType.INTEGER, idx);
            p.sendActionBar(Component.text(idx < 0 ? "效果:无" : "效果:" + BEACON_NAME[idx] + "(潜行右击存入力量)"));
        }
    }

    @EventHandler public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            var pdc = en.getPersistentDataContainer();
            if (en instanceof Interaction t && pdc.has(kTomb, PersistentDataType.BYTE)) t.getScheduler().runDelayed(pl, x -> attachTomb(t), null, 40L);
            else if (en instanceof Interaction t && pdc.has(pl.items.kStatue, PersistentDataType.BYTE)) t.getScheduler().runDelayed(pl, x -> attachStatue(t), null, 40L);
            else if (pdc.has(kKit, PersistentDataType.BYTE)) en.getScheduler().runDelayed(pl, x -> attachKit(en), null, 60L);
            else if (pdc.has(kBeacon, PersistentDataType.BYTE)) startBeacon(en);
        }
    }
}
