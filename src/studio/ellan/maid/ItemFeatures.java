package studio.ellan.maid;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/** Functions of the maid items, following the original mod's rules. */
final class ItemFeatures implements Listener {
    static final String NS = "touhou_little_maid:";
    final EllanMaid pl;
    final NamespacedKey kPower, kBack, kSlabModel, kSlabFav, kSlabBag, kSlabBack, kBell, kIoPos, kIoMode, kCompass, kStatue;

    ItemFeatures(EllanMaid pl) {
        this.pl = pl;
        kPower = new NamespacedKey(pl, "power");
        kBack = new NamespacedKey(pl, "backpack");
        kSlabModel = new NamespacedKey(pl, "slab_model");
        kSlabFav = new NamespacedKey(pl, "slab_fav");
        kSlabBag = new NamespacedKey(pl, "slab_bag");
        kSlabBack = new NamespacedKey(pl, "slab_back");
        kBell = new NamespacedKey(pl, "bell");
        kIoPos = new NamespacedKey(pl, "io_pos");
        kIoMode = new NamespacedKey(pl, "io_mode");
        kCompass = new NamespacedKey(pl, "compass");
        kStatue = new NamespacedKey(pl, "statue");
    }

    // ---------- power (player float, max 5.0, 1.0 = 100 P) ----------
    static final double POWER_MAX = 5.0;
    double power(Player p) { return p.getPersistentDataContainer().getOrDefault(kPower, PersistentDataType.DOUBLE, 0.0); }
    void setPower(Player p, double v) { p.getPersistentDataContainer().set(kPower, PersistentDataType.DOUBLE, Math.max(0, Math.min(POWER_MAX, v))); }

    /** pickup rule: overflow over the cap becomes XP at 4 P per point */
    void addPower(Player p, int points) {
        double cur = power(p), add = points / 100.0;
        if (cur + add > POWER_MAX) {
            int residual = points - (int) (POWER_MAX * 100) + (int) (cur * 100);
            setPower(p, POWER_MAX);
            if (residual > 0) p.giveExp(residual / 4);
        } else setPower(p, cur + add);
    }

