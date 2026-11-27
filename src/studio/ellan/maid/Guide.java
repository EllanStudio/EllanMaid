package studio.ellan.maid;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** /maid: how to play, in chat, one topic per page with clickable tabs. */
final class Guide implements CommandExecutor {
    final EllanMaid pl;
    Guide(EllanMaid pl) { this.pl = pl; }

    static final List<String> ORDER = List.of("summon", "care", "work", "travel", "more");
    static final Map<String, String> TITLE = Map.of("summon", "召唤", "care", "照顾", "work", "干活", "travel", "随行", "more", "其他");
    static final Map<String, List<String>> PAGES = Map.of(
        "summon", List.of(
            "<gold>① 做一座祭坛</gold>：工作台合成 —— 上下两排「红色羊毛、石英块、红色羊毛」，中间一排「原木、金块、原木」。",
            "<gold>② 攒力量</gold>：打怪有概率掉 <yellow>P点</yellow>，右键吃掉加 30~90 P（100 P = 1.0 力量，最多 5.0）。",
            "<gold>③ 召唤</gold>：把 <white>钻石、青金石、金锭、红石、铁锭、煤</white> 各一个扔在祭坛旁边，右键祭坛，花 0.5 力量得到女仆刷怪蛋。",
            "<gold>④</gold> 拿刷怪蛋右键地面，一位随机衣服的女仆就会认你为主人。",
            "<gray>祭坛还能用力量合成背包、相机、罗盘、饰品等，材料不对时右键没有反应。</gray>"),
        "care", List.of(
            "<gold>右键女仆</gold>：打开她的背包，标题上能看到她的体力和你今天还能让她干多少活。",
            "<gold>潜行右键</gold>：让她坐下 / 站起来。",
            "<gold>吃饭</gold>：她会自己吃背包里的食物来回血、回体力。没食物就干不动活。",
            "<gold>好感度</gold>：越高血越厚、打得越疼、干活越省力。",
            "<gold>倒下了</gold>：原地留下墓碑，右键取回她的东西和一张胶片；把胶片带回祭坛或神社，她就能回来。"),
        "work", List.of(
            "<gold>先设家</gold>：拿河童的罗盘依次右键三个方块（工作、休闲、睡觉位置），再右键女仆。她白天在工作点干活。潜行拿罗盘右键她可以取消。",
            "<gold>种地</gold>：背包里放镰刀或锄头，她会收割工作点 6 格内熟了的作物，并用背包里的种子补种。",
            "<gold>做饭</gold>：工作点旁边放锅、汤锅或蒸笼，下面要有火或灶台；把一道菜的全部食材和碗放进她的背包，她会照菜谱做出来。",
            "<gold>钓鱼</gold>：不用设家，带着背包里有鱼竿的她去钓鱼，你钓上鱼时她偶尔会多钓一条。",
            "<yellow>代价</yellow>：干活消耗她的体力（只能靠吃东西恢复）和工具耐久；你每天的工作量有上限，所有女仆、所有服务器一起算；最多 2 位同时干活；你要在线并在附近她才会干活。"),
        "travel", List.of(
            "<gold>跟着你走</gold>：没有设家的女仆跟着你。你下线或去别的服务器时，她会变成一张照片放进你的背包，到了新的地方自动出来。",
            "<gold>看家的女仆</gold>（设了家的）留在原地，不会跟着走。",
            "<gold>相机</gold>：右键自己的女仆把她拍进照片；照片右键地面就能放出来，背包、好感度都在。"),
        "more", List.of(
            "<gold>换装</gold>：拿着女仆换装券右键她，从全部衣服里挑一件，换好用掉一张。",
            "<gold>背包</gold>：小 / 中 / 大背包右键女仆给她背上，背包变成 18 / 27 / 36 格。",
            "<gold>手办</gold>：照片放副手、凿子右键黏土块雕出雕像，在黏土下点火烧成手办；商店的空白手办放下后随机一位女仆。",
            "<gold>下棋</gold>：放下象棋、国际象棋或五子棋棋盘，和女仆对弈。",
            "<gray>换装券、背包、手办盲盒在星露谷道具商店可以买到。</gray>"));

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String page = a.length > 0 && PAGES.containsKey(a[0]) ? a[0] : "summon";
        s.sendMessage(Component.text("—— 女仆玩法 ——", NamedTextColor.LIGHT_PURPLE));
        Component tabs = Component.empty();
        for (String k : ORDER) {
            boolean cur = k.equals(page);
            tabs = tabs.append(Component.text("[" + TITLE.get(k) + "] ", cur ? NamedTextColor.YELLOW : NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/maid " + k)).hoverEvent(HoverEvent.showText(Component.text("查看" + TITLE.get(k)))));
        }
        s.sendMessage(tabs);
        for (String line : PAGES.get(page)) s.sendMessage(MiniMessage.miniMessage().deserialize(line));
        if (s instanceof Player p)
            s.sendMessage(Component.text(String.format("你的力量 %.2f / %.1f · 今天还能让女仆干 %d", pl.items.power(p), ItemFeatures.POWER_MAX, pl.work.left(p)), NamedTextColor.GRAY));
        return true;
    }
}
