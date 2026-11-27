package app.exteraless.plugins;

import org.telegram.messenger.R;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public enum PluginSortMode {

    NAME(R.string.PluginsSortByName, plugins -> byName()),
    INSTALL_DATE(R.string.PluginsSortByInstallDate, plugins -> byFileAttributeDescending(plugins, File::lastModified)),
    SIZE(R.string.PluginsSortBySize, plugins -> byFileAttributeDescending(plugins, File::length)),
    AUTHOR(R.string.PluginsSortByAuthor, plugins -> byAuthor()),
    STATUS(R.string.PluginsSortByStatus, plugins -> enabledFirst());

    public static final PluginSortMode DEFAULT = NAME;

    public final int titleRes;
    private final Function<Collection<Plugin>, Comparator<Plugin>> primaryOrder;

    PluginSortMode(int titleRes, Function<Collection<Plugin>, Comparator<Plugin>> primaryOrder) {
        this.titleRes = titleRes;
        this.primaryOrder = primaryOrder;
    }

    public static PluginSortMode fromName(String name) {
        for (PluginSortMode mode : values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return DEFAULT;
    }

    public Comparator<Plugin> comparator(Collection<Plugin> plugins, boolean reversed) {
        final Comparator<Plugin> order = primaryOrder.apply(plugins).thenComparing(byName());
        return reversed ? order.reversed() : order;
    }

    private static Comparator<Plugin> byName() {
        return (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getDisplayName(), b.getDisplayName());
    }

    private static Comparator<Plugin> byAuthor() {
        return Comparator.comparing(PluginSortMode::authorOf, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    private static Comparator<Plugin> enabledFirst() {
        return (a, b) -> Boolean.compare(b.isEnabled(), a.isEnabled());
    }

    private static Comparator<Plugin> byFileAttributeDescending(Collection<Plugin> plugins, ToLongFunction<File> attribute) {
        final Map<String, Long> values = new HashMap<>();
        for (Plugin plugin : plugins) {
            values.put(plugin.id, fileAttributeOf(plugin, attribute));
        }
        return (a, b) -> Long.compare(values.get(b.id), values.get(a.id));
    }

    private static long fileAttributeOf(Plugin plugin, ToLongFunction<File> attribute) {
        return plugin.path == null ? 0L : attribute.applyAsLong(new File(plugin.path));
    }

    private static String authorOf(Plugin plugin) {
        final String author = plugin.author == null ? "" : plugin.author.trim();
        return author.isEmpty() ? null : author;
    }
}
