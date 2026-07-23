package eu.wohlben.qits.domain.repository.control;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Awaits the in-container workspace-daemon's <b>autonomous self-provision</b> — on boot the daemon
 * clones {@code /workspace} and materializes submodules entirely from its injected env, then
 * reports the outcome up over the control socket. This is the qits-side await; it sends the daemon
 * nothing (the whole point of the autonomous model — docs/epics/qits-workspace-daemon/ Part 1).
 * Framework-free so it lives in {@code domain}; the real implementation is the backend {@code
 * WorkspaceDaemonRegistry} (service module), reached over the socket.
 *
 * <p>Apps without the backend impl (cli, tests with no daemon) have no bean — {@link
 * WorkspaceService} injects it as {@code Instance<>} and, finding it empty, runs the host-driven
 * clone (exactly today's behaviour). Distinct from {@link WorkspaceDaemonLiveness}, which is
 * observational only; this one drives behaviour.
 */
public interface WorkspaceDaemonProvisioner {

  /**
   * Wait for workspace {@code workspaceId}'s daemon to self-provision.
   *
   * @param connectTimeout how long to wait for a daemon to become live at all; if none does,
   *     returns {@link Optional#empty()} and the caller falls back to the host-driven clone (the
   *     "socket down ⇒ exactly today's behaviour" degradation contract)
   * @param provisionTimeout how long, once a daemon is live, to wait for the terminal outcome; a
   *     timeout resolves to a failed {@link ProvisionResult} (the caller then removes the container
   *     + marks FAILED — a daemon that took ownership may have left a half-populated {@code
   *     /workspace}, so falling back to a host clone would fail anyway)
   * @param onLine receives streamed clone/submodule output for the {@code clone} process segment;
   *     may be {@code null}
   * @return the provision outcome, or empty when no daemon became live within {@code
   *     connectTimeout}
   */
  Optional<ProvisionResult> awaitProvision(
      String workspaceId,
      Duration connectTimeout,
      Duration provisionTimeout,
      Consumer<String> onLine);
}
