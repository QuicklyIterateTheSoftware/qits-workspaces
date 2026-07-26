package eu.wohlben.qits.workspaces.control;

/**
 * Delivery of one message to whatever agent conversation is live in a workspace.
 *
 * <p>{@link ServiceAgentNotifier} turns a service event into a {@code [service:<name>]}-prefixed
 * user turn on the newest running chat. In the monorepo it did that itself, by querying the command
 * context for running {@code CHAT} commands, filtering them against the command registry's live
 * sessions and writing to the winner's stdin — three reaches into a context this jar does not carry.
 * All three collapse into this one call, because "which chat is newest and actually running" is a
 * fact only the command context can answer; the answer was never this context's to compute.
 *
 * <p><strong>Optional</strong> ({@code Instance<T>}). Absent — or present and unable to deliver,
 * which is the same answer — means the event is spooled by {@link ServiceEventSpool} and handed to
 * the next session that starts, exactly as it already is whenever no chat happens to be running.
 * There is no behavioural difference between "no command context" and "no chat open right now".
 */
public interface WorkspaceChatInbox {

  /**
   * Deliver {@code message} to the newest running chat in this workspace.
   *
   * @return true when it was delivered; false when there is no running chat to deliver it to, in
   *     which case the caller spools it.
   */
  boolean deliver(String repoId, String workspaceId, String message);
}
