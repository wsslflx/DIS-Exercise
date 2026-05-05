package dis.exercise;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class TransactionDemo {

    /**
     * Demonstrates a transaction with REPEATABLE READ isolation:
     * transfers balance between two accounts atomically.
     */
    public static void transferBalance(Connection conn, int fromId, int toId, double amount) throws SQLException {
        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

        String checkSql = "SELECT balance FROM accounts WHERE id = ?";
        String updateSql = "UPDATE accounts SET balance = balance + ? WHERE id = ?";

        try {
            // Check sender has sufficient funds
            double senderBalance;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setInt(1, fromId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new SQLException("Account not found: " + fromId);
                senderBalance = rs.getDouble("balance");
            }

            if (senderBalance < amount) {
                throw new SQLException(String.format(
                    "Insufficient funds: account %d has %.2f, needs %.2f", fromId, senderBalance, amount));
            }

            // Debit sender
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setDouble(1, -amount);
                ps.setInt(2, fromId);
                ps.executeUpdate();
            }

            // Credit receiver
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setDouble(1, amount);
                ps.setInt(2, toId);
                ps.executeUpdate();
            }

            conn.commit();
            System.out.printf("Transferred %.2f from account %d to account %d%n", amount, fromId, toId);

        } catch (SQLException e) {
            conn.rollback();
            System.err.println("Transaction rolled back: " + e.getMessage());
            throw e;
        }
    }

    public static void printAccounts(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, balance FROM accounts ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- Accounts ---");
            while (rs.next()) {
                System.out.printf("  [%d] %-15s %.2f%n",
                    rs.getInt("id"), rs.getString("name"), rs.getDouble("balance"));
            }
            conn.commit();
        }
    }
}
