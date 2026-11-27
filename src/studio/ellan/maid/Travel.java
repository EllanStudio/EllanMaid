package studio.ellan.maid;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Maids that follow their owner travel with them: on quit (which includes switching server) each following
 * maid becomes a photo in the owner's inventory (or ender chest), before the sync plugin saves at MONITOR.
 * After the player's data is applied on the next server the photo turns back into the maid.
 * Maids in home mode (the workers) stay where they are.
 */
final class Travel implements Listener {
    final EllanMaid pl;
    final NamespacedKey kTravel;
    final boolean synced;

    Travel(EllanMaid pl) {
        this.pl = pl;
        kTravel = new NamespacedKey(pl, "travel");
        synced = pl.getServer().getPluginManager().getPlugin("SparrowSync") != null;
    }

    boolean enabled() { return pl.getConfig().getBoolean("travel.enabled", true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        if (!enabled()) return;
        Player p = e.getPlayer();
        double r = pl.getConfig().getDouble("travel.range", 32);
        for (Llama z : pl.items.ownedMaids(p)) {
            if (pl.life.home(z) || z.getPersistentDataContainer().has(pl.kSit, PersistentDataType.BYTE)) continue;
            if (z.getLocation().distanceSquared(p.getLocation()) > r * r) continue;
            ItemStack photo = pl.life.makeStored(z, "photo", true);
            if (photo == null) continue;
            var m = photo.getItemMeta();
            m.getPersistentDataContainer().set(kTravel, PersistentDataType.BYTE, (byte) 1);
            photo.setItemMeta(m);
            if (!p.getInventory().addItem(photo).isEmpty() && !p.getEnderChest().addItem(photo).isEmpty()) continue; // no room: she stays
            pl.life.discard(z);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled() || synced) return; // with SparrowSync wait for its data (SyncHook)
        Player p = e.getPlayer();
        p.getScheduler().runDelayed(pl, t -> release(p), null, 40L);
    }

    /** turns every travel photo of this player back into a maid next to them */
    void release(Player p) {
        if (!p.isOnline()) return;
        List<ItemStack> found = new ArrayList<>();
        for (var inv : List.of(p.getInventory(), p.getEnderChest()))
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || !s.hasItemMeta() || !s.getItemMeta().getPersistentDataContainer().has(kTravel, PersistentDataType.BYTE)) continue;
                if (!p.getUniqueId().toString().equals(s.getItemMeta().getPersistentDataContainer().get(pl.kOwner, PersistentDataType.STRING))) continue;
                inv.setItem(i, null);
                found.add(s);
            }
        for (ItemStack s : found) {
            var d = s.getItemMeta().getPersistentDataContainer();
            String model = d.get(pl.kModel, PersistentDataType.STRING);
            if (model == null) continue;
            if (!pl.modelKeys.contains(model)) { p.getInventory().addItem(s); continue; } // model not on this server: keep the photo
            Llama m = pl.spawnMaid(p.getLocation(), p.getUniqueId(), model);
            MaidLife.copy(d, m.getPersistentDataContainer(), pl);
            String home = m.getPersistentDataContainer().get(pl.life.kHome, PersistentDataType.STRING);
            if (home != null && Bukkit.getWorld(home.split("\\|")[0]) == null) pl.life.clearHome(m); // home was on another server
            pl.life.setHome(m, false);
            pl.life.applyLevel(m);
            Double hp = d.get(pl.life.kHp, PersistentDataType.DOUBLE);
            if (hp != null) m.setHealth(Math.max(1, Math.min(hp, m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())));
        }
        if (!found.isEmpty()) p.sendActionBar(Msg.c(found.size() == 1 ? "女仆跟着你来了。" : found.size() + " 位女仆跟着你来了。"));
    }
}
