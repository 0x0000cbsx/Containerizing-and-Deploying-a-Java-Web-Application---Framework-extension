package co.edu.escuelaing.app;

import static co.edu.escuelaing.webframework.WebFramework.get;
import static co.edu.escuelaing.webframework.WebFramework.staticfiles;
import static co.edu.escuelaing.webframework.WebFramework.start;
import static co.edu.escuelaing.webframework.WebFramework.stop;

import co.edu.escuelaing.webframework.Config;

public class Application {
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

        get("/pi", (req, resp) -> String.valueOf(Math.PI));

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

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
