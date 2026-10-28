package dev.ua.ikeepcalm.bedwars.domain.pathway.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@RequiredArgsConstructor
public class PlayerMagicData {
    private final UUID playerId;
    private final String pathway;
    private final String arenaName;
    private final Map<Integer, Integer> potionsPurchased = new HashMap<>();
    private int currentSequence = 9;
    private boolean active = true;
    private int storedActing;
    private int materialPurchaseCount;
    private long gameStartTime = System.currentTimeMillis();
    private long totalPlayTime;

    public void incrementPotionPurchase(int sequence) {
        potionsPurchased.merge(sequence, 1, Integer::sum);
    }

    public int getPotionPurchaseCount(int sequence) {
        return potionsPurchased.getOrDefault(sequence, 0);
    }

    public void incrementMaterialPurchase() {
        materialPurchaseCount++;
    }

    public void updatePlayTimeOnDisconnect() {
        if (active && gameStartTime > 0) {
            totalPlayTime += System.currentTimeMillis() - gameStartTime;
        }
    }

    public void resetGameStartTimeOnReconnect() {
        gameStartTime = System.currentTimeMillis();
    }

    public long getEffectivePlayTime() {
        if (active && gameStartTime > 0) {
            return totalPlayTime + (System.currentTimeMillis() - gameStartTime);
        }
        return totalPlayTime;
    }
}
