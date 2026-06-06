package dis.exercise.sheet01;

import dis.exercise.common.DBConfig;

import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.CountDownLatch;

public class TaskB {

    public static void run() throws Exception {
        DataSource ds = DBConfig.getDataSource();

        // ── Step i) ───────────────────────────────────────────────────────────
        // Connect to the persistent database and keep the connection open.
        Connection connA = ds.getConnection();
        connA.setAutoCommit(false);
        connA.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        System.out.println("[A] Connected to database.");

        // ── Step ii) ──────────────────────────────────────────────────────────
        // Create a new table and insert one tuple.
        try (Statement s = connA.createStatement()) {
            s.execute("DROP TABLE IF EXISTS test_table1");
            s.execute("""
                CREATE TABLE test_table1 (
                    id    SERIAL PRIMARY KEY,
                    value VARCHAR(50) NOT NULL
                )
            """);
            s.execute("INSERT INTO test_table1 (value) VALUES ('initial')");
            connA.commit();
        }
        System.out.println("[A] Table created and initial tuple inserted.");

        // Print current table contents
        try (Statement s = connA.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, value FROM test_table1")) {
            System.out.println("Table contents:");
            while (rs.next()) {
                System.out.printf("  id=%-3d value=%s%n", rs.getInt("id"), rs.getString("value"));
            }
        }

        // connA stays open — next steps will use it
        System.out.println("[A] Connection A is still open.");

        // ── Step iii) ─────────────────────────────────────────────────────────
        // Open a second connection to the same database — connection B.
        Connection connB = ds.getConnection();
        connB.setAutoCommit(false);
        connB.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        System.out.println("[B] Connected to database (connection B).");
        System.out.println("Both connections are now open to the same database.");

        // ── Step iv) ──────────────────────────────────────────────────────────
        // Transactions are already active on both connections (autoCommit=false).
        // Isolation level was set at connection time before any SQL ran.
        System.out.println("[A] Transaction started (READ COMMITTED).");
        System.out.println("[B] Transaction started (READ COMMITTED).");

        // ── Step v) ───────────────────────────────────────────────────────────
        // Use connection A to insert a new record.
        try (PreparedStatement ps = connA.prepareStatement(
                "INSERT INTO test_table1 (value) VALUES (?)")) {
            ps.setString(1, "inserted_by_A");
            ps.executeUpdate();
        }
        System.out.println("[A] Inserted 'inserted_by_A' (not yet committed).");

        // ── Step vi) ──────────────────────────────────────────────────────────
        // Use connection B to insert a different record.
        try (PreparedStatement ps = connB.prepareStatement(
                "INSERT INTO test_table1 (value) VALUES (?)")) {
            ps.setString(1, "inserted_by_B");
            ps.executeUpdate();
        }
        System.out.println("[B] Inserted 'inserted_by_B' (not yet committed).");

        // ── Step vii) ─────────────────────────────────────────────────────────
        // Check table contents in both connections.
        // A sees its own insert but not B's, and vice versa.
        System.out.println("\n[A] Table as seen by connection A:");
        printTable(connA);

        System.out.println("\n[B] Table as seen by connection B:");
        printTable(connB);

        // ── Step viii) ────────────────────────────────────────────────────────
        // Commit both transactions and check the table contents.
        connA.commit();
        System.out.println("\n[A] Committed.");

        connB.commit();
        System.out.println("[B] Committed.");

        System.out.println("\n[A] Table after both committed:");
        printTable(connA);

        System.out.println("\n[B] Table after both committed:");
        printTable(connB);

        // ── Step ix) ──────────────────────────────────────────────────────────
        // Open a third connection and check the table contents.
        Connection connC = ds.getConnection();
        connC.setAutoCommit(false);
        System.out.println("\n[C] Third connection opened.");
        System.out.println("[C] Table as seen by connection C:");
        printTable(connC);
        connC.close();

        // ── Step x) ───────────────────────────────────────────────────────────
        // Start a new transaction in each connection.
        // commit() ended the previous transaction, so the next SQL statement
        // automatically begins a new one (autoCommit is still false).
        System.out.println("\n[A] New transaction started.");
        System.out.println("[B] New transaction started.");

        // ── Step xi) ──────────────────────────────────────────────────────────
        // Both A and B update the same row (id=1) with different values.
        // B's UPDATE will block on A's row lock until A commits in step xii.
        // A single thread would deadlock here, so we use two threads.
        CountDownLatch aUpdated = new CountDownLatch(1);
        CountDownLatch bDone    = new CountDownLatch(1);

        Thread threadA = new Thread(() -> {
            try {
                try (PreparedStatement ps = connA.prepareStatement(
                        "UPDATE test_table1 SET value = ? WHERE id = 1")) {
                    ps.setString(1, "updated_by_A");
                    ps.executeUpdate();
                }
                System.out.println("[A] Updated id=1 to 'updated_by_A' (not yet committed).");
                aUpdated.countDown();

                // ── Step xii) A commits ───────────────────────────────────────
                connA.commit();
                System.out.println("[A] Committed.");

                bDone.await();
                System.out.println("\n[A] Table after both committed:");
                printTable(connA);
            } catch (Exception e) {
                System.err.println("[A] Error: " + e.getMessage());
                aUpdated.countDown();
            }
        }, "Thread-A");

        Thread threadB = new Thread(() -> {
            try {
                aUpdated.await(); // ensure A updates first so we can see the block
                System.out.println("[B] Attempting UPDATE on id=1 — blocking until A commits...");
                try (PreparedStatement ps = connB.prepareStatement(
                        "UPDATE test_table1 SET value = ? WHERE id = 1")) {
                    ps.setString(1, "updated_by_B");
                    ps.executeUpdate(); // blocks here until A commits
                }
                System.out.println("[B] Updated id=1 to 'updated_by_B' (unblocked).");

                // ── Step xii) B commits ───────────────────────────────────────
                connB.commit();
                System.out.println("[B] Committed.");
                bDone.countDown();

                System.out.println("\n[B] Table after both committed:");
                printTable(connB);
            } catch (Exception e) {
                System.err.println("[B] Error: " + e.getMessage());
                bDone.countDown();
            }
        }, "Thread-B");

        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();

        // ── Step xiii) ────────────────────────────────────────────────────────
        // Open a third connection and check the final state of the table.
        Connection connC2 = ds.getConnection();
        connC2.setAutoCommit(false);
        System.out.println("\n[C] Third connection opened.");
        System.out.println("[C] Table after second round of commits:");
        printTable(connC2);
        connC2.close();

        connA.close();
        connB.close();
    }

    private static void printTable(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, value FROM test_table1 ORDER BY id")) {
            System.out.printf("  %-4s | %-20s%n", "id", "value");
            System.out.println("  -----|---------------------");
            while (rs.next()) {
                System.out.printf("  %-4d | %s%n", rs.getInt("id"), rs.getString("value"));
            }
        }
    }
}
