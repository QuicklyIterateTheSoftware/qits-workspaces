package eu.wohlben.qits.domain.error;

/**
 * Base for domain-layer errors. Carries an HTTP-ish status code so the web layer can map it to a
 * response without the domain depending on JAX-RS. The {@code service} module maps these via {@code
 * DomainExceptionMapper}.
 */
public class DomainException extends RuntimeException {

  private final int statusCode;

  public DomainException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public DomainException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
