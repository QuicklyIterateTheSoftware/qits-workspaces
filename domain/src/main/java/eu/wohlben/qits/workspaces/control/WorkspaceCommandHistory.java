package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.WorkspaceCommandDto;
import java.util.List;

/**
 * The commands that ran in a workspace, for its history record.
 *
 * <p>Commands are their own bounded context and own the durable rows; this context only wants to
 * render them alongside the workspace's narrative and event timeline. The dependency direction
 * would otherwise be backwards — commands already reference workspaces.
 *
 * <p>Injected as {@code Instance<WorkspaceCommandHistory>}; absent yields an empty list, which is
 * the correct answer for a deployment with no command context rather than an error.
 */
public interface WorkspaceCommandHistory {

  /**
   * Commands that ran in the workspace with this surrogate row id, oldest first. Keyed by the row
   * id rather than {@code workspaceId} because the latter is reusable once a workspace resolves.
   */
  List<WorkspaceCommandDto> commandsFor(Long workspaceRowId);
}
