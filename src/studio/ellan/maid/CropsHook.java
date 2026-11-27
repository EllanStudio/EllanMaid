package studio.ellan.maid;

import net.momirealms.customcrops.api.BukkitCustomCropsPlugin;
import net.momirealms.customcrops.api.core.ExistenceForm;
import net.momirealms.customcrops.api.core.block.CropBlock;
import net.momirealms.customcrops.api.core.mechanic.crop.CropConfig;
import net.momirealms.customcrops.api.core.world.CustomCropsBlockState;
import net.momirealms.customcrops.api.core.world.CustomCropsWorld;
import net.momirealms.customcrops.api.core.world.Pos3;
import net.momirealms.customcrops.api.core.wrapper.WrappedInteractEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** CustomCrops access, loaded only when the plugin is present. Harvest works like the sickle (EllanHarvest). */
final class CropsHook {
    final BukkitCustomCropsPlugin cc = BukkitCustomCropsPlugin.getInstance();

    CustomCropsWorld<?> world(Block b) { return cc.getWorldManager().getWorld(b.getWorld()).orElse(null); }

    CropConfig crop(Block b, CustomCropsBlockState[] out) {
        CustomCropsWorld<?> w = world(b);
        if (w == null) return null;
        Optional<CustomCropsBlockState> os = w.getLoadedBlockState(Pos3.from(b.getLocation()));
        if (os.isEmpty() || !(os.get().type() instanceof CropBlock cb)) return null;
        if (out != null) out[0] = os.get();
        return cb.config(os.get());
    }

    boolean occupied(Block b) {
        CustomCropsWorld<?> w = world(b);
        return w != null && w.getLoadedBlockState(Pos3.from(b.getLocation())).map(s -> s.type() instanceof CropBlock).orElse(false);
    }

    boolean ripe(Block b) {
        CustomCropsBlockState[] st = new CustomCropsBlockState[1];
        CropConfig c = crop(b, st);
        return c != null && ((CropBlock) st[0].type()).point(st[0]) >= c.maxPoints();
    }

    String seedOf(Block b) { CropConfig c = crop(b, null); return c == null ? null : c.seed(); }

    static Cancellable sink() {
        return new Cancellable() {
            boolean c;
            public boolean isCancelled() { return c; }
            public void setCancelled(boolean cancel) { c = cancel; }
        };
    }

    boolean harvest(Player actor, Block b, ItemStack tool) {
        CustomCropsBlockState[] st = new CustomCropsBlockState[1];
        CropConfig c = crop(b, st);
        if (c == null) return false;
        CropBlock crop = (CropBlock) st[0].type();
        int before = crop.point(st[0]);
        if (before < c.maxPoints()) return false;
        String stageId = cc.getItemManager().blockID(b);
        if (stageId == null || c.stageByID(stageId) == null) return false;
        String toolId = EllanMaid.ceId(tool);
        Location loc = b.getLocation();
        crop.onInteract(new WrappedInteractEvent(ExistenceForm.BLOCK, actor, world(b), loc, stageId,
            tool, toolId == null ? tool.getType().getKey().toString() : toolId, EquipmentSlot.HAND, BlockFace.UP, sink()));
        Optional<CustomCropsBlockState> after = world(b).getLoadedBlockState(Pos3.from(loc));
        return after.isEmpty() || !(after.get().type() instanceof CropBlock) || crop.point(after.get()) != before;
    }

    /** uses the seed on the pot below the crop spot, as the owner would */
    void plant(Player actor, Block spot, ItemStack seed, String seedId) {
        Block pot = spot.getRelative(BlockFace.DOWN);
        String potId = cc.getItemManager().blockID(pot);
        if (potId == null) return;
        cc.getItemManager().handlePlayerInteractBlock(actor, pot, potId, BlockFace.UP, EquipmentSlot.HAND, seed, sink());
    }
}
