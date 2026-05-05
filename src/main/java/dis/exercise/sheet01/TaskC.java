package dis.exercise.sheet01;

import dis.exercise.common.DBConfig;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import java.io.FileReader;
import java.sql.*;

public class TaskC {

    public static void run() throws Exception {
        Connection conn = DBConfig.getDataSource().getConnection();
        conn.setAutoCommit(false);

        // ── Step ii) Create table with constraints ────────────────────────────
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS coffee_sales");
            s.execute("""
                CREATE TABLE coffee_sales (
                    id           SERIAL PRIMARY KEY,
                    hour_of_day  INTEGER     NOT NULL CHECK (hour_of_day BETWEEN 0 AND 23),
                    cash_type    VARCHAR(10) NOT NULL CHECK (cash_type IN ('card', 'cash')),
                    money        NUMERIC(10,2) NOT NULL CHECK (money > 0),
                    coffee_name  VARCHAR(50) NOT NULL,
                    time_of_day  VARCHAR(10) NOT NULL CHECK (time_of_day IN ('Morning', 'Afternoon', 'Night')),
                    weekday      VARCHAR(3)  NOT NULL,
                    month_name   VARCHAR(3)  NOT NULL,
                    weekdaysort  INTEGER     NOT NULL CHECK (weekdaysort BETWEEN 1 AND 7),
                    monthsort    INTEGER     NOT NULL CHECK (monthsort BETWEEN 1 AND 12),
                    date         DATE        NOT NULL,
                    time         TIME        NOT NULL
                )
            """);
            conn.commit();
            System.out.println("Table 'coffee_sales' created.");
        }

        // ── Step iii) Load CSV data ───────────────────────────────────────────
        CopyManager copyManager = new CopyManager(conn.unwrap(BaseConnection.class));
        long rows;
        try (FileReader reader = new FileReader("data/Coffe_sales.csv")) {
            rows = copyManager.copyIn("""
                COPY coffee_sales (hour_of_day, cash_type, money, coffee_name,
                    time_of_day, weekday, month_name, weekdaysort, monthsort, date, time)
                FROM STDIN DELIMITER ',' CSV HEADER
                """, reader);
            conn.commit();
        }
        System.out.printf("Loaded %d rows into 'coffee_sales'.%n", rows);

