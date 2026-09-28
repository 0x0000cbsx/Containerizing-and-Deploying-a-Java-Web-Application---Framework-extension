package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests over real TCP sockets. The server runs on one background thread only so the
 * test can act as a client; the server itself still handles one connection at a time.
 */
class HttpServerIntegrationTest {
    private HttpServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        Router router = new Router();
        router.addRoute("GET", "/hello", (req, resp) -> {
            String name = req.getValue("name");
            return "Hello " + (name == null || name.isBlank() ? "world" : name);
        });
        router.addRoute("GET", "/pi", (req, resp) -> String.valueOf(Math.PI));
        router.addRoute("GET", "/multi", (req, resp) -> req.getValue("name") + "/" + req.getValue("language"));
        router.addRoute("GET", "/boom", (req, resp) -> {
            throw new IllegalStateException("handler failure");
        });
        router.addRoute("GET", "/shutdown", (req, resp) -> {
            server.stop();
            return "Server will stop after this response.";
        });

        server = new HttpServer(router, new StaticFileService("/webroot"));
        serverThread = new Thread(() -> {
            try {
                server.start(0);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, "test-server");
        serverThread.start();
        server.awaitStarted();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        serverThread.join(5000);
        assertFalse(serverThread.isAlive(), "server thread should have terminated");
    }

    @Test
    void dynamicRouteReturnsLambdaResult() throws Exception {
        Http res = get("/hello?name=Pedro");
        assertEquals(200, res.status);
        assertEquals("Hello Pedro", res.bodyText());
        assertTrue(res.headers.contains("Content-Type: text/plain"));
    }

    @Test
    void secondDynamicRouteWorks() throws Exception {
        assertEquals(String.valueOf(Math.PI), get("/pi").bodyText());
    }

    @Test
    void missingQueryParameterDoesNotBreakTheServer() throws Exception {
        assertEquals("Hello world", get("/hello").bodyText());
        assertEquals("Hello world", get("/hello?name=").bodyText());
    }

    @Test
    void multipleQueryParametersAreSupported() throws Exception {
        assertEquals("Pedro/en", get("/multi?name=Pedro&language=en").bodyText());
    }

    @Test
    void staticHtmlCssAndJavaScriptAreServedWithCorrectTypes() throws Exception {
        Http html = get("/index.html");
        assertEquals(200, html.status);
        assertTrue(html.headers.contains("Content-Type: text/html"));
        assertTrue(html.bodyText().contains("<html"));
        assertEquals(html.bodyText(), get("/").bodyText());

        assertTrue(get("/styles.css").headers.contains("Content-Type: text/css"));
        assertTrue(get("/app.js").headers.contains("Content-Type: application/javascript"));
    }

    @Test
    void binaryImageIsServedByteForByte() throws Exception {
        byte[] expected;
        try (InputStream in = getClass().getResourceAsStream("/webroot/images/logo.png")) {
            expected = in.readAllBytes();
        }
        Http res = get("/images/logo.png");

        assertEquals(200, res.status);
        assertTrue(res.headers.contains("Content-Type: image/png"));
        assertTrue(res.headers.contains("Content-Length: " + expected.length));
        assertArrayEquals(expected, res.body);
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        Http res = get("/unknown");
        assertEquals(404, res.status);
        assertEquals("404 Not Found", res.bodyText());
        assertTrue(res.headers.contains("Content-Type: text/plain"));
    }

    @Test
    void missingStaticFileAndTraversalReturn404() throws Exception {
        assertEquals(404, get("/images/missing.png").status);
        assertEquals(404, get("/../pom.xml").status);
        assertEquals(404, get("/%2e%2e/pom.xml").status);
    }

    @Test
    void malformedRequestsGet400AndServerKeepsRunning() throws Exception {
        assertEquals(400, raw("GARBAGE\r\n\r\n").status);
        assertEquals(400, raw("GET /hello\r\n\r\n").status);
        assertEquals(400, raw("GET hello HTTP/1.1\r\n\r\n").status);
        assertEquals(400, raw("GET /hello?x=%zz HTTP/1.1\r\n\r\n").status);
        assertEquals(200, get("/pi").status);
    }

    @Test
    void emptyConnectionDoesNotKillTheServer() throws Exception {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.shutdownOutput();
        }
        assertEquals(200, get("/pi").status);
    }

    @Test
    void nonGetMethodIsRejectedWith405() throws Exception {
        Http res = raw("POST /hello HTTP/1.1\r\nHost: x\r\n\r\n");
        assertEquals(405, res.status);
        assertTrue(res.headers.contains("Allow: GET"));
    }

    @Test
    void failingHandlerReturns500AndServerKeepsRunning() throws Exception {
        assertEquals(500, get("/boom").status);
        assertEquals(200, get("/pi").status);
    }

    @Test
    void manySequentialRequestsAreAllAnswered() throws Exception {
        for (int i = 0; i < 25; i++) {
            assertEquals("Hello n" + i, get("/hello?name=n" + i).bodyText());
        }
    }

    @Test
    void shutdownRouteAnswersThenStopsTheServerGracefully() throws Exception {
        int port = server.getPort();

        Http res = get("/shutdown");
        assertEquals(200, res.status); // response arrives complete
        assertEquals("Server will stop after this response.", res.bodyText());

        serverThread.join(5000); // and then the loop ends
        assertFalse(serverThread.isAlive());
        assertFalse(server.isRunning());
        assertThrows(ConnectException.class, () -> new Socket("localhost", port).close());
    }

    // helpers

    private Http get(String target) throws IOException {
        return raw("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    private Http raw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            return new Http(all.toByteArray());
        }
    }

    private static final class Http {
        final int status;
        final String headers;
        final byte[] body;

        Http(byte[] bytes) {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            this.headers = text.substring(0, split);
            this.body = Arrays.copyOfRange(bytes, split + 4, bytes.length);
            this.status = Integer.parseInt(headers.split(" ")[1]);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
