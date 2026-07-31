package eu.wohlben.qits.workspaces.error;

/**
 * The version bump could not be applied: a manifest the stack detector claimed is missing,
 * unparseable, or shaped in a way the splice cannot address without guessing.
 *
 * <p>Mapped to HTTP 500 rather than 400, because the request was fine — the repository's own files
 * are not. It is a <i>distinct</i> failure mode from a merge conflict (409) on purpose: an integrate
 * that cannot render the version has produced nothing, and the caller needs to be told which file
 * stopped it rather than being handed a generic failure.
 *
 * <p>Failing loudly is the design. A bump that silently skips a manifest ships a release whose
 * artifacts still carry the previous version, which is discovered much later and much more
 * expensively than an error at integrate time.
 */
public class VersionBumpException extends DomainException {

  public VersionBumpException(String message) {
    super(500, message);
  }

  public VersionBumpException(String message, Throwable cause) {
    super(500, message, cause);
  }
}