    @EventHandler public void onKill(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Monster) || e.getEntity().getKiller() == null) return;
        if (Math.random() < pl.getConfig().getDouble("power-drop-chance", 0.05)) {
            ItemStack it = pl.ceItem(NS + "power_point");
            if (it != null) e.getDrops().add(it);
        }
    }

    @EventHandler public void onPlayerDeath(PlayerDeathEvent e) {
        if (Boolean.TRUE.equals(e.getEntity().getWorld().getGameRuleValue(GameRule.KEEP_INVENTORY))) return;
        Player p = e.getEntity();
        setPower(p, power(p) - pl.getConfig().getDouble("power-death-loss", 1.0));
    }

    // ---------- item use in the air / on blocks ----------
    @EventHandler public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = EllanMaid.ceId(it);
        if (id == null || !id.startsWith(NS)) return;
        Player p = e.getPlayer();
        String name = id.substring(NS.length());
        switch (name) {
            case "power_point" -> {
                e.setCancelled(true);
                int total = 30 + new Random().nextInt(30) + new Random().nextInt(30);
                addPower(p, total);
                if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_BOTTLE_THROW, 0.5f, 1.2f);
                p.sendActionBar(Component.text(String.format("力量 +%d P,共 %.2f / %.1f", total, power(p), POWER_MAX)));
            }
            case "trumpet" -> {
                e.setCancelled(true);
                if (p.hasCooldown(it)) return;
                p.setCooldown(it, 200);
                int n = 0;
                for (Llama m : ownedMaids(p)) { pl.life.setHome(m, false); m.teleportAsync(scatter(p.getLocation())); n++; }
                p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                p.sendActionBar(Component.text(n == 0 ? "附近没有你的女仆。" : "号声响起," + n + " 位女仆赶到你身边。"));
            }
            case "servant_bell" -> {
                e.setCancelled(true);
                if (p.hasCooldown(it)) return;
                p.setCooldown(it, 20);
                String uuid = it.getItemMeta().getPersistentDataContainer().get(kBell, PersistentDataType.STRING);
                if (uuid == null) { p.sendActionBar(Component.text("铃铛还没有绑定女仆。对女仆右击来绑定。")); return; }
                Entity m = Bukkit.getEntity(UUID.fromString(uuid));
                if (m instanceof Llama l && pl.isMaid(l)) {
                    pl.life.setHome(l, false);
                    l.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 1, true, false));
                    l.teleportAsync(scatter(p.getLocation()));
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 2f, 1f);
                } else p.sendActionBar(Component.text("找不到这位女仆,她可能不在已加载的区域里。"));
            }
            case "kappa_compass" -> {
                e.setCancelled(true);
                if (a != Action.RIGHT_CLICK_BLOCK) return;
                compassBlock(p, it, e.getClickedBlock());
            }
            case "red_fox_scroll" -> {
                e.setCancelled(true);
                p.sendMessage(Component.text("—— 你的女仆 ——"));
                int n = 0;
                for (World w : Bukkit.getWorlds()) for (Llama m : w.getEntitiesByClass(Llama.class))
                    if (pl.isMaid(m) && owns(p, m)) { n++; where(p, m.customName() == null ? "女仆" : plain(m), m.getLocation()); }
                if (n == 0) p.sendMessage(Component.text("没有已加载的女仆。"));
            }
            case "white_fox_scroll" -> {
                e.setCancelled(true);
                p.sendMessage(Component.text("—— 你的女仆的墓碑 ——"));
                int n = 0;
                for (World w : Bukkit.getWorlds()) for (Interaction t : w.getEntitiesByClass(Interaction.class))
                    if (t.getPersistentDataContainer().has(pl.life.kTomb, PersistentDataType.BYTE) && owns(p, t)) { n++; where(p, "墓碑", t.getLocation()); }
                if (n == 0) p.sendMessage(Component.text("没有已加载的墓碑。"));
            }
            case "extinguisher" -> { e.setCancelled(true); p.sendActionBar(Component.text("你不知道该怎么使用这个。")); }
            case "wireless_io" -> {
                e.setCancelled(true);
                ItemMeta m = it.getItemMeta();
                var d = m.getPersistentDataContainer();
                if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock().getState() instanceof org.bukkit.inventory.InventoryHolder) {
                    Block b = e.getClickedBlock();
                    d.set(kIoPos, PersistentDataType.STRING, b.getWorld().getName() + "|" + b.getX() + "|" + b.getY() + "|" + b.getZ());
                    it.setItemMeta(m);
                    p.sendActionBar(Component.text("已绑定这个箱子。"));
                } else {
                    boolean toChest = d.getOrDefault(kIoMode, PersistentDataType.BYTE, (byte) 0) == 0;
                    d.set(kIoMode, PersistentDataType.BYTE, (byte) (toChest ? 1 : 0));
                    it.setItemMeta(m);
                    p.sendActionBar(Component.text(toChest ? "模式:箱子 → 女仆" : "模式:女仆 → 箱子"));
                }
            }
            case "chisel" -> {
                if (a != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock().getType() != Material.CLAY) return;
                e.setCancelled(true);
                chisel(p, it, e.getClickedBlock());
            }
            case "photo" -> {
                if (a != Action.RIGHT_CLICK_BLOCK) return;
                e.setCancelled(true);
                if (e.getBlockFace() != org.bukkit.block.BlockFace.UP) { p.sendActionBar(Component.text("这里不适合放置女仆。")); return; }
                var d = it.getItemMeta().getPersistentDataContainer();
                String model = d.get(pl.kModel, PersistentDataType.STRING);
                if (model == null) { p.sendActionBar(Component.text("这张照片里没有女仆。")); return; }
                if (!p.getUniqueId().toString().equals(d.get(pl.kOwner, PersistentDataType.STRING))) { p.sendActionBar(Component.text("这不是你的女仆。")); return; }
                Location at = e.getClickedBlock().getRelative(org.bukkit.block.BlockFace.UP).getLocation().add(0.5, 0, 0.5);
                Llama m = pl.spawnMaid(at, p.getUniqueId(), model);
                MaidLife.copy(d, m.getPersistentDataContainer(), pl);
                pl.life.applyLevel(m);
                Double hp = d.get(pl.life.kHp, PersistentDataType.DOUBLE);
                if (hp != null) m.setHealth(Math.max(1, Math.min(hp, m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())));
                p.getWorld().spawnParticle(Particle.EXPLOSION, at, 1);
                p.getWorld().playSound(at, Sound.ENTITY_PLAYER_SPLASH, 1f, 1f);
                if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
            }
            case "smart_slab_has_maid" -> {
                if (a != Action.RIGHT_CLICK_BLOCK) return;
                e.setCancelled(true);
                releaseSlab(p, it, e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5));
            }
            default -> { }
        }
    }

    static Location scatter(Location base) {
        Random r = new Random();
        return base.clone().add(r.nextInt(3) - 1, 0, r.nextInt(3) - 1);
    }

    String plain(Entity e) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.customName()); }

    void where(Player p, String label, Location l) {
        String xyz = l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        p.sendMessage(Component.text(" " + label + "  " + l.getWorld().getName() + " " + xyz + "  [点击复制坐标]").clickEvent(ClickEvent.copyToClipboard(xyz)));
    }

    boolean owns(Player p, Entity e) { return p.getUniqueId().toString().equals(e.getPersistentDataContainer().get(pl.kOwner, PersistentDataType.STRING)); }

    List<Llama> ownedMaids(Player p) {
        List<Llama> out = new ArrayList<>();
        for (Llama m : p.getWorld().getEntitiesByClass(Llama.class)) if (pl.isMaid(m) && owns(p, m)) out.add(m);
        return out;
    }

    // ---------- kappa compass: work -> idle -> sleep, each within 64 blocks of the previous ----------
    String[] compassData(ItemStack it) {
        String s = it.getItemMeta().getPersistentDataContainer().get(kCompass, PersistentDataType.STRING);
        return s == null || s.isEmpty() ? new String[0] : s.split(";");
    }

    void compassBlock(Player p, ItemStack it, Block b) {
        ItemMeta m = it.getItemMeta();
        var d = m.getPersistentDataContainer();
        if (p.isSneaking()) { d.remove(kCompass); it.setItemMeta(m); p.sendActionBar(Component.text("已清除记录。")); return; }
        String[] pts = compassData(it);
        if (pts.length >= 3) { p.sendActionBar(Component.text("三个位置都记录好了。潜行右击方块可以清除。")); return; }
        String cur = b.getWorld().getName() + "|" + b.getX() + "|" + (b.getY() + 1) + "|" + b.getZ();
        if (pts.length > 0) {
            String[] prev = pts[pts.length - 1].split("\\|");
            if (!prev[0].equals(b.getWorld().getName())) { p.sendActionBar(Component.text("不能跨维度记录。")); return; }
            double dx = Double.parseDouble(prev[1]) - b.getX(), dy = Double.parseDouble(prev[2]) - b.getY(), dz = Double.parseDouble(prev[3]) - b.getZ();
            if (dx * dx + dy * dy + dz * dz > 64 * 64) { p.sendActionBar(Component.text("距离上一个位置太远了。")); return; }
        }
        String next = pts.length == 0 ? cur : String.join(";", pts) + ";" + cur;
        d.set(kCompass, PersistentDataType.STRING, next);
        it.setItemMeta(m);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 0.8f, 1.5f);
        p.sendActionBar(Component.text(new String[]{"已记录工作位置。", "已记录休闲位置。", "已记录睡觉位置。"}[pts.length]));
    }

    // ---------- chisel: photo (off hand) + clay -> tiny statue; fire below turns it into a garage kit ----------
    void chisel(Player p, ItemStack chisel, Block clay) {
        ItemStack photo = p.getInventory().getItemInOffHand();
        if (!(NS + "photo").equals(EllanMaid.ceId(photo))) { p.sendActionBar(Component.text("请把照片拿在副手。")); return; }
        var d = photo.getItemMeta().getPersistentDataContainer();
        if (!d.has(pl.kModel, PersistentDataType.STRING)) { p.sendActionBar(Component.text("这张照片里没有女仆。")); return; }
        Location top = clay.getLocation().add(0.5, 1, 0.5);
        Interaction st = clay.getWorld().spawn(top, Interaction.class, i -> {
            i.setInteractionWidth(0.5f); i.setInteractionHeight(0.8f); i.setPersistent(true);
            MaidLife.copy(d, i.getPersistentDataContainer(), pl);
            i.getPersistentDataContainer().set(kStatue, PersistentDataType.BYTE, (byte) 1);
        });
        pl.life.attachStatue(st);
        damage(chisel, 1);
        p.getWorld().playSound(clay.getLocation(), Sound.BLOCK_ANVIL_USE, 0.5f, 1.5f);
        p.sendActionBar(Component.text("雕出了一座小雕像。在黏土块下面点一把火,就能烧成手办。"));
    }

    /** durability by 'amount'; returns false when the item broke */
    static boolean damage(ItemStack s, int amount) {
        ItemMeta m = s.getItemMeta();
        if (m instanceof Damageable dm) {
            int max = dm.hasMaxDamage() ? dm.getMaxDamage() : 0;
            dm.setDamage(dm.getDamage() + amount);
            if (max > 0 && dm.getDamage() >= max) { s.setAmount(0); return false; }
            s.setItemMeta(dm);
        }
        return true;
    }

    // ---------- gohei: close-range weapon, +4 damage, -2 durability per hit ----------
    @EventHandler public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = EllanMaid.ceId(hand);
        if (id == null || !(id.equals(NS + "hakurei_gohei") || id.equals(NS + "sanae_gohei"))) return;
        e.setDamage(e.getDamage() + 4);
        if (hand.getItemMeta() instanceof Damageable dm) {
            int max = dm.hasMaxDamage() ? dm.getMaxDamage() : 1200;
            dm.setDamage(dm.getDamage() + 2);
            if (dm.getDamage() >= max) hand.setAmount(0); else hand.setItemMeta(dm);
        }
    }

    // ---------- items used on a non-owned maid ----------
    boolean onForeignMaid(Player p, Llama z, ItemStack hand) {
        if (!(NS + "owner_conversion_tool").equals(EllanMaid.ceId(hand))) return false;
        z.getPersistentDataContainer().set(pl.kOwner, PersistentDataType.STRING, p.getUniqueId().toString());
        pl.life.setHome(z, false);
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        p.getWorld().playSound(z.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        p.sendActionBar(Component.text("这位女仆现在是你的了。"));
        return true;
    }

    // ---------- items used on an owned maid; true = handled, no GUI ----------
    boolean onMaid(Player p, Llama z, ItemStack hand) {
        String id = EllanMaid.ceId(hand);
        if (id == null || !id.startsWith(NS)) return false;
        var pdc = z.getPersistentDataContainer();
        switch (id.substring(NS.length())) {
            case "favorability_tool_add" -> { pl.life.addFav(z, p.isSneaking() ? 1 : 64); toolSound(p); p.sendActionBar(Component.text("好感度 " + pl.life.fav(z))); return true; }
            case "favorability_tool_reduce" -> { pl.life.addFav(z, p.isSneaking() ? -1 : -64); toolSound(p); p.sendActionBar(Component.text("好感度 " + pl.life.fav(z))); return true; }
            case "favorability_tool_full" -> { pl.life.addFav(z, 384); toolSound(p); p.sendActionBar(Component.text("好感度已满。")); return true; }
            case "maid_backpack_small", "maid_backpack_middle", "maid_backpack_big" -> {
                String kind = id.substring((NS + "maid_backpack_").length());
                if (rank(kind) <= rank(pdc.getOrDefault(kBack, PersistentDataType.STRING, ""))) { p.sendActionBar(Component.text("她已经背着更大的背包了。")); return true; }
                pdc.set(kBack, PersistentDataType.STRING, kind);
                if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
                p.sendActionBar(Component.text("背包容量变成了 " + bagSize(z) + " 格。")); return true;
            }
            case "camera" -> {
                if (p.hasCooldown(hand)) return true;
                p.setCooldown(hand, 20);
                ItemStack photo = pl.life.makeStored(z, "photo", true);
                if (photo == null) return true;
                damage(hand, 1);
                pl.life.discard(z);
                p.getWorld().dropItem(p.getLocation(), photo);
                p.getWorld().spawnParticle(Particle.EXPLOSION, p.getEyeLocation().add(p.getLocation().getDirection()), 1);
                p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
                return true;
            }
            case "substitute_jizo" -> {
                if (z.isInvulnerable()) { p.sendActionBar(Component.text("她已经是无敌的了。")); return true; }
                z.setInvulnerable(true);
                hand.setAmount(hand.getAmount() - 1);
                z.getWorld().spawnParticle(Particle.PORTAL, z.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.3);
                p.sendActionBar(Component.text("替身地藏守护着她。")); return true;
            }
            case "kappa_compass" -> {
                if (p.isSneaking()) { pl.life.clearHome(z); p.sendActionBar(Component.text("已清除女仆的位置记录。")); return true; }
                String[] pts = compassData(hand);
                if (pts.length == 0) { p.sendActionBar(Component.text("罗盘里还没有记录位置。")); return true; }
                if (!pts[0].startsWith(z.getWorld().getName() + "|")) { p.sendActionBar(Component.text("罗盘记录的是另一个世界。")); return true; }
                pl.life.writeHome(z, pts);
                p.sendActionBar(Component.text("女仆记住了这些位置,会待在家里。"));
                return true;
            }
            case "servant_bell" -> {
                ItemMeta m = hand.getItemMeta();
                m.getPersistentDataContainer().set(kBell, PersistentDataType.STRING, z.getUniqueId().toString());
                m.displayName(Component.text(z.customName() == null ? "仆人铃" : plain(z)));
                hand.setItemMeta(m);
                p.sendActionBar(Component.text("铃铛绑定了这位女仆。"));
                return true;
            }
            case "smart_slab_empty", "smart_slab_init" -> {
                ItemStack slab = pl.ceItem(NS + "smart_slab_has_maid");
                if (slab == null) return true;
                ItemMeta m = slab.getItemMeta();
                PersistentDataContainer d = m.getPersistentDataContainer();
                d.set(kSlabModel, PersistentDataType.STRING, pdc.getOrDefault(pl.kModel, PersistentDataType.STRING, ""));
                d.set(kSlabFav, PersistentDataType.INTEGER, pdc.getOrDefault(pl.kFav, PersistentDataType.INTEGER, 0));
                d.set(kSlabBag, PersistentDataType.STRING, pdc.getOrDefault(pl.kBag, PersistentDataType.STRING, ""));
                d.set(kSlabBack, PersistentDataType.STRING, pdc.getOrDefault(kBack, PersistentDataType.STRING, ""));
                slab.setItemMeta(m);
                pl.life.discard(z);
                hand.setAmount(hand.getAmount() - 1);
                p.getInventory().addItem(slab).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
                p.sendActionBar(Component.text("女仆被收进了智能石板。"));
                return true;
            }
            default -> { return false; }
        }
    }

    void toolSound(Player p) { p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 0.95f); }

    @EventHandler public void onEntityId(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(NS + "entity_id_copy").equals(EllanMaid.ceId(e.getPlayer().getInventory().getItemInMainHand()))) return;
        e.setCancelled(true);
        String key = e.getRightClicked().getType().getKey().toString();
        e.getPlayer().sendMessage(Component.text("实体 ID:" + key + "  [点击复制]").clickEvent(ClickEvent.copyToClipboard(key)));
    }

    void releaseSlab(Player p, ItemStack hand, Location at) {
        PersistentDataContainer d = hand.getItemMeta().getPersistentDataContainer();
        String model = d.get(kSlabModel, PersistentDataType.STRING);
        if (model == null || model.isEmpty()) { p.sendActionBar(Component.text("石板里没有女仆。")); return; }
        Llama m = pl.spawnMaid(at, p.getUniqueId(), model);
        var pdc = m.getPersistentDataContainer();
        pdc.set(pl.kFav, PersistentDataType.INTEGER, d.getOrDefault(kSlabFav, PersistentDataType.INTEGER, 0));
        String bag = d.get(kSlabBag, PersistentDataType.STRING);
        if (bag != null && !bag.isEmpty()) pdc.set(pl.kBag, PersistentDataType.STRING, bag);
        String back = d.get(kSlabBack, PersistentDataType.STRING);
        if (back != null && !back.isEmpty()) pdc.set(kBack, PersistentDataType.STRING, back);
        pl.life.applyLevel(m);
        ItemStack empty = pl.ceItem(NS + "smart_slab_empty");
        hand.setAmount(hand.getAmount() - 1);
        if (empty != null) p.getInventory().addItem(empty).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        p.sendActionBar(Component.text("女仆从石板里出来了。"));
    }

    static int rank(String k) { return switch (k) { case "small" -> 1; case "middle" -> 2; case "big" -> 3; default -> 0; }; }

    int bagSize(Llama z) {
        return switch (z.getPersistentDataContainer().getOrDefault(kBack, PersistentDataType.STRING, "")) {
            case "small" -> 18; case "middle" -> 27; case "big" -> 36; default -> 9;
        };
    }

    // ---------- bag helpers ----------
    ItemStack[] readBag(Llama z) {
        String b64 = z.getPersistentDataContainer().get(pl.kBag, PersistentDataType.STRING);
        ItemStack[] stored = b64 == null || b64.isEmpty() ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b64));
        ItemStack[] out = new ItemStack[Math.max(bagSize(z), stored.length)];
        System.arraycopy(stored, 0, out, 0, stored.length);
        return out;
    }

    void writeBag(Llama z, ItemStack[] bag) {
        z.getPersistentDataContainer().set(pl.kBag, PersistentDataType.STRING, Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(bag)));
    }

    /** index of a bauble in the bag or -1 */
    int findBauble(ItemStack[] bag, String name) {
        for (int i = 0; i < bag.length; i++) if (bag[i] != null && (NS + name).equals(EllanMaid.ceId(bag[i]))) return i;
        return -1;
    }

    boolean hasBauble(Llama z, String name) { return findBauble(readBag(z), name) >= 0; }

    /** one durability off a bauble; false when none is carried */
    boolean useBauble(Llama z, String name, int amount) {
        ItemStack[] bag = readBag(z);
        int i = findBauble(bag, name);
        if (i < 0) return false;
        damage(bag[i], amount);
        if (bag[i].getAmount() <= 0) bag[i] = null;
        writeBag(z, bag);
        return true;
    }

    // ---------- per-maid periodic work (called every 0.5 s) ----------
    void tick(Llama z) {
        long t = z.getTicksLived();
        ItemStack[] bag = readBag(z);
        // magnet: every 60 ticks, 6 blocks
        if (t % 60 < 10 && findBauble(bag, "item_magnet_bauble") >= 0) pickup(z, 6, 3);
        // wireless io: every 100 ticks
        int io = findBauble(bag, "wireless_io");
        if (io >= 0 && t % 100 < 10) wirelessIo(z, bag, bag[io]);
    }

    /** collect dropped items around the maid into her bag */
    void pickup(Llama z, double r, double ry) {
        ItemStack[] bag = null;
        for (Entity en : z.getNearbyEntities(r, ry, r)) {
            if (!(en instanceof Item item) || item.getPickupDelay() > 0) continue;
            if (bag == null) bag = readBag(z);
            Inventory tmp = Bukkit.createInventory(null, Math.max(9, ((bag.length + 8) / 9) * 9));
            for (int i = 0; i < bag.length; i++) tmp.setItem(i, bag[i]);
            var left = tmp.addItem(item.getItemStack());
            for (int i = 0; i < bag.length; i++) bag[i] = tmp.getItem(i);
            if (left.isEmpty()) item.remove(); else item.setItemStack(left.get(0));
            writeBag(z, bag);
        }
    }

    void wirelessIo(Llama z, ItemStack[] bag, ItemStack io) {
        var d = io.getItemMeta().getPersistentDataContainer();
        String pos = d.get(kIoPos, PersistentDataType.STRING);
        if (pos == null) return;
        String[] s = pos.split("\\|");
        World w = Bukkit.getWorld(s[0]);
        if (w == null || !w.equals(z.getWorld())) return;
        Location l = new Location(w, Integer.parseInt(s[1]), Integer.parseInt(s[2]), Integer.parseInt(s[3]));
        if (l.distanceSquared(z.getLocation()) > 12 * 12) return;
        if (!(l.getBlock().getState() instanceof org.bukkit.inventory.InventoryHolder h)) return;
        Inventory chest = h.getInventory();
        if (!chest.getViewers().isEmpty()) return;
        boolean toMaid = d.getOrDefault(kIoMode, PersistentDataType.BYTE, (byte) 0) == 1;
        Inventory tmp = Bukkit.createInventory(null, Math.max(9, ((bag.length + 8) / 9) * 9));
        for (int i = 0; i < bag.length; i++) tmp.setItem(i, bag[i]);
        int ioSlot = -1;
        for (int i = 0; i < bag.length; i++) if (bag[i] == io) ioSlot = i;
        if (toMaid) {
            for (int i = 0; i < chest.getSize(); i++) {
                ItemStack c = chest.getItem(i);
                if (c == null || c.getType().isAir()) continue;
                var left = tmp.addItem(c);
                chest.setItem(i, left.isEmpty() ? null : left.get(0));
            }
        } else {
            for (int i = 0; i < bag.length; i++) {
                if (i == ioSlot || bag[i] == null || bag[i].getType().isAir()) continue;
                var left = chest.addItem(bag[i]);
                tmp.setItem(i, left.isEmpty() ? null : left.get(0));
            }
        }
        for (int i = 0; i < bag.length; i++) bag[i] = tmp.getItem(i);
        writeBag(z, bag);
    }
}
