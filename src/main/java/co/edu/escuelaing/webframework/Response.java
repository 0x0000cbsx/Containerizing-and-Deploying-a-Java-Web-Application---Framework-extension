package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class Response {
    private int statusCode = 200;
    private String contentType = "text/plain; charset=utf-8";
    private String allow;
    private byte[] body = new byte[0];

    public static Response text(int statusCode, String message) {
        Response response = new Response();
        response.setStatusCode(statusCode);
        response.setBody(message);
        return response;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setAllow(String allow) {
        this.allow = allow;
    }

    public void setBody(String value) {
        this.body = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    public void setBody(byte[] value) {
        this.body = value == null ? new byte[0] : value;
    }

    public String getBody() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public byte[] getBodyBytes() {
        return body;
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(statusCode).append(' ').append(reasonPhrase(statusCode)).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
        if (allow != null) {
            head.append("Allow: ").append(allow).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        outputStream.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(body);
        outputStream.flush();
    }

    static String reasonPhrase(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
    }
}
