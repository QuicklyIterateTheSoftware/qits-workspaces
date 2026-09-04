package eu.wohlben.qits.workspaces.bus;

import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.workspaces.control.PushCausation;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * {@link PushCausation} over the event bus's ambient cause — this service's whole remaining share
 * of the causation chain, now that the {@code SCMRelease} publisher has gone to qits-projects.
 *
 * <p>It lives in {@code bus/} because the port is in {@code domain/…/control}, the implementation is
 * where the bus is, and {@code domain} stays free of {@code
 * eu.wohlben.qits.eventstream}. Three lines, and everything interesting is in what fills {@link
 * CausationScope} rather than here:
 *
 * <ul>
 *   <li>an inbound request carrying {@code X-Qits-Causation-Id} — the bus jar's own server filter
 *       establishes the scope for the resource method, so an integrate triggered as part of a
 *       larger chain pushes under that chain's cause with nothing said here;
 *   <li>a durable frame being dispatched — a handler runs inside the arriving event's scope, so
 *       anything it pushes is caused by that event.
 * </ul>
 *
 * <p>Every ref this service moves is moved by a push, and every push goes to the git host, so this
 * one bean covers an integrate, a branch merge, a branch create and a cleanup alike. There is no
 * external remote here to leak an internal identifier to.
 */
@ApplicationScoped
public class EventstreamPushCausation implements PushCausation {

  @Override
  public String currentCauseId() {
    UUID cause = CausationScope.current();
    return cause == null ? null : cause.toString();
  }
}
