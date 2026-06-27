package eu.wohlben.qits.domain.error;

/** Domain error mapped to HTTP 400 by the web layer. */
public class BadRequestException extends DomainException {

  public BadRequestException(String message) {
    super(400, message);
  }

  public BadRequestException(String message, Throwable cause) {
    super(400, message, cause);
  }
}
