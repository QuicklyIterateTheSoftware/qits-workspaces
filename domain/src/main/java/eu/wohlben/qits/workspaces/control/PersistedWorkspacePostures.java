package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * The shipped {@link WorkspacePostures}: admin mode is a column on the workspace row, so it is read
 * back from there.
 *
 * <p>Reads through the ACTIVE finder, like {@link PersistedWorkspaceCredentials} beside it — a
 * resolved workspace has no container to describe, and an unknown id is not an admin workspace.
 * Every failure direction here therefore falls to <b>false</b>, which is the only direction a
 * privilege may fall to.
 */
@ApplicationScoped
public class PersistedWorkspacePostures implements WorkspacePostures {

  @Inject WorkspaceRepository workspaces;

  @Override
  @Transactional
  public boolean isAdmin(Long rowId) {
    if (rowId == null) {
      return false;
    }
    return workspaces.findActiveById(rowId).map(w -> w.admin).orElse(false);
  }
}
