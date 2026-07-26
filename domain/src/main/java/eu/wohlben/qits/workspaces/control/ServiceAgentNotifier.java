package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.ServiceEventDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * The agent sink: turns a service event into a {@code [service:<name>]}-prefixed user message on
 * the stdin of the newest running stream-json chat in the same workspace (the server-side twin of
 * the frontend's newest-running-chat rule). The message rides the chat's normal unified stream, so
 * it shows in the transcript and persists like any user turn. With no running chat it is spooled
 * and delivered when the next session starts. Runs on supervisor/reader threads, hence the explicit
 * request-context activation for the query.
 */
@ApplicationScoped
public class ServiceAgentNotifier {

  private static final Logger LOG = Logger.getLogger(ServiceAgentNotifier.class);

  /**
   * The command context's live conversations, when one is assembled with this jar. Absent, or
   * present with nothing running, both mean "spool it" — see {@link WorkspaceChatInbox}.
   */
  @Inject Instance<WorkspaceChatInbox> chats;

  @Inject ServiceEventSpool spool;

  @ActivateRequestContext
  @Transactional
  public void deliver(ServiceEventDto event) {
    String message = format(event);
    if (chats.isResolvable()
        && chats.get().deliver(event.repoId(), event.workspaceId(), message)) {
      LOG.debugf(
          "Service event delivered to the live chat in %s/%s: %s",
          event.repoId(), event.workspaceId(), event.summary());
      return;
    }
    spool.add(event.repoId(), event.workspaceId(), message);
  }

  /** Visible for the spool path and tests: the exact message injected into the conversation. */
  static String format(ServiceEventDto event) {
    StringBuilder message = new StringBuilder("[service:").append(event.serviceName());
    message.append("] ");
    if (event.severity() != null) {
      message.append(event.severity()).append(": ");
    }
    message.append(event.summary());
    if (event.logExcerpt() != null && !event.logExcerpt().isBlank()) {
      message.append("\n\nLog excerpt:\n```\n").append(event.logExcerpt()).append("\n```");
    }
    return message.toString();
  }
}
