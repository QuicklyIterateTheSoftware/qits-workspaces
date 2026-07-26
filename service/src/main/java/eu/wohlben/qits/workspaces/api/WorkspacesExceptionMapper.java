package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.error.DomainException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps the workspaces domain's framework-free {@link DomainException}s (each carrying a status
 * code) to HTTP responses.
 *
 * <p>It lives here, in {@code service}, for the same reason the sibling {@code CiExceptionMapper}
 * does: the {@code domain} module carries no JAX-RS, which is what lets it stay a plain library jar.
 *
 * <p>Scoped to <em>this</em> context's exception type. An application that also runs the monorepo's
 * {@code eu.wohlben.qits.workspaces.error.DomainException} keeps its own mapper for it; the two coexist
 * because they map unrelated types.
 */
@Provider
public class WorkspacesExceptionMapper implements ExceptionMapper<DomainException> {

  @Override
  public Response toResponse(DomainException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
