package dev.ua.ikeepcalm.bedwars.config.models.source;

import java.util.Locale;

public enum Language {
    EN,
    UK;

    public static Language fromFileName(String fileName) {
        String code = fileName.substring("lang-".length(), fileName.length() - ".yml".length());
        for (Language language : values()) {
            if (language.name().equalsIgnoreCase(code)) {
                return language;
            }
        }
        return null;
    }

    public boolean matches(Locale locale) {
        return name().equalsIgnoreCase(locale.getLanguage());
    }
}
