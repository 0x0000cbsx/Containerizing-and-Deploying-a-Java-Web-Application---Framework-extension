package co.edu.escuelaing.webframework;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Request {
    private final String method;
    private final String path;
    private final String version;
    private final Map<String, String> queryParams = new HashMap<>();
    private final Map<String, String> headers;

    public Request(String requestLine) {
        this(requestLine, Collections.emptyMap());
    }

    public Request(String requestLine, Map<String, String> headers) {
        if (requestLine == null || requestLine.isBlank()) {
            throw new MalformedRequestException("Empty request line");
        }
        String[] parts = requestLine.trim().split("\\s+");
        if (parts.length != 3) {
            throw new MalformedRequestException("Request line must be '<METHOD> <TARGET> <VERSION>'");
        }
        if (!parts[0].matches("[A-Za-z]+")) {
            throw new MalformedRequestException("Invalid HTTP method");
        }
        if (!parts[2].matches("HTTP/\\d(\\.\\d)?")) {
            throw new MalformedRequestException("Invalid HTTP version");
        }
        String target = parts[1];
        if (!target.startsWith("/")) {
            throw new MalformedRequestException("Request target must start with '/'");
        }

        this.method = parts[0].toUpperCase(Locale.ROOT);
        this.version = parts[2];
        this.headers = headers;

        int fragmentIndex = target.indexOf('#');
        if (fragmentIndex >= 0) {
            target = target.substring(0, fragmentIndex);
        }
        int queryIndex = target.indexOf('?');
        String rawPath = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        try {
            // in a path '+' is a plus sign, not a space
            this.path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (queryIndex >= 0) {
                parseQueryString(target.substring(queryIndex + 1));
            }
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("Invalid percent-encoding in request target", e);
        }
    }

    private void parseQueryString(String queryString) {
        if (queryString.isBlank()) {
            return;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
            String value = keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
            queryParams.putIfAbsent(key, value);
        }
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public String getValue(String key) {
        return queryParams.get(key);
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}
