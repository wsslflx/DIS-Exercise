package dis.exercise.sheet03;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class EnvConfig {

    private static final Map<String, String> values = new HashMap<>();

    static {
        Path dir = Paths.get("").toAbsolutePath();
        Path envFile = null;
        for (int i = 0; i < 4; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.exists(candidate)) {
                envFile = candidate;
                break;
            }
            dir = dir.getParent();
            if (dir == null) break;
        }

        if (envFile == null) {
            throw new IllegalStateException(".env file not found. Copy .env.example to .env and fill in your credentials.");
        }

        try {
            for (String line : Files.readAllLines(envFile)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read .env file: " + e.getMessage());
        }
    }

    public static String get(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing required env variable: " + key);
        }
        return value;
    }
}
