package co.edu.escuelaing.webframework;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP server with a fixed pool of worker threads. The accept loop only accepts connections and
 * hands each one to the pool, so a slow request no longer blocks the others.
 *
 * <p>Graceful shutdown ({@link #stop()}): stop accepting new connections, let the requests already
 * accepted finish (up to the shutdown timeout), then interrupt whatever is still running.
 */
public class HttpServer {
    public static final int DEFAULT_WORKER_THREADS = 16;
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(8);

    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_HEADERS = 100;
    private static final int READ_TIMEOUT_MILLIS = 3000;
    private static final int ACCEPT_BACKLOG = 128;

    private final Router router;
    private final StaticFileService staticFileService;
    private final int workerThreads;
    private final Duration shutdownTimeout;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger activeRequests = new AtomicInteger();

    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;

    public HttpServer(Router router, StaticFileService staticFileService) {
        this(router, staticFileService, DEFAULT_WORKER_THREADS, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    public HttpServer(Router router, StaticFileService staticFileService, int workerThreads, Duration shutdownTimeout) {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1");
        }
        this.router = router;
        this.staticFileService = staticFileService;
        this.workerThreads = workerThreads;
        this.shutdownTimeout = shutdownTimeout;
    }

    /** Blocks until the server has stopped and every accepted request has been drained. */
    public void start(int port) throws IOException {
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, new WorkerThreadFactory());
        try (ServerSocket socket = new ServerSocket(port, ACCEPT_BACKLOG, InetAddress.getByName("0.0.0.0"))) {
            serverSocket = socket;
            running = true;
            started.countDown();
            System.out.println("Server listening on port " + socket.getLocalPort()
                    + " with " + workerThreads + " worker threads");

            while (running) {
                Socket clientSocket;
                try {
                    clientSocket = socket.accept();
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Accept failed: " + e.getMessage());
                    }
                    continue; // stop() closed the socket: the loop condition ends it
                }
                try {
                    workers.execute(() -> serve(clientSocket));
                } catch (RejectedExecutionException e) {
                    closeQuietly(clientSocket);
                }
            }
        } finally {
            running = false;
            drain(workers);
            stopped.countDown();
        }
        System.out.println("Server stopped gracefully.");
    }

    /**
     * Starts the graceful shutdown and returns immediately; safe to call from a request handler.
     * Use {@link #awaitStopped()} to wait until in-flight requests are drained.
     */
    public void stop() {
        running = false;
        closeQuietly(serverSocket);
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    public void awaitStarted() throws InterruptedException {
        started.await();
    }

    public void awaitStopped() throws InterruptedException {
        stopped.await();
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    private void drain(ExecutorService workers) {
        workers.shutdown(); // accepted connections still run, no new ones are taken
        int inFlight = activeRequests.get();
        if (inFlight > 0) {
            System.out.println("Shutting down: waiting up to " + shutdownTimeout.toSeconds()
                    + "s for " + inFlight + " in-flight request(s)");
        }
        try {
            if (!workers.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                System.err.println("Shutdown timeout reached, interrupting "
                        + activeRequests.get() + " request(s)");
                workers.shutdownNow();
                workers.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void serve(Socket clientSocket) {
        activeRequests.incrementAndGet();
        try (clientSocket) {
            clientSocket.setSoTimeout(READ_TIMEOUT_MILLIS);
            handleRequest(clientSocket);
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    void handleRequest(Socket clientSocket) throws IOException {
        Response response;
        String logLine;
        try {
            InputStream in = new BufferedInputStream(clientSocket.getInputStream());
            Request request = readRequest(in);
            if (request == null) {
                return; // empty connection
            }
            response = dispatch(request);
            logLine = request.getMethod() + " " + request.getPath();
        } catch (MalformedRequestException e) {
            response = Response.text(400, "400 Bad Request");
            logLine = "MALFORMED (" + e.getMessage() + ")";
        } catch (SocketTimeoutException e) {
            System.err.println("Client timed out before sending a full request");
            return;
        }
        response.writeTo(clientSocket.getOutputStream());
        System.out.println("[" + Thread.currentThread().getName() + "] " + logLine + " -> " + response.getStatusCode());
    }

    private Response dispatch(Request request) {
        Route route = router.findRoute(request.getMethod(), request.getPath());
        if (route != null) {
            return runRoute(route, request);
        }

        if (!request.getMethod().equals("GET")) {
            Response notAllowed = Response.text(405, "405 Method Not Allowed");
            notAllowed.setAllow("GET");
            return notAllowed;
        }

        byte[] content = staticFileService.readResource(request.getPath());
        if (content != null) {
            Response response = new Response();
            response.setContentType(staticFileService.getMimeType(request.getPath()));
            response.setBody(content);
            return response;
        }
        return Response.text(404, "404 Not Found");
    }

    private Response runRoute(Route route, Request request) {
        Response response = new Response();
        try {
            response.setBody(route.handle(request, response));
        } catch (RuntimeException e) {
            System.err.println("Handler failed for " + request.getPath() + ": " + e);
            return Response.text(500, "500 Internal Server Error");
        }
        return response;
    }

    private Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (headers.size() >= MAX_HEADERS) {
                throw new MalformedRequestException("Too many headers");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        return new Request(requestLine, headers);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
            if (buffer.size() > MAX_LINE_BYTES) {
                throw new MalformedRequestException("Header line too long");
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // already closed
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            return new Thread(task, "http-worker-" + counter.incrementAndGet());
        }
    }
}
