package eu.wohlben.qits.workspaces.error;

/** Domain error mapped to HTTP 413 by the web layer. */
public class PayloadTooLargeException extends DomainException {

  public PayloadTooLargeException(String message) {
    super(413, message);
  }
}
