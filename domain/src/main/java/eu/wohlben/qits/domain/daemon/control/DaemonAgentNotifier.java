package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.entity.CommandKind;
import eu.wohlben.qits.domain.command.persistence.CommandRepository;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * The agent sink: turns a daemon event into a {@code [daemon:<name>]}-prefixed user message on the
 * stdin of the newest running stream-json chat in the same worktree (the server-side twin of the
 * frontend's newest-running-chat rule). The message rides the chat's normal unified stream, so it
 * shows in the transcript and persists like any user turn. With no running chat it is spooled and
 * delivered when the next session starts. Runs on supervisor/reader threads, hence the explicit
 * request-context activation for the query.
 */
@ApplicationScoped
public class DaemonAgentNotifier {

  private static final Logger LOG = Logger.getLogger(DaemonAgentNotifier.class);

  @Inject CommandRepository commandRepository;

  @Inject CommandRegistry registry;

  @Inject DaemonEventSpool spool;

  @ActivateRequestContext
  @Transactional
  public void deliver(DaemonEventDto event) {
    String message = format(event);
    String chatId =
        commandRepository
            .findRunningByKindAndWorktree(CommandKind.CHAT, event.repoId(), event.worktreeId())
            .stream()
            .map(command -> command.id)
            // The DB row can outlive the process (or lag its start); the registry is the truth.
            .filter(registry::isRunning)
            .findFirst()
            .orElse(null);
    if (chatId != null && registry.chatSend(chatId, message)) {
      LOG.debugf("Daemon event delivered to chat %s: %s", chatId, event.summary());
      return;
    }
    spool.add(event.repoId(), event.worktreeId(), message);
  }

  /** Visible for the spool path and tests: the exact message injected into the conversation. */
  static String format(DaemonEventDto event) {
    StringBuilder message = new StringBuilder("[daemon:").append(event.daemonName());
    if (event.source() != null && !ObservedLine.PROCESS_OUTPUT.equals(event.source())) {
      // Say where the evidence came from when it wasn't the process output (a tailed file).
      message.append(':').append(event.source());
    }
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
