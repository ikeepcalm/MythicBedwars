package dev.ua.ikeepcalm.mythicBedwars.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LocaleLoader {

    private final JavaPlugin plugin;
    private final Locale defaultLocale;
    private final Map<Locale, FileConfiguration> locales;

    public LocaleLoader(JavaPlugin plugin, Locale defaultLocale) {
        this.plugin = plugin;
        this.defaultLocale = defaultLocale;
        this.locales = new HashMap<>();
    }

    public void loadLocales() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

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

                locales.put(Locale.valueOf(localeName.toUpperCase()), config);
                if (plugin instanceof dev.ua.ikeepcalm.mythicBedwars.MythicBedwars mythicBedwars) {
                    mythicBedwars.log("Loaded locale: " + localeName);
                }
            }
        }
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

    public Component formatMessage(String key, Object... args) {
        return formatMessage(key, defaultLocale, args);
    }

    public Component formatMessage(String key, Locale locale, Object... args) {
        String message = getMessage(key, locale);
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                String placeholder = "{" + args[i] + "}";
                String value = String.valueOf(args[i + 1]);
                message = message.replace(placeholder, value);
            }
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }

    public enum Locale {
         EN,
        UK
    }
}