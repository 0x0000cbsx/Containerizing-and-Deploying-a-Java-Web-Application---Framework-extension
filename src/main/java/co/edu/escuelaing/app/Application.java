package co.edu.escuelaing.app;

import static co.edu.escuelaing.webframework.WebFramework.get;
import static co.edu.escuelaing.webframework.WebFramework.staticfiles;
import static co.edu.escuelaing.webframework.WebFramework.start;
import static co.edu.escuelaing.webframework.WebFramework.stop;

import co.edu.escuelaing.webframework.Config;

public class Application {
    private static final long MAX_SLOW_MILLIS = 30_000;

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();
        String environment = config.get("APP_ENV", "development");
        String greetingPrefix = config.get("GREETING_PREFIX", "Hello");

        staticfiles("/webroot");

        get("/hello", (req, resp) -> {
            String name = req.getValue("name");
            if (name == null || name.isBlank()) {
                name = "world";
            }
            return greetingPrefix + " " + name;
        });

        get("/greeting", (req, resp) -> {
            String name = req.getValue("name");
            return "Hello, " + (name == null || name.isBlank() ? "World" : name) + "!";
        });

        get("/pi", (req, resp) -> String.valueOf(Math.PI));

        // simulates a slow operation: shows concurrent handling and graceful draining
        get("/slow", (req, resp) -> {
            long millis = parseMillis(req.getValue("ms"));
            long begin = System.nanoTime();
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted during shutdown", e);
            }
            long elapsed = (System.nanoTime() - begin) / 1_000_000;
            return "Slow request finished after " + elapsed + " ms on " + Thread.currentThread().getName();
        });

        get("/config", (req, resp) -> {
            resp.setContentType("application/json; charset=utf-8");
            return "{\"appEnv\":\"" + environment + "\",\"greetingPrefix\":\"" + escapeJson(greetingPrefix) + "\"}";
        });

        // /shutdown only exists in development
        if (environment.equals("development")) {
            get("/shutdown", (req, resp) -> {
                stop();
                return "Server will stop after this response.";
            });
        }

        System.out.println("APP_ENV=" + environment + ", GREETING_PREFIX=" + greetingPrefix);
        start();
    }

    private static long parseMillis(String value) {
        try {
            long millis = value == null ? 2000 : Long.parseLong(value);
            return Math.max(0, Math.min(millis, MAX_SLOW_MILLIS));
        } catch (NumberFormatException e) {
            return 2000;
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
