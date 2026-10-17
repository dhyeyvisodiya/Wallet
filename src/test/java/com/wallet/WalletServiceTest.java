package com.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WalletServiceTest {

    @TempDir
    Path tempDir;

    private Database database;
    private WalletRepository repository;
    private WalletService walletService;
    private String dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("test-wallet.db").toAbsolutePath().toString();
        database = new Database(dbPath);
        database.initializeSchema();
        repository = new WalletRepository(database);
        walletService = new WalletService(repository);
    }

    @Test
    @DisplayName("1. Account creation and initial balance verification")
    void testAccountCreationAndInitialBalance() {
        Account acc1 = walletService.openAccount("Dhyey", "1000");
        assertNotNull(acc1);
        assertTrue(acc1.getId() > 0);
        assertEquals("Dhyey", acc1.getOwnerName());
        assertEquals(100000L, acc1.getBalancePaise());
        assertEquals("₹1,000.00", acc1.getFormattedBalance());

        // Also verify retrieved balance directly from database
        Account fetched = walletService.getBalance(acc1.getId());
        assertEquals(100000L, fetched.getBalancePaise());

        // Zero opening balance is allowed
        Account accZero = walletService.openAccount("ZeroUser", "0");
        assertEquals(0L, accZero.getBalancePaise());
        assertEquals("₹0.00", accZero.getFormattedBalance());

        // Negative opening balance is rejected
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                walletService.openAccount("InvalidUser", "-10"));
        assertTrue(ex.getMessage().contains("negative"));
    }

    @Test
    @DisplayName("2. Successful transfer and conservation of total money")
    void testSuccessfulTransferAndMoneyConservation() {
        Account sender = walletService.openAccount("Alice", "500.00");
        Account receiver = walletService.openAccount("Bob", "200.00");

        long initialTotal = sender.getBalancePaise() + receiver.getBalancePaise();

        Transfer transfer = walletService.transfer(sender.getId(), receiver.getId(), "150.50", "TX-101");
        assertNotNull(transfer);
        assertTrue(transfer.isCompleted());
        assertFalse(transfer.isFailed());
        assertEquals(15050L, transfer.getAmountPaise());
        assertNull(transfer.getReason());

        Account updatedSender = walletService.getBalance(sender.getId());
        Account updatedReceiver = walletService.getBalance(receiver.getId());

        assertEquals(34950L, updatedSender.getBalancePaise()); // 50000 - 15050
        assertEquals(35050L, updatedReceiver.getBalancePaise()); // 20000 + 15050

        // Conservation of total money
        long finalTotal = updatedSender.getBalancePaise() + updatedReceiver.getBalancePaise();
        assertEquals(initialTotal, finalTotal);
    }

    @Test
    @DisplayName("3. Insufficient funds creates FAILED record and preserves balances")
    void testInsufficientFundsPersistsFailedRecordWithoutMovingMoney() {
        Account sender = walletService.openAccount("Charlie", "50.00");
        Account receiver = walletService.openAccount("David", "100.00");

        Transfer failedTransfer = walletService.transfer(sender.getId(), receiver.getId(), "100.00", "TX-FAIL-1");
        assertNotNull(failedTransfer);
        assertTrue(failedTransfer.isFailed());
        assertFalse(failedTransfer.isCompleted());
        assertEquals("insufficient funds", failedTransfer.getReason());

        // Balances must remain unchanged
        Account senderCheck = walletService.getBalance(sender.getId());
        Account receiverCheck = walletService.getBalance(receiver.getId());

        assertEquals(5000L, senderCheck.getBalancePaise());
        assertEquals(10000L, receiverCheck.getBalancePaise());

        // History must record the failed transfer
        List<Transfer> history = walletService.getHistory(sender.getId(), 5);
        assertEquals(1, history.size());
        assertEquals("FAILED", history.get(0).getStatus());
        assertEquals("TX-FAIL-1", history.get(0).getReference());
    }

    @Test
    @DisplayName("4. Duplicate reference returns original result and does not move money twice")
    void testDuplicateReferenceIdempotency() {
        Account sender = walletService.openAccount("Eve", "1000.00");
        Account receiver = walletService.openAccount("Frank", "500.00");

        // First transfer
        Transfer first = walletService.transfer(sender.getId(), receiver.getId(), "200.00", "TX-IDEMP-1");
        assertTrue(first.isCompleted());

        // Repeated transfer with the exact same reference
        Transfer second = walletService.transfer(sender.getId(), receiver.getId(), "200.00", "TX-IDEMP-1");
        assertEquals(first.getId(), second.getId());
        assertEquals(first.getStatus(), second.getStatus());
        assertEquals(first.getReference(), second.getReference());

        // Sender and receiver balances changed ONLY ONCE
        assertEquals(80000L, walletService.getBalance(sender.getId()).getBalancePaise());
        assertEquals(70000L, walletService.getBalance(receiver.getId()).getBalancePaise());

        // Also test idempotency for a FAILED transfer
        Transfer failed1 = walletService.transfer(sender.getId(), receiver.getId(), "99999.00", "TX-FAIL-IDEMP");
        assertTrue(failed1.isFailed());

        Transfer failed2 = walletService.transfer(sender.getId(), receiver.getId(), "99999.00", "TX-FAIL-IDEMP");
        assertEquals(failed1.getId(), failed2.getId());
        assertEquals("FAILED", failed2.getStatus());
    }

    @Test
    @DisplayName("5. Rejection of zero, negative, same-account transfers, and non-existent accounts")
    void testInvalidTransfersValidation() {
        Account acc1 = walletService.openAccount("User1", "100");
        Account acc2 = walletService.openAccount("User2", "100");

        // Same-account transfer
        IllegalArgumentException sameAccEx = assertThrows(IllegalArgumentException.class, () ->
                walletService.transfer(acc1.getId(), acc1.getId(), "10", "REF-SAME"));
        assertTrue(sameAccEx.getMessage().contains("sender and receiver must be different"));

        // Zero amount
        IllegalArgumentException zeroEx = assertThrows(IllegalArgumentException.class, () ->
                walletService.transfer(acc1.getId(), acc2.getId(), "0", "REF-ZERO"));
        assertTrue(zeroEx.getMessage().contains("greater than zero"));

        // Negative amount
        IllegalArgumentException negEx = assertThrows(IllegalArgumentException.class, () ->
                walletService.transfer(acc1.getId(), acc2.getId(), "-10", "REF-NEG"));
        assertTrue(negEx.getMessage().contains("greater than zero"));

        // Non-existent sender
        IllegalArgumentException nonExSender = assertThrows(IllegalArgumentException.class, () ->
                walletService.transfer(9999L, acc2.getId(), "10", "REF-NO-SENDER"));
        assertTrue(nonExSender.getMessage().contains("account 9999 does not exist"));

        // Non-existent receiver
        IllegalArgumentException nonExRecv = assertThrows(IllegalArgumentException.class, () ->
                walletService.transfer(acc1.getId(), 8888L, "10", "REF-NO-RECV"));
        assertTrue(nonExRecv.getMessage().contains("account 8888 does not exist"));

        // Invalid decimal precision (> 2 decimal places)
        IllegalArgumentException precEx = assertThrows(IllegalArgumentException.class, () ->
                walletService.transfer(acc1.getId(), acc2.getId(), "10.555", "REF-PREC"));
        assertTrue(precEx.getMessage().contains("invalid amount format"));
    }

    @Test
    @DisplayName("6. History includes completed and failed transfers in newest-first order with limit")
    void testHistoryOrderAndLimits() {
        Account userA = walletService.openAccount("HistUserA", "1000");
        Account userB = walletService.openAccount("HistUserB", "1000");

        walletService.transfer(userA.getId(), userB.getId(), "100", "H-REF-1");
        walletService.transfer(userB.getId(), userA.getId(), "50", "H-REF-2");
        walletService.transfer(userA.getId(), userB.getId(), "50000", "H-REF-3"); // Fails due to insufficient funds

        List<Transfer> historyA = walletService.getHistory(userA.getId(), 10);
        assertEquals(3, historyA.size());

        // Newest first
        assertEquals("H-REF-3", historyA.get(0).getReference());
        assertEquals("FAILED", historyA.get(0).getStatus());

        assertEquals("H-REF-2", historyA.get(1).getReference());
        assertEquals("COMPLETED", historyA.get(1).getStatus());

        assertEquals("H-REF-1", historyA.get(2).getReference());
        assertEquals("COMPLETED", historyA.get(2).getStatus());

        // Test limit
        List<Transfer> limited = walletService.getHistory(userA.getId(), 2);
        assertEquals(2, limited.size());
        assertEquals("H-REF-3", limited.get(0).getReference());
        assertEquals("H-REF-2", limited.get(1).getReference());

        // Negative limit rejected
        assertThrows(IllegalArgumentException.class, () -> walletService.getHistory(userA.getId(), 0));
        assertThrows(IllegalArgumentException.class, () -> walletService.getHistory(userA.getId(), -5));
    }

    @Test
    @DisplayName("7. Concurrency simulation maintains money conservation and non-negative balances")
    void testConcurrencySimulation() {
        SimulationRunner.SimulationResult result = SimulationRunner.runSimulation(walletService, false);
        assertTrue(result.overallSuccess, "Simulation overall result should be true");
        assertEquals(20, result.attempted);
        assertEquals(20, result.successful);
        assertEquals(0, result.failed);
        assertTrue(result.moneyConservationPass);
        assertTrue(result.nonNegativeBalancesPass);
        assertTrue(result.concurrencyTestPass);
        assertEquals(100000L, result.accountXFinalPaise);
        assertEquals(100000L, result.accountYFinalPaise);
    }

    @Test
    @DisplayName("8. Persistence across database reopening")
    void testPersistenceAcrossReopening() {
        Account created = walletService.openAccount("PersistentUser", "750.25");
        walletService.openAccount("Partner", "100.00");
        walletService.transfer(created.getId(), 2L, "50.25", "PERSIST-1");

        // Re-open database with a new connection factory pointing to the same file
        Database reopenedDb = new Database(dbPath);
        WalletRepository newRepo = new WalletRepository(reopenedDb);
        WalletService newService = new WalletService(newRepo);

        Account reloaded = newService.getBalance(created.getId());
        assertEquals("PersistentUser", reloaded.getOwnerName());
        assertEquals(70000L, reloaded.getBalancePaise()); // 75025 - 5025 = 70000
        assertEquals("₹700.00", reloaded.getFormattedBalance());

        List<Transfer> history = newService.getHistory(created.getId(), 10);
        assertEquals(1, history.size());
        assertEquals("PERSIST-1", history.get(0).getReference());
        assertEquals(5025L, history.get(0).getAmountPaise());
    }

    @Test
    @DisplayName("9. Concurrent transfers with the exact same reference execute idempotently")
    void testConcurrentDuplicateReferences() throws Exception {
        Account sender = walletService.openAccount("RaceSender", "1000");
        Account receiver = walletService.openAccount("RaceReceiver", "500");

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch gate = new CountDownLatch(1);
        String sharedRef = "RACE-SAME-REF-001";

        List<Future<Transfer>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                gate.await();
                return walletService.transfer(sender.getId(), receiver.getId(), "100", sharedRef);
            }));
        }

        gate.countDown(); // release all 10 threads together
        List<Transfer> results = new ArrayList<>();
        for (Future<Transfer> f : futures) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Every thread should receive the same transfer ID
        long firstId = results.get(0).getId();
        for (Transfer t : results) {
            assertEquals(firstId, t.getId());
            assertEquals("COMPLETED", t.getStatus());
            assertEquals(sharedRef, t.getReference());
        }

        // Money must have moved exactly once: ₹100 = 10000 paise
        Account finalSender = walletService.getBalance(sender.getId());
        Account finalReceiver = walletService.getBalance(receiver.getId());

        assertEquals(90000L, finalSender.getBalancePaise()); // 100000 - 10000
        assertEquals(60000L, finalReceiver.getBalancePaise()); // 50000 + 10000
    }
}
