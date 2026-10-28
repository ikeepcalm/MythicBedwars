package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import lombok.Getter;
import lombok.Setter;

@Getter
public class Contribution {
    private final long joinedAt = System.currentTimeMillis();

    private volatile int kills;
    private volatile int finalKills;
    private volatile int bedsBroken;
    private volatile int purchases;
    private volatile int activeSeconds;
    private volatile int sampledSeconds;
    private volatile long lastActionAt;
    @Setter
    private volatile boolean forfeit;

    public void addKill(boolean finalKill) {
        if (finalKill) {
            finalKills++;
        } else {
            kills++;
        }
        touch();
    }

    public void addBedBreak() {
        bedsBroken++;
        touch();
    }

    public void addPurchase() {
        purchases++;
        touch();
    }

    public void addSample(boolean active) {
        sampledSeconds++;
        if (active) {
            activeSeconds++;
        }
    }

    public void touch() {
        lastActionAt = System.currentTimeMillis();
    }

    public int actions() {
        return kills + finalKills + bedsBroken + purchases;
    }

    public long millisSinceJoin() {
        return System.currentTimeMillis() - joinedAt;
    }

    public long millisSinceAction() {
        long at = lastActionAt;
        return at == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - at;
    }
}
