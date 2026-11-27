package studio.ellan.maid;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Fishing companion: a following maid with a fishing rod in her bag sometimes lands one more of the
 * owner's catch. Each extra catch costs stamina, rod durability and daily allowance; rare loot is never copied.
 */
class Fishing implements Listener {
    final EllanMaid pl;
    final Random rnd = new Random();

    Fishing(EllanMaid pl) { this.pl = pl; }

    ConfigurationSection cfg() { return pl.work.cfg(); }

    /** maid that can help with this catch, or null */
    Llama helper(Player p) {
        double r = cfg().getDouble("fish-range", 10);
        for (var en : p.getNearbyEntities(r, 4, r)) {
            if (!(en instanceof Llama z) || !pl.isMaid(z) || !pl.items.owns(p, z) || pl.life.home(z) || pl.bagOpen(z)) continue;
            if (rod(pl.items.readBag(z)) >= 0) return z;
        }
        return null;
    }

    static int rod(ItemStack[] bag) {
        for (int i = 0; i < bag.length; i++) if (bag[i] != null && bag[i].getType() == Material.FISHING_ROD) return i;
        return -1;
    }

    double chance(Llama z) { return cfg().getDouble("fish-chance", 0.10) + cfg().getDouble("fish-chance-per-level", 0.025) * pl.life.level(z); }

    /** rolls and pays; true when the maid lands an extra copy */
    boolean tryHelp(Player p, Llama z) {
        if (rnd.nextDouble() >= chance(z)) return false;
        if (!pl.work.pay(z, p, "fish")) return false;
        ItemStack[] bag = pl.items.readBag(z);
        int i = rod(bag);
        if (i < 0) return false;
        if (!ItemFeatures.damage(bag[i], cfg().getInt("tool-wear.fish", 1))) { bag[i] = null; p.sendMessage(Component.text("[女仆] 女仆的鱼竿用坏了。")); }
        pl.items.writeBag(z, bag);
        return true;
    }

    void give(Player p, Llama z, ItemStack copy) {
        p.getInventory().addItem(copy).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        z.swingMainHand();
        z.getWorld().spawnParticle(Particle.SPLASH, z.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.1);
        z.getWorld().playSound(z.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.2f);
        p.sendActionBar(Component.text("女仆帮你多钓上了一条！"));
    }

    static final Set<Material> VANILLA_FISH = EnumSet.of(Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);

    /** only used when CustomFishing is not installed */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanilla(PlayerFishEvent e) {
        if (pl.getServer().getPluginManager().isPluginEnabled("CustomFishing")) return;
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item it)) return;
        if (!VANILLA_FISH.contains(it.getItemStack().getType())) return;
        Llama z = helper(e.getPlayer());
        if (z == null || !tryHelp(e.getPlayer(), z)) return;
        ItemStack copy = it.getItemStack().clone();
        copy.setAmount(1);
        give(e.getPlayer(), z, copy);
    }
}
