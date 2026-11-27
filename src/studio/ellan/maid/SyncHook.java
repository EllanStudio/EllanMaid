package studio.ellan.maid;

import net.momirealms.sparrow.sync.api.event.SyncCompleteEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** with SparrowSync the travel photos are released only after the player's synced inventory is applied */
final class SyncHook implements Listener {
    final Travel travel;
    SyncHook(Travel travel) { this.travel = travel; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSynced(SyncCompleteEvent e) {
        if (!travel.enabled()) return;
        e.getPlayer().getScheduler().runDelayed(travel.pl, t -> travel.release(e.getPlayer()), null, 10L);
    }
}
