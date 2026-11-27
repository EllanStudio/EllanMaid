package studio.ellan.maid;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Model switcher (showcase tool, as in the original mod): right-click a maid with it to bind her, place it,
 * right-click it to choose the outfits in its list, then each redstone pulse next to it (or sneak + right-click)
 * puts the next outfit of the list on the bound maid.
 */
final class ModelSwitcher implements Listener {
    static final String ID = EllanMaid.NS_ + "model_switcher";
    static final int PER = 45;
    final EllanMaid pl;
    final NamespacedKey kBind, kList, kIdx, kPowered;

    ModelSwitcher(EllanMaid pl) {
        this.pl = pl;
        kBind = new NamespacedKey(pl, "switch_bind");
        kList = new NamespacedKey(pl, "switch_list");
        kIdx = new NamespacedKey(pl, "switch_idx");
        kPowered = new NamespacedKey(pl, "switch_powered");
    }

    /** right-click on a maid with the switcher in hand; true = handled */
    boolean bind(Player p, Llama z, ItemStack hand) {
        if (!pl.items.owns(p, z) && !p.hasPermission("ellanmaid.admin")) { p.sendActionBar(Msg.c("只能绑定自己的女仆。")); return true; }
        ItemMeta m = hand.getItemMeta();
        m.getPersistentDataContainer().set(kBind, PersistentDataType.STRING, z.getUniqueId().toString());
        m.lore(List.of(Msg.lore("已经绑定女仆：" + pl.items.plain(z)),
            Msg.lore("放到地上，右键挑选要轮换的衣服"),
            Msg.lore("旁边给红石信号或潜行右键：换下一件")));
        hand.setItemMeta(m);
        p.playSound(p.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 0.8f, 1.4f);
        p.sendActionBar(Msg.good("模型切换器绑定了这位女仆。"));
        return true;
    }

    @EventHandler public void onPlace(FurniturePlaceEvent e) {
        if (!ID.equals(e.furniture().id().asString())) return;
        ItemStack hand = e.player().getInventory().getItemInMainHand();
        String bound = hand.hasItemMeta() ? hand.getItemMeta().getPersistentDataContainer().get(kBind, PersistentDataType.STRING) : null;
        if (bound == null) { e.player().sendMessage(Msg.pBad("未绑定女仆，请先拿着模型切换器右键女仆进行绑定！")); return; }
        Entity base = e.furniture().baseEntity();
        base.getPersistentDataContainer().set(kBind, PersistentDataType.STRING, bound);
        watch(base);
        e.player().sendActionBar(Msg.c("右键切换器，挑选要轮换的衣服。"));
    }

    @EventHandler public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en.getPersistentDataContainer().has(kBind, PersistentDataType.STRING)) watch(en);
    }

    /** polls the redstone power around the switcher and switches on each rising edge */
    void watch(Entity base) {
        base.getScheduler().runAtFixedRate(pl, t -> {
            if (!base.isValid()) { t.cancel(); return; }
            var pdc = base.getPersistentDataContainer();
            boolean on = base.getLocation().getBlock().getBlockPower() > 0;
            boolean was = pdc.has(kPowered, PersistentDataType.BYTE);
            if (on == was) return;
            if (on) { pdc.set(kPowered, PersistentDataType.BYTE, (byte) 1); next(base, null); }
            else pdc.remove(kPowered);
        }, null, 20L, 4L);
    }

    List<String> list(PersistentDataContainer pdc) {
        String s = pdc.get(kList, PersistentDataType.STRING);
        List<String> out = new ArrayList<>();
        if (s != null) for (String k : s.split(",")) if (pl.modelKeys.contains(k)) out.add(k);
        return out;
    }

    void next(Entity base, Player who) {
        var pdc = base.getPersistentDataContainer();
        List<String> l = list(pdc);
        if (l.isEmpty()) { if (who != null) who.sendActionBar(Msg.bad("还没有挑选衣服，右键切换器打开列表。")); return; }
        Entity e;
        try { e = Bukkit.getEntity(UUID.fromString(pdc.get(kBind, PersistentDataType.STRING))); } catch (Exception ex) { e = null; }
        if (!(e instanceof Llama z) || !pl.isMaid(z)) { if (who != null) who.sendActionBar(Msg.bad("找不到绑定的女仆，她需要在附近。")); return; }
        int i = (pdc.getOrDefault(kIdx, PersistentDataType.INTEGER, -1) + 1) % l.size();
        pdc.set(kIdx, PersistentDataType.INTEGER, i);
        String m = l.get(i);
        z.getPersistentDataContainer().set(pl.kModel, PersistentDataType.STRING, m);
        z.customName(Component.text(pl.modelNames.getOrDefault(m, "女仆")));
        pl.attach(z);
        z.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, z.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0);
        if (who != null) who.sendActionBar(Msg.c("换上了：" + pl.modelNames.getOrDefault(m, m)));
    }

    @EventHandler public void onUse(FurnitureInteractEvent e) {
        if (!ID.equals(e.furniture().id().asString())) return;
        e.setCancelled(true);
        Entity base = e.furniture().baseEntity();
        Player p = e.getPlayer();
        if (!base.getPersistentDataContainer().has(kBind, PersistentDataType.STRING)) { p.sendMessage(Msg.pBad("未绑定女仆，请先拿着模型切换器右键女仆进行绑定，再放下。")); return; }
        if (p.isSneaking()) next(base, p); else open(p, base, 0);
    }

    static final class View implements InventoryHolder {
        final UUID base; final int page; Inventory inv;
        View(UUID base, int page) { this.base = base; this.page = page; }
        public Inventory getInventory() { return inv; }
    }

    void open(Player p, Entity base, int page) {
        List<String> keys = pl.modelKeys;
        int pages = Math.max(1, (keys.size() + PER - 1) / PER);
        page = Math.floorMod(page, pages);
        List<String> chosen = list(base.getPersistentDataContainer());
        View v = new View(base.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(v, 54, Component.text("轮换的衣服（已选 " + chosen.size() + "）" + (page + 1) + "/" + pages));
        v.inv = inv;
        for (int i = 0; i < PER && page * PER + i < keys.size(); i++) {
            String k = keys.get(page * PER + i);
            boolean in = chosen.contains(k);
            ItemStack it = new ItemStack(in ? Material.LIME_DYE : Material.NAME_TAG);
            ItemMeta m = it.getItemMeta();
            m.displayName(Component.text(pl.modelNames.getOrDefault(k, k)).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Msg.lore(in ? "{good}已加入{label}，点击移出" : "点击加入")));
            it.setItemMeta(m);
            inv.setItem(i, it);
        }
        inv.setItem(45, Wardrobe.button(Material.ARROW, "上一页"));
        inv.setItem(53, Wardrobe.button(Material.ARROW, "下一页"));
        inv.setItem(49, Wardrobe.button(Material.REDSTONE, "旁边给红石信号或潜行右键切换器：换下一件"));
        p.openInventory(inv);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof View) e.setCancelled(true); }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof View v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Entity base = Bukkit.getEntity(v.base);
        if (base == null || !base.isValid()) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot == 45) { open(p, base, v.page - 1); return; }
        if (slot == 53) { open(p, base, v.page + 1); return; }
        int idx = v.page * PER + slot;
        if (slot >= PER || idx >= pl.modelKeys.size()) return;
        String k = pl.modelKeys.get(idx);
        List<String> l = list(base.getPersistentDataContainer());
        if (!l.remove(k)) l.add(k);
        base.getPersistentDataContainer().set(kList, PersistentDataType.STRING, String.join(",", l));
        open(p, base, v.page);
    }
}
