package eu.wohlben.qits.domain.error;

/** Domain error mapped to HTTP 404 by the web layer. */
public class NotFoundException extends DomainException {

  public NotFoundException(String message) {
    super(404, message);
  }

  public NotFoundException(String message, Throwable cause) {
    super(404, message, cause);
  }
}
