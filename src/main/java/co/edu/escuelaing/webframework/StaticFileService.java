package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class StaticFileService {
    private static final String INDEX_FILE = "index.html";

    private final String classpathBase;
    private final Path directory;

    public StaticFileService(String classpathBase) {
        this.classpathBase = stripSlashes(classpathBase);
        this.directory = null;
    }

    public StaticFileService(Path directory) {
        this.classpathBase = null;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public byte[] readResource(String resourcePath) {
        String relative = toSafeRelativePath(resourcePath);
        if (relative == null) {
            return null;
        }
        try {
            return directory != null ? readFromDirectory(relative) : readFromClasspath(relative);
        } catch (IOException e) {
            return null;
        }
    }

    public String getMimeType(String resourcePath) {
        String lower = resourcePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/")) return "text/html; charset=utf-8";
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "application/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }

    private static String toSafeRelativePath(String resourcePath) {
        if (resourcePath == null || resourcePath.indexOf('\0') >= 0 || resourcePath.indexOf('\\') >= 0) {
            return null;
        }
        String relative = stripSlashes(resourcePath);
        if (relative.isEmpty() || resourcePath.endsWith("/")) {
            relative = relative.isEmpty() ? INDEX_FILE : relative + "/" + INDEX_FILE;
        }
        for (String segment : relative.split("/")) {
            if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
                return null;
            }
        }
        return relative;
    }

    private byte[] readFromDirectory(String relative) throws IOException {
        Path file = directory.resolve(relative).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    private byte[] readFromClasspath(String relative) throws IOException {
        String name = classpathBase.isEmpty() ? relative : classpathBase + "/" + relative;
        ClassLoader loader = StaticFileService.class.getClassLoader();
        URL url = loader.getResource(name);
        if (url == null) {
            return null;
        }
        // a directory is also a "resource" when running from target/classes, do not list it
        if ("file".equals(url.getProtocol()) && Files.isDirectory(Path.of(toUri(url)))) {
            return null;
        }
        try (InputStream in = url.openStream()) {
            return in.readAllBytes();
        }
    }

    private static java.net.URI toUri(URL url) throws IOException {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static String stripSlashes(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
