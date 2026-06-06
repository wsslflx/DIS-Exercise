package dis.exercise.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class DBConfig {

    private static HikariDataSource dataSource;

    public static DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            String url  = System.getenv().getOrDefault("POSTGRES_URL",  "jdbc:postgresql://localhost:5432/dis_db");
            String user = System.getenv().getOrDefault("POSTGRES_USER", "dis_user");
            String pass = System.getenv("POSTGRES_PASSWORD");
            if (pass == null) throw new IllegalStateException("POSTGRES_PASSWORD env variable not set");
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(pass);
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
