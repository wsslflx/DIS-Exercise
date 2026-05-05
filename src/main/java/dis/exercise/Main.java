package dis.exercise;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Main {

    public static void main(String[] args) throws SQLException, IOException {
        DataSource ds = DBConfig.getDataSource();

        // 1. Create tables
        try (Connection conn = ds.getConnection()) {
            createTables(conn);
        }

        // 2. Load CSV data into products table
        try (Connection conn = ds.getConnection()) {
            CsvLoader.loadCsv(conn, "products", "data/products.csv");
        }

        // 3. Run transaction demo: transfer balance between accounts
        try (Connection conn = ds.getConnection()) {
            TransactionDemo.printAccounts(conn);
            TransactionDemo.transferBalance(conn, 1, 2, 150.00);
            TransactionDemo.printAccounts(conn);
        }

        DBConfig.close();
    }

    private static void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id      SERIAL PRIMARY KEY,
                    name    VARCHAR(100) NOT NULL,
                    price   NUMERIC(10, 2),
                    stock   INTEGER
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id      SERIAL PRIMARY KEY,
                    name    VARCHAR(100) NOT NULL,
                    balance NUMERIC(12, 2) NOT NULL DEFAULT 0
                )
            """);

            // Seed accounts if empty
            stmt.execute("""
                INSERT INTO accounts (name, balance)
                SELECT * FROM (VALUES ('Alice', 1000.00), ('Bob', 500.00)) AS v(name, balance)
                WHERE NOT EXISTS (SELECT 1 FROM accounts)
            """);

            conn.commit();
            System.out.println("Tables ready.");
        }
    }
}
