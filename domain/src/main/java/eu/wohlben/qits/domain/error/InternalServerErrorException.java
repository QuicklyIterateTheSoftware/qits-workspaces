package eu.wohlben.qits.domain.error;

/** Domain error mapped to HTTP 500 by the web layer. */
public class InternalServerErrorException extends DomainException {

  public InternalServerErrorException(String message) {
    super(500, message);
  }

  public InternalServerErrorException(String message, Throwable cause) {
    super(500, message, cause);
  }
}
