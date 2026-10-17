# Wallet Transfers CLI Application — Java + SQLite

A complete, production-minded command-line wallet and money transfer application built with Java 17 and SQLite. The application prioritizes financial correctness, money conservation, atomic database transactions, idempotency, strict input validation, concurrency safety, and comprehensive automated testing without relying on heavy web or ORM frameworks.

---

## 1. Project Overview

`wallet-transfers` simulates a financial wallet system supporting account creation, rupee balance tracking, peer-to-peer money transfers with idempotency references, transaction history inspection, and concurrent transfer stress simulations.

### Key Highlights
- **Exact Money Representation:** All monetary values are processed and stored as integer **paise** (`long` in Java, `INTEGER` in SQLite), completely eliminating floating-point rounding errors.
- **Atomic Transactions:** Transfer operations execute atomically using SQLite transactions with `IMMEDIATE` write locks and automatic rollback on failure.
- **True Idempotency:** Every transfer requires a client-supplied unique `reference`. Repeating a reference returns the original transfer record without moving money again.
- **Concurrency Safety:** A dual-layer concurrency mechanism (in-process lock + database-level immediate transaction lock) ensures zero race conditions, lost updates, or deadlocks during high-concurrency transfers.
- **Fail-Safe Business Failures:** Failed operations (e.g., insufficient funds) are durably recorded with a reason, leaving balances untouched.

---

## 2. Technology Stack & Prerequisites

- **Java:** Java 17 or higher (LTS recommended, tested on Java 21)
- **Build Tool:** Apache Maven 3.8+
- **Database Engine:** SQLite 3 (embedded, zero configuration required)
- **Database Access:** `org.xerial:sqlite-jdbc` (v3.45.2.0) with direct SQL queries and `PreparedStatement`
- **Testing:** JUnit 5 (`org.junit.jupiter:junit-jupiter` v5.10.2)
- **Logging:** `org.slf4j:slf4j-nop` (v1.7.36) for clean console output without clutter

---

## 3. Project Structure

```text
wallet-transfers/
├── pom.xml
├── README.md
├── .gitignore
└── src/
    ├── main/
    │   └── java/
    │       └── com/wallet/
    │           ├── Main.java              # Interactive CLI command loop & input parsing
    │           ├── Database.java          # SQLite configuration, pragmas & schema setup
    │           ├── WalletService.java     # Business rules, transactions, idempotency & locks
    │           ├── WalletRepository.java  # JDBC SQL execution using PreparedStatements
    │           ├── Account.java           # Account model with paise-to-rupee formatting
    │           ├── Transfer.java          # Transfer model with status & formatting
    │           └── SimulationRunner.java  # 20-thread concurrency simulation runner
    └── test/
        └── java/
            └── com/wallet/
                └── WalletServiceTest.java # Comprehensive JUnit 5 test suite
```

---

## 4. Build, Run, and Test Instructions

### Build the Application
Compile the code, run all unit tests, and generate an executable fat JAR:
```bash
mvn clean package
```

### Run Automated Tests
Execute the JUnit 5 test suite:
```bash
mvn test
```

### Launch the Application
Run the packaged JAR:
```bash
java -jar target/wallet-transfers-1.0.0.jar
```
Or execute directly using Maven without packaging:
```bash
mvn compile exec:java -Dexec.mainClass="com.wallet.Main"
```

The application creates and connects to `wallet.db` in the working directory automatically upon startup.

---

## 5. Supported CLI Commands

The CLI runs an interactive loop reading commands line by line until `exit` or `quit` is entered. Invalid input does not terminate the program.

### Command A: Open Account
Creates a new wallet account with an initial opening balance.

- **Syntax:** `open <name> <opening_balance>`
- **Example:**
  ```text
  wallet> open Dhyey 1000
  Account created successfully. ID: 1, Owner: Dhyey, Initial Balance: ₹1,000.00
  ```
- **Rules:**
  - Owner name cannot be blank.
  - Opening balance can be zero (`open Bob 0`).
  - Negative balances are rejected (`Error: opening balance cannot be negative.`).
  - Fractional paise (> 2 decimal places) are rejected (`Error: invalid amount format.`).

### Command B: Transfer Money
Transfers funds atomically between two accounts with a unique idempotency reference.

- **Syntax:** `transfer <from_id> <to_id> <amount> <reference>`
- **Example (Success):**
  ```text
  wallet> transfer 1 2 250.50 REF-TX-001
  Transfer COMPLETED. ID: 1, Reference: REF-TX-001, Amount: ₹250.50, From: 1, To: 2
  ```
- **Example (Insufficient Funds):**
  ```text
  wallet> transfer 2 1 50000 REF-TX-002
  Transfer FAILED. ID: 2, Reference: REF-TX-002, Reason: insufficient funds
  ```
