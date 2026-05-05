package dis.exercise.sheet01;

import dis.exercise.common.DBConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;

public class TaskA {

    public static void run() throws SQLException, IOException {
        DataSource ds = DBConfig.getDataSource();

        try (Connection conn = ds.getConnection()) {
            createTables(conn);
        }

        try (Connection conn = ds.getConnection()) {
            CsvLoader.loadCsv(conn, "products", "data/products.csv");
        }

        try (Connection conn = ds.getConnection()) {
            printAccounts(conn);
            transferBalance(conn, 1, 2, 150.00);
            printAccounts(conn);
        }
    }

    private static void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id    SERIAL PRIMARY KEY,
                    name  VARCHAR(100) NOT NULL,
                    price NUMERIC(10, 2),
                    stock INTEGER
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id      SERIAL PRIMARY KEY,
                    name    VARCHAR(100) NOT NULL,
                    balance NUMERIC(12, 2) NOT NULL DEFAULT 0
                )
            """);
            stmt.execute("""
                INSERT INTO accounts (name, balance)
                SELECT * FROM (VALUES ('Alice', 1000.00), ('Bob', 500.00)) AS v(name, balance)
                WHERE NOT EXISTS (SELECT 1 FROM accounts)
            """);
            conn.commit();
            System.out.println("Tables ready.");
        }
    }

    private static void transferBalance(Connection conn, int fromId, int toId, double amount) throws SQLException {
        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            double balance;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
                ps.setInt(1, fromId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new SQLException("Account not found: " + fromId);
                balance = rs.getDouble("balance");
            }
            if (balance < amount) throw new SQLException(
                String.format("Insufficient funds: %.2f available, %.2f needed", balance, amount));

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
                ps.setDouble(1, -amount); ps.setInt(2, fromId); ps.executeUpdate();
                ps.setDouble(1,  amount); ps.setInt(2, toId);   ps.executeUpdate();
            }
            conn.commit();
            System.out.printf("Transferred %.2f from account %d to %d%n", amount, fromId, toId);
        } catch (SQLException e) {
            conn.rollback();
            System.err.println("Transfer rolled back: " + e.getMessage());
            throw e;
        }
    }

    private static void printAccounts(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, balance FROM accounts ORDER BY id")) {
            System.out.println("\n--- Accounts ---");
            while (rs.next()) {
                System.out.printf("  [%d] %-10s %.2f%n",
                    rs.getInt("id"), rs.getString("name"), rs.getDouble("balance"));
            }
            conn.commit();
        }
    }
}
