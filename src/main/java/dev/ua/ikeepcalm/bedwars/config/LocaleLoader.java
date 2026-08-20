package dev.ua.ikeepcalm.bedwars.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocaleLoader {

    private final JavaPlugin plugin;
    private final Locale defaultLocale;
    private final Map<Locale, FileConfiguration> locales;

    public LocaleLoader(JavaPlugin plugin, Locale defaultLocale) {
        this.plugin = plugin;
        this.defaultLocale = defaultLocale;
        this.locales = new java.util.concurrent.ConcurrentHashMap<>();
    }

    private static String substitute(String message, Object... args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            values.put(String.valueOf(args[i]), String.valueOf(args[i + 1]));
        }

        StringBuilder out = new StringBuilder(message.length() + 16);
        int cursor = 0;

        while (cursor < message.length()) {
            int open = message.indexOf('{', cursor);
            if (open < 0) {
                break;
            }

            int close = message.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }

            String name = message.substring(open + 1, close);
            String value = values.get(name);

            out.append(message, cursor, open);
            // An unknown placeholder is left exactly as written, so a missing argument is visible
            // rather than silently blanking part of the sentence.
            out.append(value == null ? message.substring(open, close + 1) : value);
            cursor = close + 1;
        }

        out.append(message, cursor, message.length());
        return out.toString();
    }

    private void saveDefaultLocale(String fileName) {
        File localeFile = new File(plugin.getDataFolder() + "/lang", fileName);
        if (!localeFile.exists()) {
            plugin.saveResource("lang/" + fileName, false);
        }
    }

    public String getMessage(String key) {
        return getMessage(key, defaultLocale);
    }

    public String getMessage(String key, Locale locale) {
        FileConfiguration config = locales.getOrDefault(locale, locales.get(defaultLocale));
        if (config == null) {
            return "Missing locale: " + locale;
        }

        String message = config.getString(key);
        if (message == null) {
            if (!locale.equals(defaultLocale)) {
                return getMessage(key, defaultLocale);
            }
            return "Missing key: " + key;
        }

        return message;
    }

    public void loadLocales() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // Cleared so /mb reload can drop a locale file that has been removed.
        locales.clear();

        saveDefaultLocale("lang-en.yml");
        saveDefaultLocale("lang-uk.yml");

        File[] localeFiles = langFolder.listFiles((dir, name) -> name.startsWith("lang-") && name.endsWith(".yml"));
        if (localeFiles != null) {
            for (File file : localeFiles) {
                String localeName = file.getName().replace("lang-", "").replace(".yml", "");
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);

                InputStream defaultStream = plugin.getResource("lang/" + file.getName());
                if (defaultStream != null) {
                    YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
                    config.setDefaults(defaultConfig);
                }

                Locale parsed;
                try {
                    parsed = Locale.valueOf(localeName.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    // Somebody dropped a lang-de.yml in. Skip it rather than failing every other
                    // locale along with it.
                    if (plugin instanceof dev.ua.ikeepcalm.bedwars.MythicBedwars mythicBedwars) {
                        mythicBedwars.log("Ignoring unsupported locale file: {}", file.getName());
                    }
                    continue;
                }

                locales.put(parsed, config);
                if (plugin instanceof dev.ua.ikeepcalm.bedwars.MythicBedwars mythicBedwars) {
                    mythicBedwars.log("Loaded locale: " + localeName);
                }
            }
        }
    }

    /**
     * Reads a key holding a list of lines, for multi-line copy such as the event announcement.
     *
     * @return an empty list when the key is absent or not a list, so an operator can switch a whole
     * section off simply by emptying it
     */
    public List<String> getMessageList(String key, Locale locale) {
        FileConfiguration config = locales.getOrDefault(locale, locales.get(defaultLocale));
        if (config == null) {
            return List.of();
        }

        // Fall back only when this locale does not define the key at all. An explicitly emptied
        // list is an operator switching that section off, and must not resurrect the English one.
        if (!config.isSet(key) && !locale.equals(defaultLocale)) {
            return getMessageList(key, defaultLocale);
        }
        return config.getStringList(key);
    }

    /**
     * List counterpart of {@link #formatMessage}, with the same {@code {name}} placeholders applied
     * to every line.
     */
    public List<Component> formatMessageList(String key, Object... args) {
        return formatMessageList(key, defaultLocale, args);
    }

    public List<Component> formatMessageList(String key, Locale locale, Object... args) {
        List<String> lines = getMessageList(key, locale);
        List<Component> formatted = new ArrayList<>(lines.size());
        for (String line : lines) {
            formatted.add(render(line, args));
        }
        return formatted;
    }

    public Component formatMessage(String key, Object... args) {
        return formatMessage(key, defaultLocale, args);
    }

    public Component formatMessage(String key, Locale locale, Object... args) {
        return render(getMessage(key, locale), args);
    }

    /**
     * List counterpart resolved for one recipient.
     */
    public List<Component> formatMessageList(CommandSender recipient, String key, Object... args) {
        return formatMessageList(key, localeOf(recipient), args);
    }

    /**
     * Formats a message in the recipient's own language.
     *
     * <p>Without this every lookup resolved to the default locale, which meant the shipped
     * {@code lang-uk.yml} was never read by anything — a whole translation that existed on disk and
     * could not be reached. Prefer this overload anywhere the recipient is known.
     */
    public Component formatMessage(CommandSender recipient, String key, Object... args) {
        return formatMessage(key, localeOf(recipient), args);
    }

    /**
     * Raw string in the recipient's own language, for the few places that need to compose one.
     */
    public String getMessage(CommandSender recipient, String key) {
        return getMessage(key, localeOf(recipient));
    }

    /**
     * Maps a recipient onto a shipped locale.
     *
     * @return the client's language when there is a translation for it, otherwise the default.
     * Console and command blocks always get the default.
     */
    public Locale localeOf(CommandSender recipient) {
        if (!(recipient instanceof Player player)) {
            return defaultLocale;
        }

        String language = player.locale().getLanguage();
        for (Locale candidate : Locale.values()) {
            if (candidate.name().equalsIgnoreCase(language) && locales.containsKey(candidate)) {
                return candidate;
            }
        }

        return defaultLocale;
    }

    /**
     * Substitutes {@code {name}} placeholders in a single pass.
     *
     * <p>Repeated {@code String.replace} calls would let a substituted <em>value</em> that happens to
     * contain a later placeholder be substituted in turn — so a player-supplied name or an arena id
     * could rewrite the rest of the line.
     */
    private Component render(String message, Object... args) {
        String rendered = args.length < 2 ? message : substitute(message, args);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);
    }

    /**
     * The shipped translations. Names double as ISO 639-1 language codes, which is how
     * {@link #localeOf} matches a player's client language.
     */
    public enum Locale {
        EN,
        UK
    }
}