- **Example (Idempotent Replay):**
  ```text
  wallet> transfer 1 2 250.50 REF-TX-001
  Transfer COMPLETED. ID: 1, Reference: REF-TX-001, Amount: ₹250.50, From: 1, To: 2
  ```
  *(Returns the original record; no additional money is moved).*

### Command C: Check Balance
Retrieves and displays the account owner and current balance.

- **Syntax:** `balance <account_id>`
- **Example:**
  ```text
  wallet> balance 1
  Account ID: 1, Owner: Dhyey, Balance: ₹749.50
  ```

### Command D: View History
Lists incoming and outgoing transfers (both `COMPLETED` and `FAILED`) sorted newest first.

- **Syntax:** `history <account_id> [--limit <n>]`
- **Example:**
  ```text
  wallet> history 1 --limit 5
  Transfer history for account 1 (newest first, limit 5):
  ID     | Direction  | Reference        | Amount     | Status     | Counterparty | Timestamp            | Reason
  ---------------------------------------------------------------------------------------------------------
  2      | INCOMING   | REF-TX-002       | ₹50,000.00 | FAILED     | Account 2    | 2026-10-17T04:09:02Z | insufficient funds
  1      | OUTGOING   | REF-TX-001       | ₹250.50    | COMPLETED  | Account 2    | 2026-10-17T04:08:45Z | -
  ```
- **Rules:**
  - Default limit is 10 if `--limit` is omitted.
  - Limit must be a positive integer.

### Command E: Run Concurrency Simulation
Executes 20 concurrent transfer tasks across two fresh accounts and asserts invariant safety.

- **Syntax:** `simulate`
- **Expected Output:**
  ```text
  wallet> simulate
  Simulation completed.
  Transfers attempted: 20
  Successful transfers: 20
  Failed transfers: 0
  Account X final balance: ₹1,000.00
  Account Y final balance: ₹1,000.00
  Combined balance: ₹2,000.00
  Money conservation: PASS
  Non-negative balances: PASS
  Concurrency test: PASS
  ```

### Help and Exit
- `help`: Lists all available commands and syntax.
- `exit` or `quit`: Cleanly exits the interactive session.

---

## 6. Database Schema and Constraints

The application utilizes two normalized tables in SQLite with strict constraints and foreign keys.

```sql
CREATE TABLE IF NOT EXISTS accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_name TEXT NOT NULL,
    balance_paise INTEGER NOT NULL CHECK (balance_paise >= 0),
    created_at TEXT NOT NULL
);

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

CREATE INDEX IF NOT EXISTS idx_transfers_from_id ON transfers(from_id);
CREATE INDEX IF NOT EXISTS idx_transfers_to_id ON transfers(to_id);
CREATE INDEX IF NOT EXISTS idx_transfers_history ON transfers(created_at, id);
```

### Constraints Enforced:
1. `PRAGMA foreign_keys = ON;` is executed on **every connection** to enforce referential integrity.
2. `CHECK (balance_paise >= 0)` guarantees at the database engine level that no account balance can ever drop below zero.
3. `CHECK (amount_paise > 0)` guarantees that transfer amounts must be positive.
4. `reference TEXT NOT NULL UNIQUE` provides persistent idempotency across threads, connections, and application restarts.
5. `PRAGMA journal_mode = WAL;` enables Write-Ahead Logging for high read/write concurrency.

---

## 7. Financial Correctness: Money Handling

- **Paise Representation:** All monetary balances and transaction amounts are stored exclusively as integer paise using Java `long` and SQLite `INTEGER` (64-bit signed integer).
- **No Floating-Point Math:** `float` and `double` are never used for monetary arithmetic, avoiding IEEE 754 precision inaccuracies (such as `0.1 + 0.2 = 0.30000000000000004`).
- **Input Parsing via `BigDecimal`:** User input is parsed using `BigDecimal`, rejecting values with more than two decimal places (e.g., `10.555`), malformed strings, or amounts outside supported ranges. Conversion to paise is performed via `bd.movePointRight(2).longValueExact()`.
- **Overflow Protection:** Balance additions and subtractions employ `Math.addExact()` and `Math.subtractExact()`, immediately catching arithmetic overflow before committing.
- **Output Formatting:** Formatted via exact integer division and modulus (`rupees = paise / 100`, `remainingPaise = paise % 100`), ensuring exact output like `₹1,000.00`.

---

## 8. Atomic Database Transactions & Idempotency

