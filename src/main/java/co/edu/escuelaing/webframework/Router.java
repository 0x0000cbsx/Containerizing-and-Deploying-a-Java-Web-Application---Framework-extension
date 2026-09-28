package co.edu.escuelaing.webframework;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Router {
    private final Map<String, Route> routes = new HashMap<>();

    public void addRoute(String method, String path, Route route) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/': " + path);
        }
        if (route == null) {
            throw new IllegalArgumentException("Route handler must not be null");
        }
        routes.put(key(method, path), route);
    }

    public Route findRoute(String method, String path) {
        return routes.get(key(method, path));
    }

    public boolean hasPath(String path) {
        return routes.keySet().stream().anyMatch(k -> k.endsWith(" " + path));
    }

    public void clear() {
        routes.clear();
    }

    private static String key(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }
}