        // ── Verify: row count ─────────────────────────────────────────────────
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM coffee_sales")) {
            rs.next();
            System.out.printf("Rows in table: %d%n", rs.getLong(1));
        }

        // ── Verify: first 5 rows ──────────────────────────────────────────────
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, date, time, coffee_name, money, cash_type FROM coffee_sales ORDER BY id LIMIT 5")) {
            System.out.printf("%n%-4s %-12s %-15s %-20s %-8s %-6s%n",
                "id", "date", "time", "coffee_name", "money", "cash_type");
            System.out.println("-".repeat(70));
            while (rs.next()) {
                System.out.printf("%-4d %-12s %-15s %-20s %-8.2f %-6s%n",
                    rs.getInt("id"),
                    rs.getDate("date"),
                    rs.getTime("time"),
                    rs.getString("coffee_name"),
                    rs.getDouble("money"),
                    rs.getString("cash_type"));
            }
            conn.commit();
        }

        // ── Verify constraints ────────────────────────────────────────────────
        // All constraints were defined at CREATE TABLE time.
        // Query the system catalog to confirm they exist.
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("""
                SELECT conname AS constraint_name,
                       contype AS type,
                       pg_get_constraintdef(oid) AS definition
                FROM pg_constraint
                WHERE conrelid = 'coffee_sales'::regclass
                ORDER BY contype
                """)) {
            System.out.printf("%n%-35s %-5s %s%n", "constraint_name", "type", "definition");
            System.out.println("-".repeat(90));
            while (rs.next()) {
                System.out.printf("%-35s %-5s %s%n",
                    rs.getString("constraint_name"),
                    rs.getString("type"),
                    rs.getString("definition"));
            }
            conn.commit();
        }

        // ── Normalization ─────────────────────────────────────────────────────
        // Redundancy: weekday, month_name, weekdaysort, monthsort are all
        // determined by date. hour_of_day and time_of_day are determined by time.
        // Every row with the same date repeats the same weekday/month values.
        // Fix: extract into a date_dim table, reference it via FK from coffee_sales.

        try (Statement s = conn.createStatement()) {

            // Create date dimension table
            s.execute("DROP TABLE IF EXISTS date_dim");
            s.execute("""
                CREATE TABLE date_dim (
                    date        DATE        PRIMARY KEY,
                    weekday     VARCHAR(3)  NOT NULL,
                    month_name  VARCHAR(3)  NOT NULL,
                    weekdaysort INTEGER     NOT NULL CHECK (weekdaysort BETWEEN 1 AND 7),
                    monthsort   INTEGER     NOT NULL CHECK (monthsort BETWEEN 1 AND 12)
                )
            """);
            System.out.println("\nTable 'date_dim' created.");

            // Populate date_dim with distinct dates from coffee_sales
            s.execute("""
                INSERT INTO date_dim (date, weekday, month_name, weekdaysort, monthsort)
                SELECT DISTINCT date, weekday, month_name, weekdaysort, monthsort
                FROM coffee_sales
                ORDER BY date
            """);
            conn.commit();

            // Verify date_dim contents
            try (ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) FROM date_dim")) {
                rs.next();
                System.out.printf("Distinct dates in date_dim: %d%n", rs.getLong(1));
            }

            // Add FK column to coffee_sales and populate it
            s.execute("ALTER TABLE coffee_sales ADD COLUMN date_fk DATE REFERENCES date_dim(date)");
            s.execute("UPDATE coffee_sales SET date_fk = date");
            s.execute("ALTER TABLE coffee_sales ALTER COLUMN date_fk SET NOT NULL");
            conn.commit();

            // Drop the now-redundant columns from coffee_sales
            s.execute("""
                ALTER TABLE coffee_sales
                    DROP COLUMN weekday,
                    DROP COLUMN month_name,
                    DROP COLUMN weekdaysort,
                    DROP COLUMN monthsort,
                    DROP COLUMN hour_of_day,
                    DROP COLUMN time_of_day,
                    DROP COLUMN date
            """);
            conn.commit();
            System.out.println("Redundant columns dropped from 'coffee_sales'.");
        }

        // Verify final schema of coffee_sales
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, date_fk, time, coffee_name, money, cash_type FROM coffee_sales ORDER BY id LIMIT 5")) {
            System.out.printf("%n%-4s %-12s %-15s %-20s %-8s %-6s%n",
                "id", "date_fk", "time", "coffee_name", "money", "cash_type");
            System.out.println("-".repeat(70));
            while (rs.next()) {
                System.out.printf("%-4d %-12s %-15s %-20s %-8.2f %-6s%n",
                    rs.getInt("id"),
                    rs.getDate("date_fk"),
                    rs.getTime("time"),
                    rs.getString("coffee_name"),
                    rs.getDouble("money"),
                    rs.getString("cash_type"));
            }
            conn.commit();
        }

        // Verify date_dim sample
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT * FROM date_dim ORDER BY date LIMIT 5")) {
            System.out.printf("%n%-12s %-8s %-10s %-11s %-9s%n",
                "date", "weekday", "month_name", "weekdaysort", "monthsort");
            System.out.println("-".repeat(55));
            while (rs.next()) {
                System.out.printf("%-12s %-8s %-10s %-11d %-9d%n",
                    rs.getDate("date"),
                    rs.getString("weekday"),
                    rs.getString("month_name"),
                    rs.getInt("weekdaysort"),
                    rs.getInt("monthsort"));
            }
            conn.commit();
        }

        conn.close();
    }
}