### Transaction Boundary
Each transfer runs within an explicit transaction:
1. `conn.setAutoCommit(false)` is invoked.
2. `SQLiteConfig.TransactionMode.IMMEDIATE` acquires a reserved write lock immediately upon transaction start, preventing multiple readers from concurrently upgrading to writers.
3. Sender and receiver accounts are retrieved and verified.
4. If sender funds are insufficient, a `FAILED` record is inserted, the transaction commits, and no balances are altered.
5. If sender funds are sufficient, sender is debited, receiver is credited, a `COMPLETED` transfer record is inserted, and `conn.commit()` commits the changes atomically.
6. In case of any unexpected SQL failure, `conn.rollback()` restores state, ensuring partial updates are impossible.

### Idempotency Guarantee
The client provides a unique `reference` with each transfer:
- Before opening a transaction, `WalletService` checks if `reference` already exists.
- If the reference already exists, the original `Transfer` is immediately returned. No funds are transferred a second time.
- If two threads simultaneously submit the same reference, the database `UNIQUE(reference)` constraint guarantees that only one transaction succeeds; the other catches the constraint violation and retrieves the original recorded result.

---

## 9. Concurrency and Threading Strategy

```text
The threading strategy combines in-process serialization with database-level isolation.
Within the single WalletService instance, an internal ReentrantLock serializes execution of
concurrent transfer operations, eliminating thread interleaving and race conditions during balance
checks and updates. Concurrently, SQLite connections are configured with TransactionMode.IMMEDIATE
and PRAGMA journal_mode = WAL, ensuring database-level write serialization across separate
connections or external processes. The UNIQUE constraint on the transfer reference provides a final
guarantee of idempotency. Deadlocks between bidirectional transfers (e.g., X->Y and Y->X) are
completely prevented by serializing the critical section rather than acquiring per-account locks in
conflicting orders.
```

---

## 10. Automated Test Coverage

The test suite in `WalletServiceTest.java` uses JUnit 5 with `@TempDir` to execute tests against isolated, temporary SQLite databases.

| # | Test Case | Description |
|---|---|---|
| 1 | `testAccountCreationAndInitialBalance` | Verifies account creation, ID generation, zero balance, and rejection of negative opening balance |
| 2 | `testSuccessfulTransferAndMoneyConservation` | Verifies correct debit/credit and absolute money conservation ($X + Y = \text{const}$) |
| 3 | `testInsufficientFundsPersistsFailedRecordWithoutMovingMoney` | Verifies FAILED transfer record is persisted with clear reason and balances remain untouched |
| 4 | `testDuplicateReferenceIdempotency` | Verifies duplicate references return the original transfer and never move money twice |
| 5 | `testInvalidTransfersValidation` | Verifies rejection of same-account transfers, non-positive amounts, missing accounts, and scale > 2 |
| 6 | `testHistoryOrderAndLimits` | Verifies newest-first order, inclusion of failed transfers, and limit parameter enforcement |
| 7 | `testConcurrencySimulation` | Runs 20 concurrent threads (10 X $\to$ Y, 10 Y $\to$ X) checking money conservation and non-negative balances |
| 8 | `testPersistenceAcrossReopening` | Reopens SQLite database via a new connection pool to verify durable disk persistence |
| 9 | `testConcurrentDuplicateReferences` | 10 threads concurrently race with identical reference; verifies money moves once and all receive the same transfer ID |

---

## 11. Concurrency Simulation Verification

The `simulate` command and test case execute the exact specified scenario:
1. Fresh accounts $X$ and $Y$ are created, each with ₹1,000.00 (100,000 paise). Total = ₹2,000.00 (200,000 paise).
2. A fixed pool of 20 worker threads is primed.
3. A `CountDownLatch(1)` start gate releases all 20 threads simultaneously:
   - 10 threads transfer ₹100.00 from $X \to Y$.
   - 10 threads transfer ₹100.00 from $Y \to X$.
4. Tasks are submitted as `Callable<Transfer>` and futures are gathered with a 10-second timeout.
5. Invariant checks assert:
   - Final balance of $X \ge 0$
   - Final balance of $Y \ge 0$
   - Combined balance $X + Y == 200,000$ paise (Money Conservation: **PASS**)
   - All 20 transfers complete successfully (**PASS**)

---

## 12. Limitations & Future Improvements

With an additional development day, the following enhancements would be added:
1. **Connection Pooling:** Introduce a lightweight JDBC connection pool (e.g. HikariCP) to optimize connection reuse in high-throughput environments.
2. **Account Paging:** Add pagination support (`--offset` or cursor-based pagination) to transaction history queries for high-volume accounts.
3. **Structured Audit Logging:** Add a dedicated audit trail table recording IP, timestamp, and signature for compliance and regulatory reporting.
4. **Multi-Currency Support:** Support currency codes (e.g., INR, USD, EUR) with exchange rate lookup tables and cross-currency conversion services.
