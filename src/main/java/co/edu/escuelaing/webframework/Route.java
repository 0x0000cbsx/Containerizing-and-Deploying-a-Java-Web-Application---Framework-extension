package co.edu.escuelaing.webframework;

@FunctionalInterface
public interface Route {
    String handle(Request req, Response resp);
}
