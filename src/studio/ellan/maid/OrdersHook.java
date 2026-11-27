package studio.ellan.maid;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * P点 for finished timed orders. The order plugin publishes "COMPLETED|nonce|task|player|reward" on a Redis
 * channel to every server; the server where that player is online hands out the P点. Plain RESP over a socket,
 * so no Redis library is needed; reconnects on its own.
 */
final class OrdersHook implements Runnable {
    final EllanMaid pl;
    volatile boolean running = true;
    volatile Socket socket;
    final Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<>());
    final Random rnd = new Random();

    OrdersHook(EllanMaid pl) { this.pl = pl; }

    ConfigurationSection cfg() { return pl.getConfig().getConfigurationSection("power-orders"); }

    void start() { Thread t = new Thread(this, "EllanMaid-orders"); t.setDaemon(true); t.start(); }

    void stop() { running = false; try { if (socket != null) socket.close(); } catch (IOException ignored) { } }

    @Override public void run() {
        long backoff = 2000;
        while (running) {
            ConfigurationSection c = cfg();
            try (Socket s = new Socket()) {
                socket = s;
                s.connect(new InetSocketAddress(c.getString("redis.host", "127.0.0.1"), c.getInt("redis.port", 6379)), 5000);
                OutputStream out = new BufferedOutputStream(s.getOutputStream());
                InputStream in = new BufferedInputStream(s.getInputStream());
                String pw = c.getString("redis.password", "");
                if (!pw.isEmpty()) { send(out, "AUTH", pw); Object r = read(in); if (r instanceof IOException e) throw e; }
                send(out, "SUBSCRIBE", c.getString("channel", "ellan:timedorders:events"));
                pl.getLogger().info("orders: listening on " + c.getString("channel", "ellan:timedorders:events"));
                backoff = 2000;
                while (running) {
                    Object o = read(in);
                    if (o instanceof List<?> l && l.size() == 3 && "message".equals(l.get(0))) handle(String.valueOf(l.get(2)));
                }
            } catch (Exception e) {
                if (!running) return;
                pl.getLogger().warning("orders: redis connection lost (" + e.getMessage() + "), retry in " + backoff / 1000 + " s");
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { return; }
                backoff = Math.min(backoff * 2, 60_000);
            }
        }
    }

    void handle(String msg) {
        String[] p = msg.split("\\|");
        if (p.length < 4 || !"COMPLETED".equals(p[0])) return;
        if (!seen.add(p[1])) return;
        if (seen.size() > 500) { var it = seen.iterator(); it.next(); it.remove(); }
        Player player = Bukkit.getPlayerExact(p[3]);
        if (player == null) return; // finished on another server, or already left
        player.getScheduler().run(pl, t -> give(player, p[2]), null);
    }

    void give(Player player, String task) {
        ConfigurationSection c = cfg();
        if (rnd.nextDouble() >= c.getDouble("chance", 0.5)) return;
        ItemStack it = pl.ceItem(ItemFeatures.NS + "power_point");
        if (it == null) return;
        int n = Math.max(1, c.getInt("points", 1));
        it.setAmount(n);
        player.getInventory().addItem(it).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.sendMessage(Msg.p("完成限时订单，额外获得 " + n + " 个 P点。"));
        pl.getLogger().info("orders: " + player.getName() + " finished " + task + ", gave " + n + " P点");
    }

    static void send(OutputStream out, String... args) throws IOException {
        StringBuilder b = new StringBuilder("*").append(args.length).append("\r\n");
        for (String a : args) { byte[] x = a.getBytes(StandardCharsets.UTF_8); b.append('$').append(x.length).append("\r\n").append(a).append("\r\n"); }
        out.write(b.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') { in.read(); break; }
            b.write(c);
        }
        if (c == -1) throw new EOFException("closed");
        return b.toString(StandardCharsets.UTF_8);
    }

    static Object read(InputStream in) throws IOException {
        String l = line(in);
        if (l.isEmpty()) throw new IOException("empty reply");
        char t = l.charAt(0);
        String v = l.substring(1);
        switch (t) {
            case '+': return v;
            case '-': return new IOException(v);
            case ':': return Long.parseLong(v);
            case '$': {
                int n = Integer.parseInt(v);
                if (n < 0) return null;
                byte[] x = in.readNBytes(n);
                in.read(); in.read();
                return new String(x, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(v);
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < n; i++) out.add(read(in));
                return out;
            }
            default: throw new IOException("bad reply " + l);
        }
    }
}
