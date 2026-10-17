package com.wallet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WalletRepository {
    private final Database database;

    public WalletRepository(Database database) {
        this.database = database;
    }

    public Database getDatabase() {
        return database;
    }

    public long createAccount(Connection conn, String ownerName, long initialBalancePaise, String createdAt) throws SQLException {
        String sql = "INSERT INTO accounts (owner_name, balance_paise, created_at) VALUES (?, ?, ?);";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, ownerName);
            pstmt.setLong(2, initialBalancePaise);
            pstmt.setString(3, createdAt);
            pstmt.executeUpdate();

            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                } else {
                    throw new SQLException("Account creation failed, no generated ID obtained.");
                }
            }
        }
    }

    public Optional<Account> findAccountById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, owner_name, balance_paise, created_at FROM accounts WHERE id = ?;";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Account account = new Account(
                            rs.getLong("id"),
                            rs.getString("owner_name"),
                            rs.getLong("balance_paise"),
                            rs.getString("created_at")
                    );
                    return Optional.of(account);
                }
                return Optional.empty();
            }
        }
    }

    public void updateAccountBalance(Connection conn, long id, long newBalancePaise) throws SQLException {
        String sql = "UPDATE accounts SET balance_paise = ? WHERE id = ?;";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, newBalancePaise);
            pstmt.setLong(2, id);
            int updated = pstmt.executeUpdate();
            if (updated == 0) {
                throw new SQLException("Failed to update balance for account id " + id);
            }
        }
    }

    public Optional<Transfer> findTransferByReference(Connection conn, String reference) throws SQLException {
        String sql = """
            SELECT id, from_id, to_id, amount_paise, reference, status, reason, created_at
            FROM transfers
            WHERE reference = ?;
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, reference);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Transfer transfer = new Transfer(
                            rs.getLong("id"),
                            rs.getLong("from_id"),
                            rs.getLong("to_id"),
                            rs.getLong("amount_paise"),
                            rs.getString("reference"),
                            rs.getString("status"),
                            rs.getString("reason"),
                            rs.getString("created_at")
                    );
                    return Optional.of(transfer);
                }
                return Optional.empty();
            }
        }
    }

    public Transfer insertTransfer(Connection conn, long fromId, long toId, long amountPaise,
                                  String reference, String status, String reason, String createdAt) throws SQLException {
        String sql = """
            INSERT INTO transfers (from_id, to_id, amount_paise, reference, status, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?);
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, fromId);
            pstmt.setLong(2, toId);
            pstmt.setLong(3, amountPaise);
            pstmt.setString(4, reference);
            pstmt.setString(5, status);
            if (reason != null) {
                pstmt.setString(6, reason);
            } else {
                pstmt.setNull(6, Types.VARCHAR);
            }
            pstmt.setString(7, createdAt);
            pstmt.executeUpdate();

            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    long id = generatedKeys.getLong(1);
                    return new Transfer(id, fromId, toId, amountPaise, reference, status, reason, createdAt);
                } else {
                    throw new SQLException("Inserting transfer failed, no generated ID obtained.");
                }
            }
        }
    }

    public List<Transfer> getTransferHistory(Connection conn, long accountId, int limit) throws SQLException {
        String sql = """
            SELECT id, from_id, to_id, amount_paise, reference, status, reason, created_at
            FROM transfers
            WHERE from_id = ? OR to_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?;
        """;
        List<Transfer> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, accountId);
            pstmt.setLong(2, accountId);
            pstmt.setInt(3, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new Transfer(
                            rs.getLong("id"),
                            rs.getLong("from_id"),
                            rs.getLong("to_id"),
                            rs.getLong("amount_paise"),
                            rs.getString("reference"),
                            rs.getString("status"),
                            rs.getString("reason"),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return list;
    }
}
