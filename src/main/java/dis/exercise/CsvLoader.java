package dis.exercise;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class CsvLoader {

    /**
     * Loads a CSV file into the given table using PostgreSQL's COPY command.
     * The CSV must have a header row matching the table columns.
     */
    public static long loadCsv(Connection conn, String tableName, String csvPath) throws SQLException, IOException {
        CopyManager copyManager = new CopyManager((BaseConnection) conn);
        String sql = String.format("COPY %s FROM STDIN DELIMITER ',' CSV HEADER", tableName);
        long rowsInserted;
        try (FileReader reader = new FileReader(csvPath)) {
            rowsInserted = copyManager.copyIn(sql, reader);
        }
        conn.commit();
        System.out.printf("Loaded %d rows into '%s' from '%s'%n", rowsInserted, tableName, csvPath);
        return rowsInserted;
    }
}
