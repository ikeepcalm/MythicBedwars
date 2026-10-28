package dev.ua.ikeepcalm.bedwars.domain.stats.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.stats.model.PathwayStats;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SQLiteDatabase {

    private static final String TOTAL_GAMES_KEY = "total_unique_games";

    private final MythicBedwars plugin;
    private final File databaseFile;
    private Connection connection;
    private volatile boolean loadFailed;

    public SQLiteDatabase(MythicBedwars plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "statistics.db");
    }

    public synchronized void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            createTables();
        } catch (ClassNotFoundException | SQLException e) {
            plugin.log("Could not open statistics.db, statistics will not be saved: {}", e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pathway_stats (
                        pathway VARCHAR(64) PRIMARY KEY,
                        wins INTEGER DEFAULT 0,
                        losses INTEGER DEFAULT 0,
                        total_games INTEGER DEFAULT 0,
                        total_damage_dealt REAL DEFAULT 0
                    )
                    """);
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sequences_reached (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        pathway VARCHAR(64),
                        sequence INTEGER,
                        FOREIGN KEY (pathway) REFERENCES pathway_stats(pathway) ON DELETE CASCADE
                    )
                    """);
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS game_durations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        pathway VARCHAR(64),
                        duration_ms BIGINT,
                        FOREIGN KEY (pathway) REFERENCES pathway_stats(pathway) ON DELETE CASCADE
                    )
                    """);
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ability_usage (
                        pathway VARCHAR(64),
                        ability_name VARCHAR(128),
                        usage_count INTEGER DEFAULT 0,
                        PRIMARY KEY (pathway, ability_name),
                        FOREIGN KEY (pathway) REFERENCES pathway_stats(pathway) ON DELETE CASCADE
                    )
                    """);
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS global_stats (
                        key VARCHAR(64) PRIMARY KEY,
                        value BIGINT DEFAULT 0
                    )
                    """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sequences_pathway ON sequences_reached(pathway)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_durations_pathway ON game_durations(pathway)");
        }
    }

    public CompletableFuture<Void> saveStatistics(Map<String, PathwayStats> statistics) {
        return CompletableFuture.runAsync(() -> saveStatisticsNow(statistics));
    }

    public synchronized void saveStatisticsNow(Map<String, PathwayStats> statistics) {
        if (!isConnected()) {
            return;
        }
        if (loadFailed) {
            plugin.log("Skipping statistics save: the stored statistics never loaded, and saving would overwrite them.");
            return;
        }

        try {
            connection.setAutoCommit(false);
            writeAll(statistics);
            connection.commit();
        } catch (SQLException e) {
            plugin.log("Failed to save statistics: {}", e.getMessage());
            rollback();
        } finally {
            resetAutoCommit();
        }
    }

    private void writeAll(Map<String, PathwayStats> statistics) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM pathway_stats");
            stmt.executeUpdate("DELETE FROM sequences_reached");
            stmt.executeUpdate("DELETE FROM game_durations");
            stmt.executeUpdate("DELETE FROM ability_usage");
        }

        try (PreparedStatement statsStmt = connection.prepareStatement(
                "INSERT INTO pathway_stats (pathway, wins, losses, total_games, total_damage_dealt) VALUES (?, ?, ?, ?, ?)");
             PreparedStatement seqStmt = connection.prepareStatement(
                     "INSERT INTO sequences_reached (pathway, sequence) VALUES (?, ?)");
             PreparedStatement durStmt = connection.prepareStatement(
                     "INSERT INTO game_durations (pathway, duration_ms) VALUES (?, ?)");
             PreparedStatement abilityStmt = connection.prepareStatement(
                     "INSERT INTO ability_usage (pathway, ability_name, usage_count) VALUES (?, ?, ?)")) {

            for (Map.Entry<String, PathwayStats> entry : statistics.entrySet()) {
                String pathway = entry.getKey();
                PathwayStats stats = entry.getValue();

                statsStmt.setString(1, pathway);
                statsStmt.setInt(2, stats.getWins());
                statsStmt.setInt(3, stats.getLosses());
                statsStmt.setInt(4, stats.getTotalGames());
                statsStmt.setDouble(5, stats.getTotalDamageDealt());
                statsStmt.addBatch();

                for (int sequence : stats.getSequencesReached()) {
                    seqStmt.setString(1, pathway);
                    seqStmt.setInt(2, sequence);
                    seqStmt.addBatch();
                }
                for (long duration : stats.getGameDurations()) {
                    durStmt.setString(1, pathway);
                    durStmt.setLong(2, duration);
                    durStmt.addBatch();
                }
                for (Map.Entry<String, Integer> ability : stats.getAbilityUsage().entrySet()) {
                    abilityStmt.setString(1, pathway);
                    abilityStmt.setString(2, ability.getKey());
                    abilityStmt.setInt(3, ability.getValue());
                    abilityStmt.addBatch();
                }
            }
            statsStmt.executeBatch();
            seqStmt.executeBatch();
            durStmt.executeBatch();
            abilityStmt.executeBatch();
        }

        try (PreparedStatement stmt = connection.prepareStatement("INSERT OR REPLACE INTO global_stats (key, value) VALUES (?, ?)")) {
            stmt.setString(1, TOTAL_GAMES_KEY);
            stmt.setLong(2, plugin.getStatisticsManager().totalGames());
            stmt.executeUpdate();
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            plugin.log("Failed to roll back statistics save: {}", e.getMessage());
        }
    }

    private void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            plugin.log("Failed to reset auto-commit: {}", e.getMessage());
        }
    }

    public CompletableFuture<Map<String, PathwayStats>> loadStatistics() {
        return CompletableFuture.supplyAsync(this::loadStatisticsNow);
    }

    private synchronized Map<String, PathwayStats> loadStatisticsNow() {
        Map<String, PathwayStats> result = new HashMap<>();
        if (!isConnected()) {
            return result;
        }

        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM pathway_stats")) {
                while (rs.next()) {
                    PathwayStats stats = new PathwayStats();
                    stats.setWins(rs.getInt("wins"));
                    stats.setLosses(rs.getInt("losses"));
                    stats.setTotalGames(rs.getInt("total_games"));
                    stats.setTotalDamageDealt(rs.getDouble("total_damage_dealt"));
                    result.put(rs.getString("pathway"), stats);
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT pathway, sequence FROM sequences_reached")) {
                while (rs.next()) {
                    PathwayStats stats = result.get(rs.getString("pathway"));
                    if (stats != null) {
                        stats.getSequencesReached().add(rs.getInt("sequence"));
                    }
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT pathway, duration_ms FROM game_durations")) {
                while (rs.next()) {
                    PathwayStats stats = result.get(rs.getString("pathway"));
                    if (stats != null) {
                        stats.getGameDurations().add(rs.getLong("duration_ms"));
                    }
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT pathway, ability_name, usage_count FROM ability_usage")) {
                while (rs.next()) {
                    PathwayStats stats = result.get(rs.getString("pathway"));
                    if (stats != null) {
                        stats.getAbilityUsage().put(rs.getString("ability_name"), rs.getInt("usage_count"));
                    }
                }
            }
        } catch (SQLException e) {
            loadFailed = true;
            plugin.log("Failed to load statistics, saving is disabled to protect them: {}", e.getMessage());
        }
        return result;
    }

    public synchronized long loadTotalGames() {
        if (!isConnected()) {
            return 0;
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT value FROM global_stats WHERE key = ?")) {
            stmt.setString(1, TOTAL_GAMES_KEY);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            plugin.log("Failed to load the total game count: {}", e.getMessage());
            return 0;
        }
    }

    public synchronized void close() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.log("Failed to close statistics.db: {}", e.getMessage());
        }
    }

    public synchronized boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}
