package studio.ellan.maid;

import com.google.gson.*;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Maid companions: model from the runtime manifests, follow/sit/backpack, altar crafting. */
public final class EllanMaid extends JavaPlugin implements Listener {
    static final String EGG = "touhou_little_maid:maid_spawn_egg";
    static final String NS_ = "touhou_little_maid:";
    static final String ALTAR = "touhou_little_maid:altar";
    static final String CHANGER = "touhou_little_maid:change_maid_model";

    NamespacedKey kOwner, kModel, kSit, kBag;
    final List<String> modelKeys = new ArrayList<>();
    final Map<String, String> modelNames = new HashMap<>();
    final List<JsonObject> altar = new ArrayList<>();
    final Map<Inventory, UUID> bags = new HashMap<>();
    final Map<String, JsonObject> chairs = new HashMap<>();
    final Set<String> beds = new HashSet<>();
    final Map<UUID, ArmorStand> seats = new HashMap<>();
    NamespacedKey kSeat, kSnacks, kFav;
    Boards boards;
    ItemFeatures items;
    MaidLife life;
    Work work;
    Fishing fishing;
    Travel travel;
    Wardrobe wardrobe;
    ModelSwitcher switcher;
    PowerHook power;
    final Map<UUID, Integer> gen = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        kOwner = new NamespacedKey(this, "owner");
        kModel = new NamespacedKey(this, "model");
        kSit = new NamespacedKey(this, "sit");
        kBag = new NamespacedKey(this, "bag");
        kSeat = new NamespacedKey(this, "seat");
        kSnacks = new NamespacedKey(this, "snacks");
        kFav = new NamespacedKey(this, "fav");
        boards = new Boards(this);
        items = new ItemFeatures(this);
        getServer().getPluginManager().registerEvents(items, this);
        life = new MaidLife(this);
        getServer().getPluginManager().registerEvents(life, this);
        getServer().getPluginManager().registerEvents(this, this);
        var pm = getServer().getPluginManager();
        work = new Work(this);
        fishing = new Fishing(this);
        pm.registerEvents(fishing, this);
        travel = new Travel(this);
        pm.registerEvents(travel, this);
        var guide = getCommand("maid");
        if (guide != null) guide.setExecutor(new Guide(this));
        switcher = new ModelSwitcher(this);
        pm.registerEvents(switcher, this);
        wardrobe = new Wardrobe(this);
        pm.registerEvents(wardrobe, this);
        if (pm.getPlugin("CustomFishing") != null) try { pm.registerEvents(new CustomFishingHook(fishing), this); } catch (Throwable t) { getLogger().warning("CustomFishing hook off: " + t); }
        if (pm.getPlugin("MythicMobs") != null) try { power = new PowerHook(this); pm.registerEvents(power, this); } catch (Throwable t) { power = null; getLogger().warning("MythicMobs hook off: " + t); }
        if (pm.getPlugin("SparrowSync") != null) try { pm.registerEvents(new SyncHook(travel), this); } catch (Throwable t) { getLogger().warning("SparrowSync hook off: " + t); }
        // ModelEngine imports blueprints asynchronously after enable, so read them a bit later
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            loadRuntime();
            for (World w : Bukkit.getWorlds())
                for (Chunk c : w.getLoadedChunks())
                    for (Entity e : c.getEntities()) tryAttach(e);
        }, 200L);
    }

    void loadRuntime() {
        modelKeys.clear(); modelNames.clear(); altar.clear();
        Path dir = Path.of(getConfig().getString("runtime-dir", "plugins/XiaoMengMaidLib/runtime"));
        try {
            JsonArray a = JsonParser.parseString(Files.readString(dir.resolve("tlm_models.json"), StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : a) {
                JsonObject o = el.getAsJsonObject();
                if (!"maid".equals(o.get("kind").getAsString())) continue;
                String key = o.get("key").getAsString();
                if (ModelEngineAPI.getBlueprint(key) == null) continue;
                modelKeys.add(key);
                modelNames.put(key, o.has("display_name") ? o.get("display_name").getAsString() : key);
            }
            chairs.clear(); beds.clear();
            JsonObject cj = JsonParser.parseString(Files.readString(dir.resolve("chair_models.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement c : cj.getAsJsonArray("chairs")) chairs.put(c.getAsJsonObject().get("id").getAsString(), c.getAsJsonObject());
            JsonObject bj = JsonParser.parseString(Files.readString(dir.resolve("bed_models.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement b : bj.getAsJsonArray("beds")) beds.add("touhou_little_maid:" + b.getAsJsonObject().get("id").getAsString());
            JsonArray r = JsonParser.parseString(Files.readString(dir.resolve("altar_recipes.json"), StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : r) altar.add(el.getAsJsonObject());
        } catch (Exception ex) {
            getLogger().warning("runtime manifests not readable in " + dir + ": " + ex);
        }
        boards.load(dir);
        work.cooking.load();
        getLogger().info("maid models with blueprint: " + modelKeys.size() + ", altar recipes: " + altar.size() + ", chairs: " + chairs.size() + ", beds: " + beds.size());
    }

    // ---------- items ----------
    static String ceId(ItemStack s) {
        if (s == null || s.getType().isAir()) return null;
        try { Key k = CraftEngineItems.getCustomItemId(s); return k == null ? null : k.asString(); } catch (Throwable t) { return null; }
    }
    ItemStack ceItem(String id) {
        var d = CraftEngineItems.byId(id);
        return d == null ? null : d.buildBukkitItem();
    }

    // ---------- maid lifecycle ----------
    boolean isMaid(Entity e) { return e instanceof Llama && e.getPersistentDataContainer().has(kModel, PersistentDataType.STRING); }

    Llama spawnMaid(Location loc, UUID owner, String model) {
        Llama z = loc.getWorld().spawn(loc, Llama.class, m -> {
            m.setAdult();
            m.setSilent(true);
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.setCanPickupItems(false);
            m.setCustomNameVisible(true);
            m.customName(Component.text(modelNames.getOrDefault(model, "女仆")));
            var sp = m.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
            if (sp != null) sp.setBaseValue(0.3);
            Bukkit.getMobGoals().removeAllGoals(m);
            var p = m.getPersistentDataContainer();
            p.set(kModel, PersistentDataType.STRING, model);
            p.set(kOwner, PersistentDataType.STRING, owner.toString());
        });
        attach(z);
        return z;
    }

    void tryAttach(Entity e) { if (isMaid(e)) attach((Llama) e); }

    /** plays a one-shot animation on her model when the blueprint has it */
    void playOnce(Llama z, String anim) {
        ModeledEntity me = ModelEngineAPI.getModeledEntity(z);
        if (me == null) return;
        for (ActiveModel am : me.getModels().values())
            if (am.getBlueprint().getAnimations().containsKey(anim)) am.getAnimationHandler().playAnimation(anim, 0.05, 0.1, 1, true);
    }

    void attach(Llama z) {
        life.init(z);
        String key = z.getPersistentDataContainer().get(kModel, PersistentDataType.STRING);
        ActiveModel am = ModelEngineAPI.createActiveModel(key);
        if (am == null) { getLogger().warning("no blueprint " + key); return; }
        ModeledEntity me = ModelEngineAPI.getOrCreateModeledEntity(z);
        me.getModels().keySet().forEach(me::removeModel);
        me.addModel(am, true);
        me.setBaseEntityVisible(false);
        int g = gen.merge(z.getUniqueId(), 1, Integer::sum);
        z.getScheduler().runAtFixedRate(this, t -> {
            if (gen.getOrDefault(z.getUniqueId(), 0) != g) { t.cancel(); return; } // re-attached (new outfit): the newer loop takes over
            tick(z, am, t);
        }, null, 10L, 10L);
    }

    void tick(Llama z, ActiveModel am, io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
        if (!z.isValid()) { task.cancel(); if (z.isDead()) gen.remove(z.getUniqueId()); return; }
        if (!bagOpen(z)) items.tick(z);
        life.tick(z);
        var anims = am.getBlueprint().getAnimations();
        boolean sit = z.getPersistentDataContainer().has(kSit, PersistentDataType.BYTE);
        Player owner = null;
        try { owner = Bukkit.getPlayer(UUID.fromString(z.getPersistentDataContainer().get(kOwner, PersistentDataType.STRING))); } catch (Exception ignored) {}
        boolean moving = !sit && ((!bagOpen(z) && work.tick(z)) || life.navigate(z, owner));
        if (sit) z.getPathfinder().stopPathfinding();
        String want = sit && anims.containsKey("sit") ? "sit" : moving && anims.containsKey("walk") ? "walk" : anims.containsKey("idle") ? "idle" : null;
        for (String n : new String[]{"sit", "walk", "idle"})
            if (!n.equals(want) && am.getAnimationHandler().isPlayingAnimation(n)) am.getAnimationHandler().stopAnimation(n);
        if (want != null && !am.getAnimationHandler().isPlayingAnimation(want)) am.getAnimationHandler().playAnimation(want, 0.2, 0.2, 1, true);
    }

    // ---------- events ----------
    @EventHandler public void onLoad(EntitiesLoadEvent e) {
        e.getEntities().forEach(this::tryAttach);
        for (Entity en : e.getEntities()) {
            String b64 = en.getPersistentDataContainer().get(kSnacks, PersistentDataType.STRING);
            if (b64 == null) continue;
            int n = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b64)).length;
            en.getScheduler().runDelayed(this, t -> snackView(en, n), null, 60L);
        }
    }
    @EventHandler public void onTransform(EntityTransformEvent e) { if (isMaid(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void onTarget(EntityTargetEvent e) { if (isMaid(e.getEntity())) e.setCancelled(true); }

    @EventHandler public void onUseEgg(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (!EGG.equals(ceId(it))) return;
        e.setCancelled(true);
        if (modelKeys.isEmpty()) { e.getPlayer().sendMessage(Msg.pBad("暂时没有可用的女仆模型。")); return; }
        Location at = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        spawnMaid(at, e.getPlayer().getUniqueId(), modelKeys.get(new Random().nextInt(modelKeys.size())));
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
        e.getPlayer().sendMessage(Msg.p("女仆来到了你的身边。").append(Msg.c(" {key}[怎么照顾她？]")
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/maid care"))));
    }

    @EventHandler public void onInteractMaid(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isMaid(e.getRightClicked())) return;
        e.setCancelled(true);
        Llama z = (Llama) e.getRightClicked();
        Player p = e.getPlayer();
        var pdc = z.getPersistentDataContainer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!p.getUniqueId().toString().equals(pdc.get(kOwner, PersistentDataType.STRING))) {
            if (items.onForeignMaid(p, z, hand)) return;
            p.sendMessage(Msg.pBad("这不是你的女仆。"));
            return;
        }
        if (items.onMaid(p, z, hand)) return;
        if (CHANGER.equals(ceId(hand))) {
            if (modelKeys.isEmpty()) return;
            wardrobe.open(p, z, Math.max(0, modelKeys.indexOf(pdc.get(kModel, PersistentDataType.STRING))) / Wardrobe.PER);
        } else if (p.isSneaking()) {
            boolean sit = pdc.has(kSit, PersistentDataType.BYTE);
            if (sit) pdc.remove(kSit); else pdc.set(kSit, PersistentDataType.BYTE, (byte) 1);
            p.sendActionBar(Msg.c(sit ? "女仆站了起来。" : "女仆坐下休息。"));
        } else {
            openBag(p, z);
        }
    }

    /** while a player looks into her bag nothing else may change it, or closing the window would overwrite it */
    boolean bagOpen(Entity z) { return bags.containsValue(z.getUniqueId()); }

    void openBag(Player p, Llama z) {
        Inventory inv = Bukkit.createInventory(null, Math.max(9, ((items.bagSize(z) + 8) / 9) * 9), Component.text("女仆的背包 · " + work.status(z, p)));
        String b64 = z.getPersistentDataContainer().get(kBag, PersistentDataType.STRING);
        if (b64 != null) {
            ItemStack[] stored = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b64));
            for (int i = 0; i < stored.length && i < inv.getSize(); i++) inv.setItem(i, stored[i]);
        }
        bags.put(inv, z.getUniqueId());
        p.openInventory(inv);
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        UUID id = bags.remove(e.getInventory());
        if (id == null) return;
        Entity z = Bukkit.getEntity(id);
        if (z == null) return;
        byte[] raw = ItemStack.serializeItemsAsBytes(e.getInventory().getContents());
        z.getPersistentDataContainer().set(kBag, PersistentDataType.STRING, Base64.getEncoder().encodeToString(raw));
    }

    // ---------- altar ----------
    static final Map<String, Set<Material>> C_TAGS = Map.of(
        "c:gems/lapis", Set.of(Material.LAPIS_LAZULI), "c:gems/diamond", Set.of(Material.DIAMOND),
        "c:ingots/gold", Set.of(Material.GOLD_INGOT), "c:ingots/iron", Set.of(Material.IRON_INGOT),
        "c:dusts/redstone", Set.of(Material.REDSTONE), "c:gunpowders", Set.of(Material.GUNPOWDER));

    boolean matches(String ing, ItemStack s) {
        if (ing.startsWith("#")) {
            String t = ing.substring(1);
            Set<Material> set = C_TAGS.get(t);
            if (set != null) return set.contains(s.getType());
            NamespacedKey nk = NamespacedKey.fromString(t);
            Tag<Material> tag = nk == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, nk, Material.class);
            return tag != null && tag.isTagged(s.getType());
        }
        String c = ceId(s);
        return ing.equals(c != null ? c : s.getType().getKey().toString());
    }

    @EventHandler public void onAltar(FurnitureInteractEvent e) {
        if (!ALTAR.equals(e.furniture().id().asString())) return;
        Location at = e.furniture().location();
        List<Item> drops = new ArrayList<>(at.getWorld().getNearbyEntitiesByType(Item.class, at, 2.5));
        for (JsonObject r : altar) {
            List<Item> used = new ArrayList<>();
            boolean ok = true;
            for (JsonElement ing : r.getAsJsonArray("ingredients")) {
                Item hit = null;
                for (Item d : drops) if (!used.contains(d) && matches(ing.getAsString(), d.getItemStack())) { hit = d; break; }
                if (hit == null) { ok = false; break; }
                used.add(hit);
            }
            if (!ok) continue;
            e.setCancelled(true);
            double need = r.has("power") ? r.get("power").getAsDouble() : 0;
            if (items.power(e.getPlayer()) + 1e-9 < need) {
                e.getPlayer().sendMessage(Msg.pBad(String.format("祭坛需要 %.1f 力量，你只有 %.1f。打怪有概率掉落 P点。", need, items.power(e.getPlayer()))));
                return;
            }
            items.setPower(e.getPlayer(), items.power(e.getPlayer()) - need);
            for (Item d : used) {
                ItemStack s = d.getItemStack();
                if (s.getAmount() <= 1) d.remove(); else { s.setAmount(s.getAmount() - 1); d.setItemStack(s); }
            }
            String ent = r.get("entity").getAsString();
            String res = r.getAsJsonObject("result").get("id").getAsString();
            Location out = at.clone().add(0, 1.2, 0);
            at.getWorld().spawnParticle(Particle.ENCHANT, out, 60, 0.4, 0.4, 0.4, 0.5);
            ItemStack usedFilm = null;
            for (Item u : used) if ((NS_ + "film").equals(ceId(u.getItemStack()))) usedFilm = u.getItemStack();
            if (ent.equals("touhou_little_maid:maid") && usedFilm != null && life.reviveFromFilm(usedFilm, out, e.getPlayer())) { /* revived from her film */ }
            else if (ent.equals("touhou_little_maid:maid") && !modelKeys.isEmpty())
                spawnMaid(out, e.getPlayer().getUniqueId(), modelKeys.get(new Random().nextInt(modelKeys.size())));
            else if (ent.equals("minecraft:lightning_bolt")) at.getWorld().strikeLightningEffect(out);
            else {
                ItemStack it = ceItem(res);
                if (it != null) at.getWorld().dropItemNaturally(out, it);
            }
            e.getPlayer().sendMessage(Msg.p("祭坛亮起了光。"));
            return;
        }
    }

    static boolean isFood(ItemStack it) {
        return it != null && !it.getType().isAir() && (it.getType().isEdible() || it.hasData(DataComponentTypes.CONSUMABLE));
    }

    // ---------- boards ----------
    @EventHandler public void onBoard(FurnitureInteractEvent e) { boards.handle(e); }
    @EventHandler public void onBoardBreak(FurnitureBreakEvent e) { boards.remove(e.furniture().baseEntity()); }

    // ---------- snack cabinet ----------
    static final int SNACK_MAX = 16;

    @EventHandler public void onSnack(FurnitureInteractEvent e) {
        if (!"touhou_little_maid:snack_cabinet".equals(e.furniture().id().asString())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Entity base = e.furniture().baseEntity();
        var pdc = base.getPersistentDataContainer();
        List<ItemStack> list = new ArrayList<>();
        String b64 = pdc.get(kSnacks, PersistentDataType.STRING);
        if (b64 != null) list.addAll(Arrays.asList(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b64))));
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isFood(hand)) {
            if (list.size() >= SNACK_MAX) { p.sendActionBar(Msg.bad("零食柜满了。")); return; }
            ItemStack one = hand.clone(); one.setAmount(1);
            list.add(one);
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        } else if (hand.getType().isAir() && !list.isEmpty()) {
            p.getInventory().addItem(list.remove(list.size() - 1)).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        } else {
            p.sendActionBar(Msg.c("零食柜里有 " + list.size() + " 份零食。"));
            return;
        }
        pdc.set(kSnacks, PersistentDataType.STRING, Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(list)));
        snackView(base, list.size());
        p.sendActionBar(Msg.c("零食柜里有 " + list.size() + " 份零食。"));
    }

    void snackView(Entity base, int n) {
        ModeledEntity me = ModelEngineAPI.getModeledEntity(base);
        if (me == null) return;
        for (ActiveModel am : me.getModels().values()) {
            am.getBone("half").ifPresent(b -> b.setVisible(n > 0 && n <= SNACK_MAX / 2));
            am.getBone("full").ifPresent(b -> b.setVisible(n > SNACK_MAX / 2));
        }
    }

    // ---------- seats and beds ----------
    @EventHandler public void onSeat(FurnitureInteractEvent e) {
        String id = e.furniture().id().asString();
        Player p = e.getPlayer();
        if (beds.contains(id)) {
            e.setCancelled(true);
            p.setRespawnLocation(e.furniture().location(), true);
            p.sendActionBar(Msg.c("已把这张床设为重生点。"));
            return;
        }
        JsonObject c = chairs.get(id);
        if (c == null) return;
        e.setCancelled(true);
        if (p.isInsideVehicle()) return;
        Location at = e.furniture().location().clone();
        for (Entity n : at.getWorld().getNearbyEntities(at, 0.6, 1.5, 0.6))
            if (n instanceof ArmorStand st && st.getPersistentDataContainer().has(kSeat, PersistentDataType.BYTE) && !st.getPassengers().isEmpty()) {
                p.sendActionBar(Msg.c("这里已经有人坐了。"));
                return;
            }
        double h = c.has("height") ? c.get("height").getAsDouble() : 0;
        at.add(0, h - 0.4, 0);
        ArmorStand st = at.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setVisible(false); s.setMarker(true); s.setGravity(false); s.setInvulnerable(true); s.setSmall(true);
            s.getPersistentDataContainer().set(kSeat, PersistentDataType.BYTE, (byte) 1);
        });
        st.addPassenger(p);
        seats.put(p.getUniqueId(), st);
    }

    @EventHandler public void onDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (e.getDismounted() instanceof ArmorStand st && st.getPersistentDataContainer().has(kSeat, PersistentDataType.BYTE)) {
            seats.values().remove(st);
            st.remove();
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        ArmorStand st = seats.remove(e.getPlayer().getUniqueId());
        if (st != null) st.remove();
    }

    @Override public void onDisable() { seats.values().forEach(Entity::remove); seats.clear(); boards.clear(); }

    // ---------- command ----------
    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) return false;
        switch (a[0]) {
            case "reload" -> { reloadConfig(); loadRuntime(); s.sendMessage("已重新读取。"); }
            case "install" -> {
                Path src = getDataFolder().toPath().resolve("source");
                try {
                    Files.createDirectories(src);
                    Path jar;
                    try (var st = Files.list(src)) { jar = st.filter(x -> x.toString().endsWith(".jar")).findFirst().orElse(null); }
                    if (jar == null) { s.sendMessage("请先把素材包放进 " + src); return true; }
                    s.sendMessage("正在安装,请稍等...");
                    Bukkit.getAsyncScheduler().runNow(this, t -> {
                        try {
                            String r = Installer.run(jar, Path.of("plugins"), Path.of(getConfig().getString("runtime-dir")));
                            Bukkit.getGlobalRegionScheduler().run(this, u -> {
                                s.sendMessage("安装完成:" + r + "。接着会重新生成资源包。");
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "meg reload");
                                // ModelEngine builds its pack asynchronously; CraftEngine must merge it afterwards
                                Bukkit.getGlobalRegionScheduler().runDelayed(this, v -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all"), 400L);
                                Bukkit.getGlobalRegionScheduler().runDelayed(this, v -> { loadRuntime(); s.sendMessage("已重新读取。"); }, 700L);
                            });
                        } catch (Exception ex) { getLogger().warning("install failed: " + ex); s.sendMessage("安装失败:" + ex.getMessage()); }
                    });
                } catch (IOException ex) { s.sendMessage("安装失败:" + ex.getMessage()); }
            }
            case "list" -> {
                int n = 0;
                for (World w : Bukkit.getWorlds())
                    for (Llama z : w.getEntitiesByClass(Llama.class))
                        if (isMaid(z)) { n++; s.sendMessage(w.getName() + " " + z.getLocation().getBlockX() + "," + z.getLocation().getBlockY() + "," + z.getLocation().getBlockZ()
                            + " " + z.getPersistentDataContainer().get(kModel, PersistentDataType.STRING) + " modeled=" + (ModelEngineAPI.getModeledEntity(z) != null)); }
                s.sendMessage("女仆数量:" + n);
            }
            case "give" -> {
                Player t = a.length > 1 ? Bukkit.getPlayer(a[1]) : (s instanceof Player p ? p : null);
                ItemStack it = ceItem(EGG);
                if (t == null || it == null) { s.sendMessage("找不到玩家或物品。"); return true; }
                t.getInventory().addItem(it);
            }
            case "summon" -> {
                if (modelKeys.isEmpty()) { s.sendMessage("还没有可用模型。"); return true; }
                String m = a.length > 1 ? a[1] : modelKeys.get(new Random().nextInt(modelKeys.size()));
                if (!modelKeys.contains(m)) { s.sendMessage("没有这个模型。"); return true; }
                if (s instanceof Player p) { spawnMaid(p.getLocation(), p.getUniqueId(), m); return true; }
                // console: /ellanmaid summon <model> <world> <x> <y> <z> (no owner, stays in place)
                if (a.length < 6 || Bukkit.getWorld(a[2]) == null) { s.sendMessage("控制台用法:summon <模型> <世界> <x> <y> <z>"); return true; }
                Location at = new Location(Bukkit.getWorld(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5]));
                at.getWorld().getChunkAtAsync(at).thenAccept(ch -> {
                    ch.addPluginChunkTicket(this);
                    Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> spawnMaid(at, new UUID(0, 0), m), 60L);
                });
            }
            default -> { return false; }
        }
        return true;
    }
}
