package dev.ua.ikeepcalm.bedwars.domain.reward.service;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.ClaimOutcome;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
public class RewardQueue {

    private static final String EMIT = """
            if redis.call('SADD', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            redis.call('RPUSH', KEYS[2], ARGV[2])
            redis.call('LTRIM', KEYS[2], -tonumber(ARGV[4]), -1)
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
            return redis.call('LLEN', KEYS[2])
            """;

    private static final String CLAIM = """
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', tonumber(ARGV[1])) then return 1 end
            return 0
            """;

    private static final String RETURN = """
            redis.call('DEL', KEYS[1])
            redis.call('LPUSH', KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
            return 1
            """;

    private static final String COUNT_TODAY = """
            local n = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], 172800)
            return n
            """;

    private final MythicBedwars plugin;
    private final RedisClient client;
    private final RedisKeys keys;
    private final RewardConfig config;
    private final Gson gson = new Gson();

    public boolean emit(RewardBundle bundle) {
        long result = client.evalLong(EMIT,
                List.of(keys.rewardsGranted(bundle.getEventId()), keys.rewardsPending(bundle.getPlayerId())),
                List.of(bundle.getPlayerId().toString(),
                        gson.toJson(bundle),
                        Integer.toString(config.queueTtlSeconds()),
                        Integer.toString(config.maxQueuedBundles())),
                -1L);

        if (result < 0) {
            plugin.log("Could not queue rewards for {} - Redis unavailable.", bundle.getPlayerName());
            return false;
        }
        return result > 0;
    }

    public Optional<RewardBundle> poll(UUID playerId) {
        Optional<String> raw = client.lpop(keys.rewardsPending(playerId));
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        try {
            RewardBundle bundle = gson.fromJson(raw.get(), RewardBundle.class);
            if (bundle == null || bundle.getSchema() != RewardBundle.SCHEMA) {
                quarantine(raw.get(), "unsupported schema");
                return Optional.empty();
            }
            return Optional.of(bundle);
        } catch (JsonSyntaxException exception) {
            quarantine(raw.get(), exception.getMessage());
            return Optional.empty();
        }
    }

    public ClaimOutcome claim(RewardBundle bundle) {
        long result = client.evalLong(CLAIM,
                List.of(keys.rewardsClaimed(bundle.getPlayerId(), bundle.getEventId())),
                List.of(Integer.toString(config.queueTtlSeconds())),
                -1L);
        if (result < 0) {
            return ClaimOutcome.UNAVAILABLE;
        }
        return result > 0 ? ClaimOutcome.CLAIMED : ClaimOutcome.ALREADY_APPLIED;
    }

    public void returnToQueue(RewardBundle bundle) {
        client.evalLong(RETURN,
                List.of(keys.rewardsClaimed(bundle.getPlayerId(), bundle.getEventId()),
                        keys.rewardsPending(bundle.getPlayerId())),
                List.of(gson.toJson(bundle), Integer.toString(config.queueTtlSeconds())),
                -1L);
    }

    public long bundlesToday(UUID playerId, String day) {
        return client.get(keys.rewardsDailyCount(playerId, day))
                .map(raw -> {
                    try {
                        return Long.parseLong(raw.trim());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    public void recordBundleToday(UUID playerId, String day) {
        client.evalLong(COUNT_TODAY, List.of(keys.rewardsDailyCount(playerId, day)), List.of(), 0L);
    }

    private void quarantine(String payload, String reason) {
        plugin.log("Quarantining unreadable reward payload ({}).", String.valueOf(reason));
        client.rpushCapped(keys.rewardsDeadLetter(), payload, 200);
    }
}
