package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/** The short-lived bearer a workspaces process presents exclusively to qits-githost. */
public interface GitHostBearer {

  /** A current access token for qits-githost, or empty when the issuer cannot provide one. */
  Optional<String> token();
}
