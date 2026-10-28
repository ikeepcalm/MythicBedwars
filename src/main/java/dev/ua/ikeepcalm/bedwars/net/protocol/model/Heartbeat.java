package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import lombok.Data;

@Data
public class Heartbeat {

    private final String serverId;
    private final NetworkRole role;
    private final String velocityServer;
    private final int onlinePlayers;
    private final String pluginVersion;
    private final long ts;

    public boolean isFresh(long now, long staleAfterMillis) {
        return now - ts < staleAfterMillis;
    }
}
