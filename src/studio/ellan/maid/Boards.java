package studio.ellan.maid;

import com.google.gson.*;
import io.github.tlm.folia.api.BoardGame;
import io.github.tlm.folia.open.board.MobileChessGame;
import io.github.tlm.folia.open.board.XqwlightGame;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Chess, xiangqi and gomoku played on the placed boards; the maid answers using the engines. */
final class Boards {
    static final String NS = "touhou_little_maid:";
    final EllanMaid pl;
    final Map<String, BoardGame> games = new HashMap<>();
    final Map<String, JsonObject> geo = new HashMap<>();
    final Map<UUID, Session> sessions = new HashMap<>();

    static final class Session {
        BoardGame game; JsonObject g; String fen; int selected = -1; UUID owner; boolean busy;
        final List<ItemDisplay> pieces = new ArrayList<>();
    }

    Boards(EllanMaid pl) {
        this.pl = pl;
        games.put(NS + "cchess", XqwlightGame.INSTANCE);
        games.put(NS + "wchess", MobileChessGame.INSTANCE);
        games.put(NS + "gomoku", new Gomoku());
    }

    void load(Path dir) {
        geo.clear();
        try {
            JsonObject o = JsonParser.parseString(Files.readString(dir.resolve("board_models.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement b : o.getAsJsonArray("boards")) geo.put(NS + b.getAsJsonObject().get("id").getAsString(), b.getAsJsonObject());
        } catch (Exception ex) { pl.getLogger().warning("board manifest: " + ex); }
    }

    boolean handle(FurnitureInteractEvent e) {
        String id = e.furniture().id().asString();
        BoardGame g = games.get(id);
        JsonObject geom = geo.get(id);
        if (g == null || geom == null) return false;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Entity base = e.furniture().baseEntity();
        Session s = sessions.computeIfAbsent(base.getUniqueId(), k -> {
            Session n = new Session(); n.game = g; n.g = geom; n.fen = g.initialFen(); return n;
        });
        if (p.isSneaking()) {
            s.fen = g.initialFen(); s.selected = -1; s.owner = null; s.busy = false;
            render(s, base);
            p.sendActionBar(Msg.c("棋局已重新开始。"));
            return true;
        }
        if (s.owner != null && !s.owner.equals(p.getUniqueId())) {
            Player o = Bukkit.getPlayer(s.owner);
            if (o != null && o.getWorld().equals(p.getWorld()) && o.getLocation().distance(base.getLocation()) < 6) {
                p.sendActionBar(Msg.c("这盘棋已经有人在下了。")); return true;
            }
        }
        s.owner = p.getUniqueId();
        if (s.pieces.isEmpty() && !g.pieces(s.fen).isEmpty()) render(s, base);
        if (s.busy) { p.sendActionBar(Msg.c("女仆正在思考……")); return true; }
        Location hit = e.interactionPoint();
        if (hit == null) return true;
        double yaw = Math.toRadians(base.getLocation().getYaw());
        double dx = hit.getX() - base.getLocation().getX(), dz = hit.getZ() - base.getLocation().getZ();
        double lx = dx * Math.cos(yaw) + dz * Math.sin(yaw), lz = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
        int sq = g.clickToSquare(lx, lz);
        if (sq < 0) return true;
        BoardGame.Result r = g.click(s.fen, s.selected, sq);
        switch (r.action()) {
            case SELECT -> {
                s.selected = r.selected();
                Cell(s, base, g.squareToCell(r.selected()).column(), g.squareToCell(r.selected()).row());
            }
            case MOVE -> {
                s.selected = -1;
                s.fen = r.move().fenAfter();
                render(s, base);
                if (r.move().mate()) { p.sendMessage(Msg.p("你赢了！")); finish(s); return true; }
                if (r.move().check()) p.sendActionBar(Msg.c("将军！"));
                if (g.isDraw(s.fen)) { p.sendMessage(Msg.p("和棋。")); finish(s); return true; }
                reply(s, base, p);
            }
            default -> { }
        }
        return true;
    }

    private void finish(Session s) { s.owner = null; s.selected = -1; }

    private void reply(Session s, Entity base, Player p) {
        s.busy = true;
        final String fen = s.fen;
        Bukkit.getAsyncScheduler().runNow(pl, t -> {
            BoardGame.Move m;
            try { m = s.game.think(fen, 1000); } catch (Throwable ex) { m = null; pl.getLogger().warning("think failed: " + ex); }
            final BoardGame.Move mv = m;
            Bukkit.getGlobalRegionScheduler().run(pl, u -> {
                s.busy = false;
                if (mv == null) { p.sendMessage(Msg.p("女仆无子可走，你赢了！")); finish(s); return; }
                s.fen = mv.fenAfter();
                render(s, base);
                if (mv.mate()) { p.sendMessage(Msg.p("女仆赢了。")); finish(s); }
                else if (mv.check()) p.sendActionBar(Msg.c("你被将军了！"));
            });
        });
    }

    /** world position of a board cell */
    Location cellLoc(Session s, Entity base, int col, int row, double lift) {
        double sp = s.g.get("spacing").getAsDouble();
        int r = s.game.mirroredRows() ? s.g.get("rows").getAsInt() - 1 - row : row;
        double lx = s.game.originX() + col * sp, lz = s.game.originZ() + r * sp;
        double yaw = Math.toRadians(base.getLocation().getYaw());
        double wx = lx * Math.cos(yaw) - lz * Math.sin(yaw), wz = lx * Math.sin(yaw) + lz * Math.cos(yaw);
        return base.getLocation().clone().add(wx, s.g.get("surface_y").getAsDouble() + lift, wz);
    }

    void Cell(Session s, Entity base, int col, int row) {
        Location at = cellLoc(s, base, col, row, 0.05);
        base.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 8, 0.05, 0.05, 0.05, 0);
    }

    void render(Session s, Entity base) {
        s.pieces.forEach(Entity::remove);
        s.pieces.clear();
        // piece models are drawn at real size inside the 16px model space (a xiangqi piece is only ~4px wide),
        // so the display scale stays 1.0; the model origin is the cube centre, hence the 0.5 lift
        float sc = (float) pl.getConfig().getDouble("board-piece-scale", 1.0);
        float yawDeg = base.getLocation().getYaw();
        for (BoardGame.Placed p : s.game.pieces(s.fen)) {
            ItemStack it = pl.ceItem(NS + p.item());
            if (it == null) continue;
            it.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("touhou_little_maid", p.item()));
            Location at = cellLoc(s, base, p.column(), p.row(), 0.5 * sc);
            at.setYaw(yawDeg); at.setPitch(0);
            ItemDisplay d = base.getWorld().spawn(at, ItemDisplay.class, x -> {
                x.setItemStack(it);
                x.setPersistent(false);
                x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sc, sc, sc), new AxisAngle4f()));
            });
            s.pieces.add(d);
        }
    }

    void remove(Entity base) {
        Session s = sessions.remove(base.getUniqueId());
        if (s != null) s.pieces.forEach(Entity::remove);
    }

    void clear() {
        sessions.values().forEach(s -> s.pieces.forEach(Entity::remove));
        sessions.clear();
    }
}
