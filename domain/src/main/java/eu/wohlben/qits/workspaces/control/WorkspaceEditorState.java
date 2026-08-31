package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The live web-editor state a workspace's in-container {@code workspace-daemon} reported over its
 * dial-home socket. Framework-free (no websockets type) so it lives in {@code domain}; the {@code
 * service} module implements it over {@code WorkspaceDaemonRegistry}, and {@link EditorService}
 * reads it as an {@code Instance<>} that is simply empty in apps without the backend. Sibling of
 * {@link WorkspaceAgentActivity} and {@link WorkspaceGitStatus}, down to the shape of the answer.
 *
 * <h2>This is a seam, and today it is only that</h2>
 *
 * <p><b>Nothing implements it yet.</b> The daemon sends its state as an {@code EditorState} frame on
 * the control socket (capability version 5), and both halves of that — the vendored protocol module
 * and the registry's handling of the frame — land with the editor proxy route, not here. Until they
 * do, this port is unsatisfied, {@link EditorService} answers {@code editorState: null} and {@code
 * editorReady: false}, and the editor page waits. That is the correct behaviour for a platform whose
 * daemons cannot report yet, and it is why the door could be built and shipped first.
 *
 * <p><b>What filling it in looks like</b>, so there is one obvious place and not a search: {@code
 * WorkspaceDaemonRegistry} adds this interface to its {@code implements} list, caches the reported
 * value per workspace row id in a {@code ConcurrentHashMap} beside {@code gitClean}/{@code
 * agentActivity}, clears the entry on {@code unregister} (a disconnect means nothing is known, not
 * that the editor ended), and re-fills it from the frame the daemon sends on every connect. The
 * caching rules are the ones the agent-activity rollup already documents — a live report always
 * wins, and an absence is {@link Optional#empty()} rather than a state.
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
