package dev.ua.ikeepcalm.bedwars.util.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class Completions {

    public static List<String> matching(Collection<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
