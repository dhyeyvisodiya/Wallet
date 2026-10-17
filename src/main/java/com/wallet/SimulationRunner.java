package com.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SimulationRunner {

    public static class SimulationResult {
        public final int attempted;
        public final int successful;
        public final int failed;
        public final long accountXFinalPaise;
        public final long accountYFinalPaise;
        public final boolean moneyConservationPass;
        public final boolean nonNegativeBalancesPass;
        public final boolean concurrencyTestPass;
        public final boolean overallSuccess;

        public SimulationResult(int attempted, int successful, int failed,
                                long accountXFinalPaise, long accountYFinalPaise,
                                boolean moneyConservationPass, boolean nonNegativeBalancesPass,
                                boolean concurrencyTestPass, boolean overallSuccess) {
            this.attempted = attempted;
            this.successful = successful;
            this.failed = failed;
            this.accountXFinalPaise = accountXFinalPaise;
            this.accountYFinalPaise = accountYFinalPaise;
            this.moneyConservationPass = moneyConservationPass;
            this.nonNegativeBalancesPass = nonNegativeBalancesPass;
            this.concurrencyTestPass = concurrencyTestPass;
            this.overallSuccess = overallSuccess;
        }
    }

    public static SimulationResult runSimulation(WalletService walletService) {
        return runSimulation(walletService, true);
    }

    public static SimulationResult runSimulation(WalletService walletService, boolean printOutput) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Account accountX = walletService.openAccount("Simulation-X-" + runId, "1000");
        Account accountY = walletService.openAccount("Simulation-Y-" + runId, "1000");

        long xId = accountX.getId();
        long yId = accountY.getId();
        long transferAmountPaise = 10000L; // ₹100.00
        long initialTotalPaise = 200000L;  // ₹2,000.00

        int totalTasks = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalTasks);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Transfer>> futures = new ArrayList<>(totalTasks);

        try {
            // Submit 10 transfers from X to Y and 10 from Y to X
            for (int i = 1; i <= totalTasks; i++) {
                final long fromId = (i <= 10) ? xId : yId;
                final long toId = (i <= 10) ? yId : xId;
                final String reference = "SIM-" + runId + "-" + String.format("%03d", i);

                Callable<Transfer> task = () -> {
                    startGate.await(); // wait for release
                    return walletService.transfer(fromId, toId, transferAmountPaise, reference);
                };
                futures.add(executor.submit(task));
            }

            // Release all workers simultaneously
            startGate.countDown();

            int successful = 0;
            int failed = 0;
            boolean hadExecutionError = false;

            for (Future<Transfer> future : futures) {
                try {
                    Transfer transfer = future.get(10, TimeUnit.SECONDS);
                    if (transfer.isCompleted()) {
                        successful++;
                    } else {
                        failed++;
                    }
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    hadExecutionError = true;
                    failed++;
                }
            }

            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            Account finalX = walletService.getBalance(xId);
            Account finalY = walletService.getBalance(yId);

            long finalTotal = finalX.getBalancePaise() + finalY.getBalancePaise();
            boolean moneyConservationPass = (finalTotal == initialTotalPaise);
            boolean nonNegativePass = (finalX.getBalancePaise() >= 0 && finalY.getBalancePaise() >= 0);
            boolean concurrencyPass = !hadExecutionError && moneyConservationPass && nonNegativePass && (successful == totalTasks);
            boolean overallSuccess = concurrencyPass;

            if (printOutput) {
                System.out.println("Simulation completed.");
                System.out.println("Transfers attempted: " + totalTasks);
                System.out.println("Successful transfers: " + successful);
                System.out.println("Failed transfers: " + failed);
                System.out.println("Account X final balance: " + finalX.getFormattedBalance());
                System.out.println("Account Y final balance: " + finalY.getFormattedBalance());
                System.out.println("Combined balance: " + Account.formatPaise(finalTotal));
                System.out.println("Money conservation: " + (moneyConservationPass ? "PASS" : "FAIL"));
                System.out.println("Non-negative balances: " + (nonNegativePass ? "PASS" : "FAIL"));
                System.out.println("Concurrency test: " + (concurrencyPass ? "PASS" : "FAIL"));
            }

            return new SimulationResult(
                    totalTasks, successful, failed,
                    finalX.getBalancePaise(), finalY.getBalancePaise(),
                    moneyConservationPass, nonNegativePass,
                    concurrencyPass, overallSuccess
            );

        } catch (Exception e) {
            executor.shutdownNow();
            if (printOutput) {
                System.out.println("Simulation failed with unexpected error: " + e.getMessage());
                System.out.println("Concurrency test: FAIL");
            }
            return new SimulationResult(
                    totalTasks, 0, totalTasks,
                    -1, -1,
                    false, false, false, false
            );
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }
}
