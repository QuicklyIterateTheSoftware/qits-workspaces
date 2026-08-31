package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The live web-editor state a workspace's in-container {@code workspace-daemon} reported over its
 * dial-home socket. Framework-free (no websockets type) so it lives in {@code domain}; the {@code
 * service} module implements it over {@code WorkspaceDaemonRegistry}, and {@link EditorService}
 * reads it as an {@code Instance<>} that is simply empty in apps without the backend. Sibling of
 * {@link WorkspaceAgentActivity} and {@link WorkspaceGitStatus}, down to the shape of the answer.
 *
 * <h2>Who fills it in</h2>
 *
 * <p>{@code WorkspaceDaemonRegistry}, out of the {@code EditorState} frame the daemon sends on its
 * control socket (capability version 5): the reported value is cached per workspace row id in a
 * {@code ConcurrentHashMap} beside {@code gitClean}/{@code agentActivity}, dropped on {@code
 * unregister} (a disconnect means nothing is known, not that the editor ended), and re-filled from
 * the frame the daemon sends on every connect. The caching rules are the ones the agent-activity
 * rollup already documents — a live report always wins, and an absence is {@link Optional#empty()}
 * rather than a state.
 *
 * <p>It stays an {@code Instance<>} at both readers ({@link EditorService} and the editor proxy
 * route) rather than a hard dependency, because {@code domain} must not know that the implementation
 * is a websocket registry — and because a build with no control plane at all is a supported shape,
 * where every answer here is empty and every caller reads that as "not ready".
 *
 * <p><b>Empty is "nothing reported", never "no editor".</b> The daemon sends this frame only where
 * it supervises an editor at all, so absence covers three cases at once — a plain workspace, a
 * container that is not up, and an editor whose first frame has not arrived. A caller turns all
 * three into "not ready", which is the same answer each of them deserves.
 */
public interface WorkspaceEditorState {

  /**
   * The editor state reported for {@code workspaceRowId}, or {@link Optional#empty()} when nothing
   * has been reported (no live daemon, no editor, or not yet).
   */
  Optional<EditorLifecycle> editorStateFor(Long workspaceRowId);
}
