package dev.ua.ikeepcalm.bedwars.net.smp.model;

import lombok.Data;

@Data
public class ScheduleOutlook {

    private final boolean enabled;
    private final boolean eventInFlight;
    private final long millisUntilDue;
    private final int onlineEligible;
    private final int requiredPlayers;

    public boolean isShortfall() {
        return onlineEligible < requiredPlayers;
    }

    public int getMissing() {
        return Math.max(0, requiredPlayers - onlineEligible);
    }

    public long getMinutesUntilDue() {
        return (millisUntilDue + 59_999L) / 60_000L;
    }
}
