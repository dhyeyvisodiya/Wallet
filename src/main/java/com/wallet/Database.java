package com.wallet;

import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private static final String DEFAULT_DB_PATH = "wallet.db";
    private final String jdbcUrl;
    private final SQLiteConfig config;

    public Database() {
        this(DEFAULT_DB_PATH);
    }

    public Database(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.config = new SQLiteConfig();
        this.config.enforceForeignKeys(true);
        this.config.setBusyTimeout(5000);
        this.config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        this.config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, config.toProperties());
    }

    public void initializeSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Enable WAL mode for better concurrency
            stmt.execute("PRAGMA journal_mode = WAL;");

            // Accounts table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    owner_name TEXT NOT NULL,
                    balance_paise INTEGER NOT NULL CHECK (balance_paise >= 0),
                    created_at TEXT NOT NULL
                );
            """);

            // Transfers table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transfers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_id INTEGER NOT NULL,
                    to_id INTEGER NOT NULL,
                    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                    reference TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
                    reason TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (from_id) REFERENCES accounts(id),
                    FOREIGN KEY (to_id) REFERENCES accounts(id)
                );
            """);

            // Index for faster history queries
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_transfers_from_id ON transfers(from_id);
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_transfers_to_id ON transfers(to_id);
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_transfers_history ON transfers(created_at, id);
            """);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema: " + e.getMessage(), e);
        }
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }
}
