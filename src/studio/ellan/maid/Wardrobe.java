package studio.ellan.maid;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/** Outfit change: the outfit ticket (change_maid_model) opens a list of every outfit; picking one uses up the ticket. */
final class Wardrobe implements Listener {
    static final int PER = 45;
    final EllanMaid pl;

    Wardrobe(EllanMaid pl) { this.pl = pl; }

    static final class View implements InventoryHolder {
        final UUID maid; final int page; Inventory inv;
        View(UUID maid, int page) { this.maid = maid; this.page = page; }
        public Inventory getInventory() { return inv; }
    }

    void open(Player p, Llama z, int page) {
        List<String> keys = pl.modelKeys;
        int pages = Math.max(1, (keys.size() + PER - 1) / PER);
        page = Math.floorMod(page, pages);
        View v = new View(z.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(v, 54, Component.text("挑选女仆的衣服 " + (page + 1) + "/" + pages));
        v.inv = inv;
        String cur = z.getPersistentDataContainer().get(pl.kModel, PersistentDataType.STRING);
        for (int i = 0; i < PER && page * PER + i < keys.size(); i++) {
            String k = keys.get(page * PER + i);
            ItemStack it = new ItemStack(k.equals(cur) ? Material.LIME_DYE : Material.NAME_TAG);
            ItemMeta m = it.getItemMeta();
            m.displayName(Component.text(pl.modelNames.getOrDefault(k, k)).decoration(TextDecoration.ITALIC, false));
            if (k.equals(cur)) m.lore(List.of(Component.text("正在穿着", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            it.setItemMeta(m);
            inv.setItem(i, it);
        }
        inv.setItem(45, button(Material.ARROW, "上一页"));
        inv.setItem(53, button(Material.ARROW, "下一页"));
        inv.setItem(49, button(Material.PAPER, pl.getConfig().getBoolean("wardrobe.consume", true) ? "选好后会用掉一张换装券" : "随便挑"));
        p.openInventory(inv);
    }

    static ItemStack button(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof View) e.setCancelled(true); }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof View v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (!(Bukkit.getEntity(v.maid) instanceof Llama z) || !z.isValid() || !pl.items.owns(p, z)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot == 45) { open(p, z, v.page - 1); return; }
        if (slot == 53) { open(p, z, v.page + 1); return; }
        int idx = v.page * PER + slot;
        if (slot >= PER || idx >= pl.modelKeys.size()) return;
        String next = pl.modelKeys.get(idx);
        if (next.equals(z.getPersistentDataContainer().get(pl.kModel, PersistentDataType.STRING))) return;
        if (pl.getConfig().getBoolean("wardrobe.consume", true) && p.getGameMode() != GameMode.CREATIVE && !takeTicket(p)) {
            p.sendActionBar(Component.text("你身上没有换装券了。"));
            p.closeInventory();
            return;
        }
        z.getPersistentDataContainer().set(pl.kModel, PersistentDataType.STRING, next);
        z.customName(Component.text(pl.modelNames.getOrDefault(next, "女仆")));
        pl.attach(z);
        p.closeInventory();
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.2f);
        p.sendMessage(Component.text("换上了新的衣服：" + pl.modelNames.getOrDefault(next, next)));
    }

    boolean takeTicket(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && EllanMaid.CHANGER.equals(EllanMaid.ceId(s))) { s.setAmount(s.getAmount() - 1); inv.setItem(i, s.getAmount() > 0 ? s : null); return true; }
        }
        return false;
    }
}
