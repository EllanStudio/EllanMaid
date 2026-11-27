package studio.ellan.maid;

import net.momirealms.customfishing.api.event.FishingLootSpawnEvent;
import net.momirealms.customfishing.api.mechanic.loot.Loot;
import net.momirealms.customfishing.api.mechanic.loot.LootType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Llama;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** CustomFishing catches: only loot in the allowed groups and outside the denied groups/ids is copied. */
final class CustomFishingHook implements Listener {
    final Fishing f;

    CustomFishingHook(Fishing f) { this.f = f; }

    boolean eligible(Loot loot) {
        if (loot.type() != LootType.ITEM) return false;
        List<String> allow = f.cfg().getStringList("fish-groups");
        List<String> deny = f.cfg().getStringList("fish-deny-groups");
        List<String> denyIds = f.cfg().getStringList("fish-deny-loot");
        if (denyIds.contains(loot.id())) return false;
        String[] groups = loot.lootGroup() == null ? new String[0] : loot.lootGroup();
        boolean ok = allow.isEmpty();
        for (String g : groups) {
            if (deny.contains(g)) return false;
            if (allow.contains(g)) ok = true;
        }
        return ok;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoot(FishingLootSpawnEvent e) {
        if (!(e.getEntity() instanceof Item it) || !eligible(e.getLoot())) return;
        Llama z = f.helper(e.getPlayer());
        if (z == null || !f.tryHelp(e.getPlayer(), z)) return;
        ItemStack copy = it.getItemStack().clone();
        copy.setAmount(1);
        f.give(e.getPlayer(), z, copy);
    }
}
