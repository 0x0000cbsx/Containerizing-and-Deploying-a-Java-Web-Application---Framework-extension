package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Concurrent request handling and graceful shutdown, tested over real sockets. */
class ConcurrencyAndShutdownTest {
    private static final int SLOW_MILLIS = 600;

    private HttpServer server;
    private Thread serverThread;
    private final ExecutorService clients = Executors.newCachedThreadPool();

    private void startServer(int workerThreads, Duration shutdownTimeout) throws Exception {
        Router router = new Router();
        router.addRoute("GET", "/fast", (req, resp) -> "fast");
        router.addRoute("GET", "/slow", (req, resp) -> {
            sleep(Long.parseLong(req.getValue("ms")));
            return "slow done on " + Thread.currentThread().getName();
        });
        server = new HttpServer(router, new StaticFileService("/webroot"), workerThreads, shutdownTimeout);
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
    void cleanUp() throws Exception {
        if (server != null) {
            server.stop();
            serverThread.join(10_000);
        }
        clients.shutdownNow();
    }

    @Test
    void slowRequestsAreHandledInParallel() throws Exception {
        startServer(8, Duration.ofSeconds(5));
        int requests = 6;

        long begin = System.nanoTime();
        List<Future<String>> responses = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            responses.add(clients.submit(() -> get("/slow?ms=" + SLOW_MILLIS)));
        }
        List<String> threads = new ArrayList<>();
        for (Future<String> response : responses) {
            String body = response.get(10, TimeUnit.SECONDS);
            assertTrue(body.startsWith("slow done on http-worker-"), body);
            threads.add(body);
        }
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        // sequentially this would take 6 x 600 ms = 3.6 s
        assertTrue(elapsedMillis < 2 * SLOW_MILLIS, "took " + elapsedMillis + " ms");
        assertTrue(threads.stream().distinct().count() > 1, "requests should run on different workers");
    }

    @Test
    void slowRequestDoesNotBlockFastRequest() throws Exception {
        startServer(4, Duration.ofSeconds(5));
        Future<String> slow = clients.submit(() -> get("/slow?ms=2000"));
        waitForActiveRequests(1);

        long begin = System.nanoTime();
        assertEquals("fast", get("/fast"));
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMillis < 1000, "fast request waited " + elapsedMillis + " ms");
        assertFalse(slow.isDone());
        slow.get(10, TimeUnit.SECONDS);
    }

    @Test
    void stopWaitsForInFlightRequestsAndRefusesNewConnections() throws Exception {
        startServer(4, Duration.ofSeconds(5));
        int port = server.getPort();
        Future<String> inFlight = clients.submit(() -> get("/slow?ms=1500"));
        waitForActiveRequests(1);

        server.stop();

        assertThrows(ConnectException.class, () -> new Socket("localhost", port).close());
        assertTrue(serverThread.isAlive(), "server must keep draining while the request runs");
        assertTrue(inFlight.get(10, TimeUnit.SECONDS).startsWith("slow done"), "in-flight request completes");
        serverThread.join(5000);
        assertFalse(serverThread.isAlive());
        assertEquals(0, server.getActiveRequests());
    }

    @Test
    void awaitStoppedReturnsOnceDrained() throws Exception {
        startServer(2, Duration.ofSeconds(5));
        Future<String> inFlight = clients.submit(() -> get("/slow?ms=800"));
        waitForActiveRequests(1);

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> {
            server.stop();
            try {
                server.awaitStopped();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        stopped.get(5, TimeUnit.SECONDS);
        assertTrue(inFlight.isDone(), "awaitStopped must not return before the request finished");
    }

    @Test
    void shutdownTimeoutInterruptsStuckRequests() throws Exception {
        startServer(2, Duration.ofSeconds(1));
        clients.submit(() -> get("/slow?ms=30000"));
        waitForActiveRequests(1);

        long begin = System.nanoTime();
        server.stop();
        serverThread.join(5000);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        assertFalse(serverThread.isAlive());
        assertTrue(elapsedMillis < 4000, "shutdown took " + elapsedMillis + " ms");
    }

    // helpers

    private void waitForActiveRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.getActiveRequests() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("request never became active");
            }
            Thread.sleep(10);
        }
    }

    private String get(String target) throws IOException {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(40_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            return text.substring(text.indexOf("\r\n\r\n") + 4);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
