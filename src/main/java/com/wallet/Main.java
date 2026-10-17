package com.wallet;

import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));

        Database database = new Database("wallet.db");
        database.initializeSchema();

        WalletRepository repository = new WalletRepository(database);
        WalletService walletService = new WalletService(repository);

        System.out.println("==================================================");
        System.out.println("  Wallet Transfers CLI Application (Java + SQLite)");
        System.out.println("  Type 'help' for available commands or 'exit' to quit.");
        System.out.println("==================================================");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nwallet> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                System.out.println("Exiting Wallet Transfers application. Goodbye!");
                break;
            }

            try {
                handleCommand(line, walletService);
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            } catch (Exception e) {
                System.out.println("Error: unexpected error: " + e.getMessage());
            }
        }
    }

    public static void handleCommand(String line, WalletService walletService) {
        String[] parts = line.split("\\s+");
        String command = parts[0].toLowerCase();

        switch (command) {
            case "open" -> handleOpen(parts, walletService);
            case "transfer" -> handleTransfer(parts, walletService);
            case "balance" -> handleBalance(parts, walletService);
            case "history" -> handleHistory(parts, walletService);
            case "simulate" -> handleSimulate(walletService);
            case "help" -> printHelp();
            default -> System.out.println("Error: unknown command '" + command + "'. Type 'help' for usage.");
        }
    }

    private static void handleOpen(String[] parts, WalletService walletService) {
        if (parts.length < 3) {
            System.out.println("Error: missing command arguments. Usage: open <name> <opening_balance>");
            return;
        }
        String name = parts[1];
        String openingBalanceStr = parts[2];

        Account account = walletService.openAccount(name, openingBalanceStr);
        System.out.println("Account created successfully. ID: " + account.getId() +
                ", Owner: " + account.getOwnerName() +
                ", Initial Balance: " + account.getFormattedBalance());
    }

    private static void handleTransfer(String[] parts, WalletService walletService) {
        if (parts.length < 5) {
            System.out.println("Error: missing command arguments. Usage: transfer <from_id> <to_id> <amount> <reference>");
            return;
        }

        long fromId;
        long toId;
        try {
            fromId = Long.parseLong(parts[1]);
            toId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            System.out.println("Error: invalid account ID format. IDs must be integers.");
            return;
        }

        String amountStr = parts[3];
        String reference = parts[4];

        Transfer transfer = walletService.transfer(fromId, toId, amountStr, reference);
        if (transfer.isCompleted()) {
            System.out.println("Transfer COMPLETED. ID: " + transfer.getId() +
                    ", Reference: " + transfer.getReference() +
                    ", Amount: " + transfer.getFormattedAmount() +
                    ", From: " + transfer.getFromId() +
                    ", To: " + transfer.getToId());
        } else {
            System.out.println("Transfer FAILED. ID: " + transfer.getId() +
                    ", Reference: " + transfer.getReference() +
                    ", Reason: " + transfer.getReason());
        }
    }

    private static void handleBalance(String[] parts, WalletService walletService) {
        if (parts.length < 2) {
            System.out.println("Error: missing command arguments. Usage: balance <account_id>");
            return;
        }

        long accountId;
        try {
            accountId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            System.out.println("Error: invalid account ID format. ID must be an integer.");
            return;
        }

        Account account = walletService.getBalance(accountId);
        System.out.println("Account ID: " + account.getId() +
                ", Owner: " + account.getOwnerName() +
                ", Balance: " + account.getFormattedBalance());
    }

    private static void handleHistory(String[] parts, WalletService walletService) {
        if (parts.length < 2) {
            System.out.println("Error: missing command arguments. Usage: history <account_id> [--limit <n>]");
            return;
        }

        long accountId;
        try {
            accountId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            System.out.println("Error: invalid account ID format. ID must be an integer.");
            return;
        }

        int limit = 10;
        if (parts.length >= 3) {
            if (parts[2].equalsIgnoreCase("--limit")) {
                if (parts.length < 4) {
                    System.out.println("Error: missing value for --limit.");
                    return;
                }
                try {
                    limit = Integer.parseInt(parts[3]);
                } catch (NumberFormatException e) {
                    System.out.println("Error: history limit must be a positive integer.");
                    return;
                }
            } else {
                System.out.println("Error: unrecognized option '" + parts[2] + "'. Usage: history <account_id> [--limit <n>]");
                return;
            }
        }

        List<Transfer> history = walletService.getHistory(accountId, limit);
        if (history.isEmpty()) {
            System.out.println("No transfer history found for account " + accountId + ".");
            return;
        }

        System.out.println("Transfer history for account " + accountId + " (newest first, limit " + limit + "):");
        System.out.printf("%-6s | %-10s | %-16s | %-10s | %-10s | %-12s | %-20s | %s%n",
                "ID", "Direction", "Reference", "Amount", "Status", "Counterparty", "Timestamp", "Reason");
        System.out.println("-".repeat(105));

        for (Transfer t : history) {
            boolean isOutgoing = (t.getFromId() == accountId);
            String direction = isOutgoing ? "OUTGOING" : "INCOMING";
            long counterparty = isOutgoing ? t.getToId() : t.getFromId();
            String reason = t.getReason() != null ? t.getReason() : "-";

            System.out.printf("%-6d | %-10s | %-16s | %-10s | %-10s | %-12s | %-20s | %s%n",
                    t.getId(), direction, t.getReference(), t.getFormattedAmount(),
                    t.getStatus(), "Account " + counterparty, t.getCreatedAt(), reason);
        }
    }

    private static void handleSimulate(WalletService walletService) {
        SimulationRunner.runSimulation(walletService);
    }

    private static void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  open <name> <opening_balance>              Open a new wallet account");
        System.out.println("  transfer <from_id> <to_id> <amount> <ref>  Transfer money between accounts");
        System.out.println("  balance <account_id>                       Check account balance");
        System.out.println("  history <account_id> [--limit <n>]         View transaction history");
        System.out.println("  simulate                                   Run concurrent transfer simulation");
        System.out.println("  help                                       Show this help message");
        System.out.println("  exit / quit                                Exit the application");
    }
}
