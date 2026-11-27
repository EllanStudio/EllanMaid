package studio.ellan.maid;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Player text in the server's palette (same as the tractor): {dim} brackets, {text} body, {label} labels,
 * {gold} numbers and names, {good} success, {bad} warnings, {key} actions, {muted} notes.
 * Plain numbers in a message are highlighted in gold automatically.
 */
final class Msg {
    static final Map<String, String> P = Map.of(
        "{dim}", "<#68766E>", "{text}", "<#E8EEE9>", "{label}", "<#b7afa0>", "{gold}", "<#e7c36a>", "{aqua}", "<#73c9e8>",
        "{good}", "<#8fcf77>", "{bad}", "<#d98e8e>", "{key}", "<#f1d58a>", "{muted}", "<#8f887c>");
    static final String PREFIX = "{dim}[{gold}女仆{dim}] ";
    static final Pattern NUM = Pattern.compile("(?<![#\\w])(\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?)?)");

    private Msg() {}

    static String colors(String s) { for (var e : P.entrySet()) s = s.replace(e.getKey(), e.getValue()); return s; }

    /** a template in the palette; base colour {text}, numbers gold */
    static Component c(String s) { return build("{text}", s); }
    /** a warning */
    static Component bad(String s) { return build("{bad}", s); }
    /** a success */
    static Component good(String s) { return build("{good}", s); }
    /** chat line with the [女仆] prefix */
    static Component p(String s) { return MiniMessage.miniMessage().deserialize(colors(PREFIX)).append(c(s)); }
    static Component pBad(String s) { return MiniMessage.miniMessage().deserialize(colors(PREFIX)).append(bad(s)); }
    /** lore / GUI line (no italics) */
    static Component lore(String s) { return build("{label}", s).decoration(TextDecoration.ITALIC, false); }

    static Component build(String base, String s) {
        String body = s.indexOf('{') >= 0 || s.indexOf('<') >= 0 ? s : NUM.matcher(s).replaceAll("{gold}$1" + base);
        return MiniMessage.miniMessage().deserialize(colors(base + body));
    }
}
