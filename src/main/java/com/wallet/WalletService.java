package com.wallet;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public class WalletService {
    private final WalletRepository repository;
    private final Database database;
    private final ReentrantLock transferLock = new ReentrantLock();

    public WalletService(WalletRepository repository) {
        this.repository = repository;
        this.database = repository.getDatabase();
    }

    public Account openAccount(String ownerName, String openingBalanceStr) {
        if (ownerName == null || ownerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Error: owner name cannot be empty.");
        }
        long initialBalancePaise = parseAmountToPaise(openingBalanceStr, false);

        String now = Instant.now().toString();
        try (Connection conn = database.getConnection()) {
            long id = repository.createAccount(conn, ownerName.trim(), initialBalancePaise, now);
            return new Account(id, ownerName.trim(), initialBalancePaise, now);
        } catch (SQLException e) {
            throw new RuntimeException("Database error creating account: " + e.getMessage(), e);
        }
    }

    public Account getBalance(long accountId) {
        try (Connection conn = database.getConnection()) {
            return repository.findAccountById(conn, accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Error: account " + accountId + " does not exist."));
        } catch (SQLException e) {
            throw new RuntimeException("Database error checking balance: " + e.getMessage(), e);
        }
    }

    public List<Transfer> getHistory(long accountId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Error: history limit must be a positive integer.");
        }
        // Verify account exists
        getBalance(accountId);

        try (Connection conn = database.getConnection()) {
            return repository.getTransferHistory(conn, accountId, limit);
        } catch (SQLException e) {
            throw new RuntimeException("Database error retrieving history: " + e.getMessage(), e);
        }
    }

    public Transfer transfer(long fromId, long toId, String amountStr, String reference) {
        long amountPaise = parseAmountToPaise(amountStr, true);
        return transfer(fromId, toId, amountPaise, reference);
    }

    public Transfer transfer(long fromId, long toId, long amountPaise, String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("Error: reference cannot be empty.");
        }
        String ref = reference.trim();

        if (fromId == toId) {
            throw new IllegalArgumentException("Error: sender and receiver must be different.");
        }
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Error: amount must be greater than zero.");
        }

        // Fast-path idempotency check before locking
        try (Connection conn = database.getConnection()) {
            Optional<Transfer> existing = repository.findTransferByReference(conn, ref);
            if (existing.isPresent()) {
                return existing.get();
            }
        } catch (SQLException ignored) {
            // Proceed to locked path if read fails
        }

        // Thread synchronization to serialize concurrent transfers on this service instance
        transferLock.lock();
        try {
            // Re-check idempotency under lock
            try (Connection conn = database.getConnection()) {
                Optional<Transfer> existing = repository.findTransferByReference(conn, ref);
                if (existing.isPresent()) {
                    return existing.get();
                }
            }

            // Execute atomic transfer transaction
            return executeTransferTransaction(fromId, toId, amountPaise, ref);
        } catch (SQLException e) {
            // If unique constraint violation on reference occurred, retrieve original transfer
            if (isUniqueConstraintViolation(e)) {
                try (Connection conn = database.getConnection()) {
                    Optional<Transfer> existing = repository.findTransferByReference(conn, ref);
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                } catch (SQLException ex) {
                    throw new RuntimeException("Database error reading idempotency record: " + ex.getMessage(), ex);
                }
            }
            throw new RuntimeException("Database transaction failed: " + e.getMessage(), e);
        } finally {
            transferLock.unlock();
        }
    }

    private Transfer executeTransferTransaction(long fromId, long toId, long amountPaise, String ref) throws SQLException {
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Check again inside transaction for idempotency
                Optional<Transfer> existing = repository.findTransferByReference(conn, ref);
                if (existing.isPresent()) {
                    conn.rollback();
                    return existing.get();
                }

                // Validate accounts exist
                Account sender = repository.findAccountById(conn, fromId)
                        .orElseThrow(() -> new IllegalArgumentException("Error: account " + fromId + " does not exist."));
                Account receiver = repository.findAccountById(conn, toId)
                        .orElseThrow(() -> new IllegalArgumentException("Error: account " + toId + " does not exist."));

                String now = Instant.now().toString();

                // Check for insufficient funds
                if (sender.getBalancePaise() < amountPaise) {
                    // Record FAILED transfer without modifying balances
                    Transfer failedTransfer = repository.insertTransfer(
                            conn, fromId, toId, amountPaise, ref, Transfer.STATUS_FAILED, "insufficient funds", now);
                    conn.commit();
                    return failedTransfer;
                }

                // Perform balance updates with overflow safety
                long newSenderBalance = Math.subtractExact(sender.getBalancePaise(), amountPaise);
                long newReceiverBalance = Math.addExact(receiver.getBalancePaise(), amountPaise);

                repository.updateAccountBalance(conn, fromId, newSenderBalance);
                repository.updateAccountBalance(conn, toId, newReceiverBalance);

                Transfer completedTransfer = repository.insertTransfer(
                        conn, fromId, toId, amountPaise, ref, Transfer.STATUS_COMPLETED, null, now);

                conn.commit();
                return completedTransfer;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    // ignore rollback secondary failure
                }
                if (e instanceof IllegalArgumentException iae) {
                    throw iae;
                }
                if (e instanceof SQLException sqle) {
                    throw sqle;
                }
                throw new RuntimeException(e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public static long parseAmountToPaise(String amountStr, boolean mustBePositive) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Error: invalid amount format.");
        }
        String clean = amountStr.trim();
        // Remove rupee symbol or commas if present
        clean = clean.replace("₹", "").replace(",", "").trim();

        BigDecimal bd;
        try {
            bd = new BigDecimal(clean);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Error: invalid amount format.");
        }

        if (bd.scale() > 2) {
            throw new IllegalArgumentException("Error: invalid amount format.");
        }

        if (mustBePositive) {
            if (bd.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Error: amount must be greater than zero.");
            }
        } else {
            if (bd.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Error: opening balance cannot be negative.");
            }
        }

        try {
            return bd.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Error: amount causes numeric overflow.");
        }
    }

    private boolean isUniqueConstraintViolation(SQLException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("UNIQUE constraint failed") || msg.contains("SQLITE_CONSTRAINT"));
    }
}
