package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests: every component is exercised without opening a socket. */
class WebFrameworkTest {

    @AfterEach
    void resetFramework() {
        WebFramework.reset();
    }

    // Request

    @Test
    void shouldReadQueryStringValues() {
        Request request = new Request("GET /hello?name=Pedro&language=en HTTP/1.1");

        assertEquals("GET", request.getMethod());
        assertEquals("/hello", request.getPath());
        assertEquals("Pedro", request.getValue("name"));
        assertEquals("en", request.getValue("language"));
    }

    @Test
    void missingQueryParameterReturnsNull() {
        Request request = new Request("GET /hello HTTP/1.1");
        assertNull(request.getValue("name"));
        assertNull(new Request("GET /hello?other=1 HTTP/1.1").getValue("name"));
    }

    @Test
    void shouldDecodeQueryValuesAndTolerateOddPairs() {
        Request request = new Request("GET /hello?name=Ana%20Mar%C3%ADa&flag&&x=a+b HTTP/1.1");

        assertEquals("Ana María", request.getValue("name"));
        assertEquals("", request.getValue("flag"));
        assertEquals("a b", request.getValue("x"));
    }

    @Test
    void shouldReadHeadersCaseInsensitively() {
        Request request = new Request("GET / HTTP/1.1", Map.of("host", "localhost"));
        assertEquals("localhost", request.getHeader("Host"));
        assertNull(request.getHeader("X-Missing"));
    }

    @Test
    void malformedRequestLinesAreRejected() {
        assertThrows(MalformedRequestException.class, () -> new Request(""));
        assertThrows(MalformedRequestException.class, () -> new Request("GET"));
        assertThrows(MalformedRequestException.class, () -> new Request("GET /hello"));
        assertThrows(MalformedRequestException.class, () -> new Request("GET hello HTTP/1.1"));
        assertThrows(MalformedRequestException.class, () -> new Request("GET /hello FTP/1.1"));
        assertThrows(MalformedRequestException.class, () -> new Request("GET /a b c HTTP/1.1"));
        assertThrows(MalformedRequestException.class, () -> new Request("GET /hello?name=%zz HTTP/1.1"));
    }

    // Router / WebFramework

    @Test
    void shouldResolveDynamicRoutes() {
        WebFramework.get("/hello", (req, resp) -> "Hello " + req.getValue("name"));

        Route route = WebFramework.findRoute("GET", "/hello");

        assertNotNull(route);
        assertEquals("Hello Pedro", route.handle(new Request("GET /hello?name=Pedro HTTP/1.1"), new Response()));
        assertNull(WebFramework.findRoute("GET", "/unknown"));
        assertNull(WebFramework.findRoute("POST", "/hello"));
    }

    @Test
    void routerRejectsInvalidRegistrations() {
        Router router = new Router();
        assertThrows(IllegalArgumentException.class, () -> router.addRoute("GET", "hello", (q, r) -> ""));
        assertThrows(IllegalArgumentException.class, () -> router.addRoute("GET", "/hello", null));
    }

    // StaticFileService

    @Test
    void shouldServeClasspathResourcesIncludingBinaryFiles() {
        StaticFileService service = new StaticFileService("/webroot");

        byte[] html = service.readResource("/index.html");
        assertNotNull(html);
        assertTrue(new String(html, StandardCharsets.UTF_8).contains("<html"));
        assertNotNull(service.readResource("/app.js"));
        assertNotNull(service.readResource("/styles.css"));

        byte[] png = service.readResource("/images/logo.png");
        assertNotNull(png);
        assertEquals((byte) 0x89, png[0]); // PNG signature
        assertEquals('P', png[1]);
        assertEquals("image/png", service.getMimeType("/images/logo.png"));
    }

    @Test
    void rootAndDirectoriesMapToIndexOrNothing() {
        StaticFileService service = new StaticFileService("/webroot");
        assertNotNull(service.readResource("/"));
        assertNull(service.readResource("/images")); // no directory listings
        assertNull(service.readResource("/nope.html"));
    }

    @Test
    void shouldRejectPathTraversal() {
        StaticFileService service = new StaticFileService("/webroot");
        assertNull(service.readResource("/../pom.xml"));
        assertNull(service.readResource("/images/../../pom.xml"));
        assertNull(service.readResource("/..\\pom.xml"));
        assertNull(service.readResource("/index.html\0.png"));
    }

    @Test
    void shouldServeFromExternalDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.html"), "<p>external</p>");
        Files.writeString(dir.getParent().resolve("secret.txt"), "secret");
        StaticFileService service = new StaticFileService(dir);

        assertEquals("<p>external</p>", new String(service.readResource("/page.html"), StandardCharsets.UTF_8));
        assertNull(service.readResource("/missing.html"));
        assertNull(service.readResource("/../secret.txt"));
    }

    @Test
    void mimeTypesAreDetectedByExtension() {
        StaticFileService service = new StaticFileService("/webroot");
        assertEquals("text/html; charset=utf-8", service.getMimeType("/index.html"));
        assertEquals("text/css; charset=utf-8", service.getMimeType("/styles.css"));
        assertEquals("application/javascript; charset=utf-8", service.getMimeType("/app.js"));
        assertEquals("image/jpeg", service.getMimeType("/a.JPG"));
        assertEquals("application/octet-stream", service.getMimeType("/file.unknown"));
    }

    // Config

    @Test
    void portDefaultsTo8080AndReadsPortVariable() {
        assertEquals(8080, new Config(Map.of()).port());
        assertEquals(8080, new Config(Map.of("PORT", "  ")).port());
        assertEquals(9000, new Config(Map.of("PORT", "9000")).port());
    }

    @Test
    void invalidPortFailsFastWithClearMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("PORT", "abc")).port());
        assertTrue(e.getMessage().contains("PORT"));
        assertThrows(IllegalArgumentException.class, () -> new Config(Map.of("PORT", "70000")).port());
    }

    @Test
    void getFallsBackToDefaultForMissingOrBlankValues() {
        Config config = new Config(Map.of("APP_ENV", "production", "GREETING_PREFIX", " "));
        assertEquals("production", config.get("APP_ENV", "development"));
        assertEquals("Hello", config.get("GREETING_PREFIX", "Hello"));
        assertEquals("x", config.get("MISSING", "x"));
        assertNull(config.staticFilesPath());
    }
}
