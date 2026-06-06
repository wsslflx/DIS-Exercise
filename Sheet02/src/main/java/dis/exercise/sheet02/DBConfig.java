package dis.exercise.sheet02;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class DBConfig {

    private static HikariDataSource dataSource;

    public static DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(EnvConfig.get("POSTGRES_URL"));
            config.setUsername(EnvConfig.get("POSTGRES_USER"));
            config.setPassword(EnvConfig.get("POSTGRES_PASSWORD"));
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
