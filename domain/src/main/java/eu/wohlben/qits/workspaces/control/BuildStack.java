package eu.wohlben.qits.workspaces.control;

/**
 * A build stack whose manifests render the release version. Detection decides which <i>files</i> a
 * version is written into; it never decides whether there is a version — the stamp comes from the
 * clock regardless, and a repository with no manifests still gets the same {@code release(…)} merge
 * commit. The commit is the release; the files are one stack's rendering of it.
 */
public enum BuildStack {
  MAVEN,
  NPM
}
