package dis.exercise.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class DBConfig {

    private static HikariDataSource dataSource;

    public static DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:postgresql://localhost:5432/dis_db");
            config.setUsername("dis_user");
            config.setPassword("dis_password");
            config.setMaximumPoolSize(10);
            config.setAutoCommit(false);
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
