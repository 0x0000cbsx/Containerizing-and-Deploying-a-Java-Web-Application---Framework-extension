package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WebFramework {
    private static final String DEFAULT_STATIC_LOCATION = "/webroot";

    private static final Router ROUTER = new Router();
    private static String staticLocation = DEFAULT_STATIC_LOCATION;
    private static volatile HttpServer server;

    private WebFramework() {
    }

    public static void staticfiles(String path) {
        staticLocation = path;
    }

    public static void get(String path, Route route) {
        ROUTER.addRoute("GET", path, route);
    }

    public static Route findRoute(String method, String path) {
        return ROUTER.findRoute(method, path);
    }

    public static void reset() {
        ROUTER.clear();
        staticLocation = DEFAULT_STATIC_LOCATION;
        server = null;
    }

    public static void start() throws IOException {
        start(Config.fromEnv().port());
    }

    public static void start(int port) throws IOException {
        HttpServer httpServer = new HttpServer(ROUTER, buildStaticFileService(Config.fromEnv()));
        server = httpServer;
        httpServer.start(port);
    }

    public static void stop() {
        HttpServer httpServer = server;
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    private static StaticFileService buildStaticFileService(Config config) {
        String external = config.staticFilesPath();
        if (external == null) {
            return new StaticFileService(staticLocation);
        }
        Path directory = Path.of(external);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("STATIC_FILES_PATH is not a directory: " + external);
        }
        System.out.println("Serving static files from external directory " + directory.toAbsolutePath());
        return new StaticFileService(directory);
    }
}
