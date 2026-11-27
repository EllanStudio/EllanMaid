package studio.ellan.maid;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/** MythicMobs kills drop power points by pack (Alex's Mobs pack drops more than plain monsters). */
final class PowerHook implements Listener {
    final EllanMaid pl;
    PowerHook(EllanMaid pl) { this.pl = pl; }

    static boolean isMythic(Entity e) {
        try { return MythicBukkit.inst().getMobManager().isActiveMob(e.getUniqueId()); } catch (Throwable t) { return false; }
    }

    @EventHandler public void onDeath(MythicMobDeathEvent e) {
        if (!(e.getKiller() instanceof Player)) return;
        ConfigurationSection s = pl.getConfig().getConfigurationSection("power-mythic");
        if (s == null) return;
        String pack = null;
        try { pack = e.getMobType().getPack() == null ? null : e.getMobType().getPack().getName(); } catch (Throwable ignored) { }
        double chance = s.getDouble("mobs." + e.getMobType().getInternalName(),
            pack != null && s.isSet("packs." + pack) ? s.getDouble("packs." + pack) : s.getDouble("default", 0.05));
        int points = s.getInt("points", 1);
        if (Math.random() >= chance) return;
        ItemStack it = pl.ceItem(ItemFeatures.NS + "power_point");
        if (it == null) return;
        it.setAmount(points);
        e.getDrops().add(it);
    }
}
