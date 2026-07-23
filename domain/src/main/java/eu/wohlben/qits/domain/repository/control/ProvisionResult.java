package eu.wohlben.qits.domain.repository.control;

/**
 * The outcome of the in-container workspace-daemon's autonomous self-provision (clone + submodule
 * materialization on boot — docs/epics/qits-workspace-daemon/ Part 1), as reported over the control
 * socket. {@code ok} true carries the checked-out {@code head}; {@code ok} false carries a failure
 * {@code message} (the host then removes the container and marks the workspace {@code FAILED},
 * parity with the old host-driven clone's failure path). Framework-free so it lives in {@code
 * domain}; {@link WorkspaceDaemonProvisioner} returns it.
 */
public record ProvisionResult(boolean ok, String head, String message) {

  public static ProvisionResult ok(String head) {
    return new ProvisionResult(true, head, null);
  }

  public static ProvisionResult failed(String message) {
    return new ProvisionResult(false, null, message);
  }
}
