package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RewardBundle {
    public static final int SCHEMA = 2;

    private final int schema;
    private final String eventId;
    private final String arena;
    private final UUID playerId;
    private final String playerName;
    private final String eventPathway;
    private final boolean won;
    private final long earnedAtEpochMs;
    private final List<RewardGrant> grants;
}
