package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.control.CommandRegistry;
import eu.wohlben.qits.workspaces.entity.CommandKind;
import eu.wohlben.qits.workspaces.persistence.CommandRepository;
import eu.wohlben.qits.workspaces.dto.ServiceEventDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
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

  @Inject CommandRepository commandRepository;

  @Inject CommandRegistry registry;

  @Inject ServiceEventSpool spool;

  @ActivateRequestContext
  @Transactional
  public void deliver(ServiceEventDto event) {
    String message = format(event);
    String chatId =
        commandRepository
            .findRunningByKindAndWorkspace(CommandKind.CHAT, event.repoId(), event.workspaceId())
            .stream()
            .map(command -> command.id)
            // The DB row can outlive the process (or lag its start); the registry is the truth.
            .filter(registry::isRunning)
            .findFirst()
            .orElse(null);
    if (chatId != null && registry.chatSend(chatId, message)) {
      LOG.debugf("Service event delivered to chat %s: %s", chatId, event.summary());
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
