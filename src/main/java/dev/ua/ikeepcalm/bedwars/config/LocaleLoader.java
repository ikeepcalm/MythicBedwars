package dev.ua.ikeepcalm.bedwars.config;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.models.source.Language;
import dev.ua.ikeepcalm.bedwars.util.config.LocaleBackfiller;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class LocaleLoader {

    private static final Language DEFAULT_LANGUAGE = Language.EN;
    private static final List<String> BUNDLED_FILES = List.of("lang-en.yml", "lang-uk.yml");

    private final MythicBedwars plugin;
    private volatile Map<Language, FileConfiguration> locales = Map.of();

    public void loadLocales() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        langFolder.mkdirs();

        for (String bundled : BUNDLED_FILES) {
            if (!new File(langFolder, bundled).exists()) {
                plugin.saveResource("lang/" + bundled, false);
            }
            List<String> added = LocaleBackfiller.apply(plugin, bundled);
            if (!added.isEmpty()) {
                plugin.getLogger().info(LocaleBackfiller.describe(bundled, added));
            }
        }

        Map<Language, FileConfiguration> loaded = new EnumMap<>(Language.class);
        File[] files = langFolder.listFiles((dir, name) -> name.startsWith("lang-") && name.endsWith(".yml"));
        for (File file : files == null ? new File[0] : files) {
            Language language = Language.fromFileName(file.getName());
            if (language == null) {
                plugin.log("Ignoring unsupported locale file: {}", file.getName());
                continue;
            }
            loaded.put(language, load(file));
        }
        locales = loaded;
    }

    private FileConfiguration load(File file) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        try (InputStream defaults = plugin.getResource("lang/" + file.getName())) {
            if (defaults != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read the bundled " + file.getName() + ": " + exception.getMessage());
        }
        return config;
    }

    public String getMessage(String key) {
        return getMessage(key, DEFAULT_LANGUAGE);
    }

    public String getMessage(CommandSender recipient, String key) {
        return getMessage(key, localeOf(recipient));
    }

    private String getMessage(String key, Language language) {
        FileConfiguration config = locales.getOrDefault(language, locales.get(DEFAULT_LANGUAGE));
        if (config == null) {
            return "Missing locale: " + language;
        }

        String message = config.getString(key);
        if (message != null) {
            return message;
        }
        return language == DEFAULT_LANGUAGE ? "Missing key: " + key : getMessage(key, DEFAULT_LANGUAGE);
    }

    private List<String> getMessageList(String key, Language language) {
        FileConfiguration config = locales.getOrDefault(language, locales.get(DEFAULT_LANGUAGE));
        if (config == null) {
            return List.of();
        }
        if (!config.isSet(key) && language != DEFAULT_LANGUAGE) {
            return getMessageList(key, DEFAULT_LANGUAGE);
        }
        return config.getStringList(key);
    }

    public Component formatMessage(String key, Object... args) {
        return render(getMessage(key, DEFAULT_LANGUAGE), args);
    }

    public Component formatMessage(CommandSender recipient, String key, Object... args) {
        return render(getMessage(key, localeOf(recipient)), args);
    }

    public List<Component> formatMessageList(String key, Object... args) {
        return formatMessageList(key, DEFAULT_LANGUAGE, args);
    }

    public List<Component> formatMessageList(CommandSender recipient, String key, Object... args) {
        return formatMessageList(key, localeOf(recipient), args);
    }

    private List<Component> formatMessageList(String key, Language language, Object... args) {
        return getMessageList(key, language).stream().map(line -> render(line, args)).toList();
    }

    public Language localeOf(CommandSender recipient) {
        if (recipient instanceof Player player) {
            for (Language language : locales.keySet()) {
                if (language.matches(player.locale())) {
                    return language;
                }
            }
        }
        return DEFAULT_LANGUAGE;
    }

    private Component render(String message, Object... args) {
        String rendered = args.length < 2 ? message : substitute(message, args);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);
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
            int close = open < 0 ? -1 : message.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }

            String value = values.get(message.substring(open + 1, close));
            out.append(message, cursor, open).append(value == null ? message.substring(open, close + 1) : value);
            cursor = close + 1;
        }
        out.append(message, cursor, message.length());
        return out.toString();
    }
}
