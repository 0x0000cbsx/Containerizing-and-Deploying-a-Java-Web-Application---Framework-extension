package co.edu.escuelaing.webframework;

import java.util.Map;

public final class Config {
    public static final int DEFAULT_PORT = 8080;

    private final Map<String, String> values;

    public Config(Map<String, String> values) {
        this.values = values;
    }

    public static Config fromEnv() {
        return new Config(System.getenv());
    }

    public String get(String name, String defaultValue) {
        String value = values.get(name);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    public int port() {
        String value = get("PORT", null);
        if (value == null) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid PORT value: '" + value + "' (expected 0-65535)", e);
        }
    }

    public String staticFilesPath() {
        return get("STATIC_FILES_PATH", null);
    }
}
