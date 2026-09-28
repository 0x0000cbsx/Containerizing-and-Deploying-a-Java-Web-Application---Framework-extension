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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class HttpServer {
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_HEADERS = 100;
    private static final int READ_TIMEOUT_MILLIS = 3000;

    private final Router router;
    private final StaticFileService staticFileService;
    private final CountDownLatch started = new CountDownLatch(1);

    private volatile boolean running = false;
    private volatile boolean handlingRequest = false;
    private volatile ServerSocket serverSocket;

    public HttpServer(Router router, StaticFileService staticFileService) {
        this.router = router;
        this.staticFileService = staticFileService;
    }

    public void start(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))) {
            serverSocket = socket;
            running = true;
            started.countDown();
            System.out.println("Server listening on port " + socket.getLocalPort());

            while (running) {
                try (Socket clientSocket = socket.accept()) {
                    handlingRequest = true;
                    clientSocket.setSoTimeout(READ_TIMEOUT_MILLIS);
                    handleRequest(clientSocket);
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Connection error: " + e.getMessage());
                    }
                } finally {
                    handlingRequest = false;
                }
            }
        } finally {
            running = false;
        }
        System.out.println("Server stopped gracefully.");
    }

    public void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        if (!handlingRequest && socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // socket was already closed
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void awaitStarted() throws InterruptedException {
        started.await();
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
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
        System.out.println(logLine + " -> " + response.getStatusCode());
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
}
