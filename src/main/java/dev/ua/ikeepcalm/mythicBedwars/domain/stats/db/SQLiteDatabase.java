package dev.ua.ikeepcalm.mythicBedwars.domain.stats.db;

import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SQLiteDatabase {

    private final MythicBedwars plugin;
    private final File databaseFile;
    private Connection connection;

    public SQLiteDatabase(MythicBedwars plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "statistics.db");
    }

    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            createTables();
            plugin.log("SQLite database initialized.");
        } catch (Exception e) {
            plugin.log("Failed to initialize SQLite database: " + e.getMessage());
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
        return saveStatistics(statistics, false);
    }

    public CompletableFuture<Void> saveStatistics(Map<String, PathwayStats> statistics, boolean isShuttingDown) {
        if (isShuttingDown) {
            saveStatisticsSync(statistics);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                if (connection == null || connection.isClosed()) {
                    plugin.log("Database connection is not available for async save.");
                    return;
                }
                connection.setAutoCommit(false);

                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("DELETE FROM pathway_stats");
                    stmt.executeUpdate("DELETE FROM sequences_reached");
                    stmt.executeUpdate("DELETE FROM game_durations");
                    stmt.executeUpdate("DELETE FROM ability_usage");
                }

                String insertStats = "INSERT INTO pathway_stats (pathway, wins, losses, total_games, total_damage_dealt) VALUES (?, ?, ?, ?, ?)";
                String insertSequence = "INSERT INTO sequences_reached (pathway, sequence) VALUES (?, ?)";
                String insertDuration = "INSERT INTO game_durations (pathway, duration_ms) VALUES (?, ?)";
                String insertAbility = "INSERT INTO ability_usage (pathway, ability_name, usage_count) VALUES (?, ?, ?)";

                try (PreparedStatement statsStmt = connection.prepareStatement(insertStats);
                     PreparedStatement seqStmt = connection.prepareStatement(insertSequence);
                     PreparedStatement durStmt = connection.prepareStatement(insertDuration);
                     PreparedStatement abilityStmt = connection.prepareStatement(insertAbility)) {

                    for (Map.Entry<String, PathwayStats> entry : statistics.entrySet()) {
                        String pathway = entry.getKey();
                        PathwayStats statsData = entry.getValue();

                        statsStmt.setString(1, pathway);
                        statsStmt.setInt(2, statsData.wins);
                        statsStmt.setInt(3, statsData.losses);
                        statsStmt.setInt(4, statsData.totalGames);
                        statsStmt.setDouble(5, statsData.totalDamageDealt);
                        statsStmt.addBatch();

                        for (Integer sequence : statsData.sequencesReached) {
                            seqStmt.setString(1, pathway);
                            seqStmt.setInt(2, sequence);
                            seqStmt.addBatch();
                        }

                        for (Long duration : statsData.gameDurations) {
                            durStmt.setString(1, pathway);
                            durStmt.setLong(2, duration);
                            durStmt.addBatch();
                        }

                        for (Map.Entry<String, Integer> ability : statsData.abilityUsage.entrySet()) {
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

                saveGlobalStats();

                connection.commit();
                plugin.log("Statistics saved asynchronously to SQLite database.");

            } catch (SQLException e) {
                plugin.log("Failed to save statistics asynchronously: " + e.getMessage());
                try {
                    if (connection != null && !connection.isClosed() && !connection.getAutoCommit()) {
                        connection.rollback();
                        plugin.log("Transaction rolled back due to async save failure.");
                    }
                } catch (SQLException ex) {
                    plugin.log("Failed to rollback transaction in async save: " + ex.getMessage());
                }
            } finally {
                try {
                    if (connection != null && !connection.isClosed()) {
                        connection.setAutoCommit(true);
                    }
                } catch (SQLException e) {
                    plugin.log("Failed to reset auto-commit in async save: " + e.getMessage());
                }
            }
        });
    }

    private void saveStatisticsSync(Map<String, PathwayStats> statistics) {
        try {
            if (connection == null || connection.isClosed()) {
                plugin.log("Database connection is not available for sync save.");
                return;
            }
            connection.setAutoCommit(false);

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM pathway_stats");
                stmt.executeUpdate("DELETE FROM sequences_reached");
                stmt.executeUpdate("DELETE FROM game_durations");
                stmt.executeUpdate("DELETE FROM ability_usage");
            }

            String insertStats = "INSERT INTO pathway_stats (pathway, wins, losses, total_games, total_damage_dealt) VALUES (?, ?, ?, ?, ?)";
            String insertSequence = "INSERT INTO sequences_reached (pathway, sequence) VALUES (?, ?)";
            String insertDuration = "INSERT INTO game_durations (pathway, duration_ms) VALUES (?, ?)";
            String insertAbility = "INSERT INTO ability_usage (pathway, ability_name, usage_count) VALUES (?, ?, ?)";

            try (PreparedStatement statsStmt = connection.prepareStatement(insertStats);
                 PreparedStatement seqStmt = connection.prepareStatement(insertSequence);
                 PreparedStatement durStmt = connection.prepareStatement(insertDuration);
                 PreparedStatement abilityStmt = connection.prepareStatement(insertAbility)) {

                for (Map.Entry<String, PathwayStats> entry : statistics.entrySet()) {
                    String pathway = entry.getKey();
                    PathwayStats statsData = entry.getValue();

                    statsStmt.setString(1, pathway);
                    statsStmt.setInt(2, statsData.wins);
                    statsStmt.setInt(3, statsData.losses);
                    statsStmt.setInt(4, statsData.totalGames);
                    statsStmt.setDouble(5, statsData.totalDamageDealt);
                    statsStmt.addBatch();

                    for (Integer sequence : statsData.sequencesReached) {
                        seqStmt.setString(1, pathway);
                        seqStmt.setInt(2, sequence);
                        seqStmt.addBatch();
                    }

                    for (Long duration : statsData.gameDurations) {
                        durStmt.setString(1, pathway);
                        durStmt.setLong(2, duration);
                        durStmt.addBatch();
                    }

                    for (Map.Entry<String, Integer> ability : statsData.abilityUsage.entrySet()) {
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

            saveGlobalStats();

            connection.commit();
            plugin.log("Statistics saved to SQLite database.");
        } catch (SQLException e) {
            plugin.log("Failed to save statistics: " + e.getMessage());
            try {
                if (connection != null && !connection.isClosed() && !connection.getAutoCommit()) {
                    connection.rollback();
                    plugin.log("Transaction rolled back due to sync save failure.");
                }
            } catch (SQLException ex) {
                plugin.log("Failed to rollback transaction: " + ex.getMessage());
            }
        } finally {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.log("Failed to reset auto-commit: " + e.getMessage());
            }
        }
    }

    private void saveGlobalStats() throws SQLException {
        String upsertGlobal = "INSERT OR REPLACE INTO global_stats (key, value) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(upsertGlobal)) {
            long totalGames = plugin.getStatisticsManager().totalGames();
            stmt.setString(1, "total_unique_games");
            stmt.setLong(2, totalGames);
            stmt.executeUpdate();
        }
    }

    public CompletableFuture<Map<String, PathwayStats>> loadStatistics() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, PathwayStats> result = new HashMap<>();
            try {
                if (connection == null || connection.isClosed()) {
                    plugin.log("Database connection is not available for loading statistics.");
                    return result;
                }

                String selectStats = "SELECT * FROM pathway_stats";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(selectStats)) {
                    while (rs.next()) {
                        String pathway = rs.getString("pathway");
                        PathwayStats stats = new PathwayStats();
                        stats.wins = rs.getInt("wins");
                        stats.losses = rs.getInt("losses");
                        stats.totalGames = rs.getInt("total_games");
                        stats.totalDamageDealt = rs.getDouble("total_damage_dealt");
                        result.put(pathway, stats);
                    }
                }

                String selectSequences = "SELECT pathway, sequence FROM sequences_reached";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(selectSequences)) {
                    while (rs.next()) {
                        String pathway = rs.getString("pathway");
                        int sequence = rs.getInt("sequence");
                        PathwayStats stats = result.get(pathway);
                        if (stats != null) {
                            stats.sequencesReached.add(sequence);
                        }
                    }
                }

                String selectDurations = "SELECT pathway, duration_ms FROM game_durations";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(selectDurations)) {
                    while (rs.next()) {
                        String pathway = rs.getString("pathway");
                        long duration = rs.getLong("duration_ms");
                        PathwayStats stats = result.get(pathway);
                        if (stats != null) {
                            stats.gameDurations.add(duration);
                        }
                    }
                }

                String selectAbilities = "SELECT pathway, ability_name, usage_count FROM ability_usage";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(selectAbilities)) {
                    while (rs.next()) {
                        String pathway = rs.getString("pathway");
                        String abilityName = rs.getString("ability_name");
                        int count = rs.getInt("usage_count");
                        PathwayStats stats = result.get(pathway);
                        if (stats != null) {
                            stats.abilityUsage.put(abilityName, count);
                        }
                    }
                }
                plugin.log("Loaded " + result.size() + " pathway statistics from SQLite.");
            } catch (SQLException e) {
                plugin.log("Failed to load statistics: " + e.getMessage());
            }
            return result;
        });
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.log("SQLite database connection closed.");
            }
        } catch (SQLException e) {
            plugin.log("Failed to close database connection: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            plugin.log("Error checking SQLite connection status: " + e.getMessage());
            return false;
        }
    }
}