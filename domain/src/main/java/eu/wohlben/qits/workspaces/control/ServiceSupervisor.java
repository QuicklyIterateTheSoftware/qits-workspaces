package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.control.TechnicalProcess;
import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceConfigReader;
import eu.wohlben.qits.workspaces.control.WorkspaceServiceDriver;
import eu.wohlben.qits.workspaces.dto.HealthCheckDto;
import eu.wohlben.qits.workspaces.dto.HealthCheckState;
import eu.wohlben.qits.workspaces.dto.HealthCheckStatusDto;
import eu.wohlben.qits.workspaces.dto.ServiceDefinitionDto;
import eu.wohlben.qits.workspaces.dto.ServiceEventDto;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.entity.ServiceEventKind;
import eu.wohlben.qits.workspaces.entity.ServiceEventSeverity;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import eu.wohlben.qits.workspaces.mapper.ServiceDefinitionMapper;
import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint;
import eu.wohlben.qits.workspaces.control.WorkspaceChangePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Host-side <b>projection</b> of the in-container {@code workspace-daemon}'s services (dev
 * servers). The daemon is the sole executor <em>and</em> supervisor: it spawns each declared
 * service, applies the restart policy with backoff, group-kills escaped forks, and pushes every
 * lifecycle transition home as a {@code ServiceTransition} over the control socket
 * (docs/epics/qits-workspace-daemon/ Part 4). This class keeps no process of its own — it
 * {@linkplain #subscribeProjection subscribes} once at startup and mirrors the streamed events onto
 * a thin display state machine ({@code STARTING → READY → RESTARTING → CRASHED → STOPPED}),
 * settling {@code service:<name>} process segments and resolving the web-view proxy origin. The
 * only outbound operations are the <b>subsequent</b> ones the daemon can't self-initiate: a
 * {@linkplain #start manual start} and a {@linkplain #stop stop signal}, both delegated over the
 * {@link WorkspaceServiceDriver}. Auto-start is entirely daemon-driven (the tail of its own boot
 * sequence), so the host never instructs it.
 *
 * <p>There is no host-execution fallback: a workspace with no live daemon simply cannot run a
 * service, and the honest state is "not available yet" (the service stays STARTING until a daemon
 * connects and reports, or a stop settles it). This replaces the former tmux/host-exec supervisor,
 * whose second restart policy and liveness poll fought the daemon over the process and the port
 * whenever the socket blipped (docs/issues/resolved/
 * 2026-07-25_host-side-service-supervision-should-move-to-daemon.md).
 *
 * <p>In-memory state is lost on a JVM restart, but the daemon survives it and <b>re-reports</b> its
 * running services on socket reconnect, so a qits restart re-adopts them through the same
 * projection sink — event-driven, no probing.
 */
@ApplicationScoped
public class ServiceSupervisor {

  private static final Logger LOG = Logger.getLogger(ServiceSupervisor.class);

  /** One projected service in one workspace. Mutated only under the supervisor monitor. */
  private static final class Instance {
    final String repoId;
    final String workspaceId;
    ServiceDefinitionDto definition;
    ServiceStatus status = ServiceStatus.STOPPED;
    int restartCount;
    boolean stopRequested;

    /** Recent streamed output, so a CRASHED transition can carry the evidence tail. */
    TailSink tail;

    /**
     * Where the service web-view proxy connects to reach the daemon's {@code webView.port} inside
     * the container — its DNS name + port on the shared network. Null when the daemon isn't
     * web-viewable; re-resolved each time the service reports READY.
     */
    ProxyOrigin origin;

    /**
     * The technical process tracking the container start that auto-started this instance, or null
     * for manual/adopted starts. Its {@code service:<name>} segment receives the settle summary on
     * the first terminal-ish transition (READY/CRASHED/STOPPED).
     */
    TechnicalProcess process;

    Instance(String repoId, String workspaceId, ServiceDefinitionDto definition) {
      this.repoId = repoId;
      this.workspaceId = workspaceId;
      this.definition = definition;
    }
  }

  private record Key(String repoId, String workspaceId, String serviceId) {}

  private final Map<Key, Instance> instances = new ConcurrentHashMap<>();

  /**
   * The in-container config read (Part 2) — the only source of service definitions since Part 5
   * removed the DB store. Absent in cli/tests without the backend (empty {@code Instance<>} ⇒ no
   * definitions).
   */
  @Inject jakarta.enterprise.inject.Instance<WorkspaceConfigReader> configReader;

  @Inject ServiceDefinitionMapper definitions;

  @Inject ServiceEventService events;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceChangePublisher changePublisher;

  /**
   * The in-container supervision driver (Part 4) — the control-socket link to the workspace-daemon
   * that owns every service's lifecycle. Its impl is the backend {@code WorkspaceDaemonRegistry};
   * absent in cli/tests without the backend (an empty {@code Instance<>}), where the host has
   * nothing to project and start/stop are no-ops.
   */
  @Inject jakarta.enterprise.inject.Instance<WorkspaceServiceDriver> serviceDriver;

  /**
   * Subscribe the projection sink once at startup, so a daemon's service events reach this
   * supervisor's state machine, segments, and proxy. No-op when the driver is absent (cli/tests).
   */
  @PostConstruct
  void subscribeProjection() {
    if (!serviceDriver.isUnsatisfied()) {
      serviceDriver.get().subscribe(new ProjectionSink());
    }
  }

  /**
   * The workspace's config-declared service definitions — the in-container {@code .qits-config.yml}
   * read via {@link WorkspaceConfigReader}, mapped to flat DTOs. Empty when no daemon is live (no
   * control socket ⇒ no config) or the file declares no services.
   */
  private List<ServiceDefinitionDto> resolveDefinitions(String workspaceId) {
    if (configReader.isUnsatisfied()) {
      return List.of();
    }
    return configReader
        .get()
        .readConfig(workspaceId)
        .map(view -> view.config().services().stream().map(definitions::toDto).toList())
        .orElse(List.of());
  }

  /**
   * Start {@code serviceId} (the config-declared {@code id:}) in the workspace. One running
   * instance per (workspace, service) is enforced — "restart" beats two dev servers fighting over a
   * port.
   */
  public synchronized ServiceInstanceDto start(
      String repoId, String workspaceId, String serviceId) {
    return start(repoId, workspaceId, serviceId, null);
  }

  /**
   * {@link #start(String, String, String)} with an optional {@link TechnicalProcess}: the auto
   * start path passes the container start's process so this service's startup settles into its
   * {@code service:<name>} segment.
   *
   * <p>Either way the host only <em>registers a projection</em>. An auto-start (process != null,
   * from the lifecycle coupler) needs no instruction — the daemon self-starts the service from its
   * in-container config, and we pre-register so its streamed events settle this segment/status. A
   * manual start (process == null) asks the daemon to start it now over the socket.
   */
  public synchronized ServiceInstanceDto start(
      String repoId, String workspaceId, String serviceId, TechnicalProcess process) {
    ServiceDefinitionDto definition =
        resolveDefinitions(workspaceId).stream()
            .filter(d -> d.id().equals(serviceId))
            .findFirst()
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Service not declared in the workspace qits config: " + serviceId));
    Key key = new Key(repoId, workspaceId, serviceId);
    Instance existing = instances.get(key);
    if (existing != null && isLive(existing.status)) {
      throw new BadRequestException(
          "Service '" + definition.name() + "' is already running in this workspace");
    }
    Instance instance = new Instance(repoId, workspaceId, definition);
    instance.process = process;
    instance.tail = new TailSink();
    instance.status = ServiceStatus.STARTING;
    instances.put(key, instance);
    if (process == null && !serviceDriver.isUnsatisfied()) {
      serviceDriver
          .get()
          .startService(
              workspaceId, definition.name(), definition.startScript(), definition.environment());
    }
    return toInstanceDto(instance, null, workspaceId);
  }

  /**
   * Ask the daemon to stop a running instance. The daemon owns the process, so this only delivers
   * the stop signal over the socket; the daemon reports back STOPPED, which the projection sink
   * settles. Without a live driver there is nothing to signal — settle STOPPED locally so the UI
   * doesn't hang on a service the host can no longer reach.
   */
  public synchronized ServiceInstanceDto stop(String repoId, String workspaceId, String serviceId) {
    Instance instance = instances.get(new Key(repoId, workspaceId, serviceId));
    if (instance == null || !isLive(instance.status)) {
      throw new NotFoundException("Daemon is not running in this workspace");
    }
    instance.stopRequested = true;
    if (!serviceDriver.isUnsatisfied()) {
      serviceDriver
          .get()
          .signalService(workspaceId, instance.definition.name(), instance.definition.stopSignal());
    } else {
      transition(instance, ServiceStatus.STOPPED, ServiceEventSeverity.INFO, "stopped", null);
    }
    return toInstanceDto(instance, null, workspaceId);
  }

  /**
   * Settle every live service of a workspace whose container is about to be deliberately removed
   * ({@code stopContainer} / discard). Without it, the daemon's own crash/restart machinery would
   * read the imminent container disappearance as a failure and resurrect the just-stopped
   * container. Setting {@code stopRequested} first, then transitioning STOPPED here, makes the
   * settle deterministic even when the container's {@code rm} beats the daemon's STOPPED event.
   *
   * <p>Runs <em>synchronously</em> on the caller's (event-firing) thread so it completes before
   * {@code containers.rm}. {@code graceful} = true (stopContainer) additionally asks the daemon to
   * signal each service for a clean flush; false (discard) settles bookkeeping only and lets {@code
   * rm} kill the processes.
   */
  public synchronized void settleForWorkspace(String repoId, String workspaceId, boolean graceful) {
    for (Map.Entry<Key, Instance> entry : instances.entrySet()) {
      Key key = entry.getKey();
      if (!key.repoId().equals(repoId) || !key.workspaceId().equals(workspaceId)) {
        continue;
      }
      Instance instance = entry.getValue();
      if (!isLive(instance.status)) {
        continue;
      }
      instance.stopRequested = true;
      if (graceful && !serviceDriver.isUnsatisfied()) {
        serviceDriver
            .get()
            .signalService(
                workspaceId, instance.definition.name(), instance.definition.stopSignal());
      }
      transition(
          instance, ServiceStatus.STOPPED, ServiceEventSeverity.INFO, "workspace stopped", null);
    }
  }

  /** Every config-declared service of the workspace with its projected runtime state. */
  public synchronized List<ServiceInstanceDto> effectiveServices(
      String repoId, String workspaceId) {
    List<ServiceDefinitionDto> definitions = resolveDefinitions(workspaceId);
    List<ServiceInstanceDto> result = new ArrayList<>(definitions.size());
    for (ServiceDefinitionDto definition : definitions) {
      Instance instance = instances.get(new Key(repoId, workspaceId, definition.id()));
      result.add(toInstanceDto(instance, definition, workspaceId));
    }
    return result;
  }

  /**
   * Resolve where the web-view proxy reaches a web-viewable service's port — the container's DNS
   * name on the shared network + the real container port. There is no create-time port constraint,
   * so this always resolves for a web-viewable service; null only when it isn't web-viewable.
   */
  private void resolveOrigin(Instance instance) {
    Integer httpPort =
        instance.definition.webView() != null ? instance.definition.webView().port() : null;
    if (httpPort == null) {
      instance.origin = null;
      return;
    }
    String container = containers.containerName(instance.workspaceId, instance.repoId);
    instance.origin = containers.resolveTarget(container, httpPort);
  }

  /**
   * The live proxy target for a (workspaceId, serviceId) pair — the service web-view proxy's only
   * lookup. {@code serviceId} is the config-declared service id, unique within the workspace's
   * config; scoped to the workspace by the pair lookup. The port comes exclusively from projection
   * state (never from any request component) and targets localhost — the SSRF constraint. A present
   * target with a null {@code origin} means the service isn't reachable (e.g. the container is
   * gone) — the proxy 502s.
   */
  public synchronized Optional<ProxyTarget> proxyTarget(String workspaceId, String serviceId) {
    for (Map.Entry<Key, Instance> entry : instances.entrySet()) {
      Key key = entry.getKey();
      if (key.workspaceId().equals(workspaceId) && key.serviceId().equals(serviceId)) {
        Instance instance = entry.getValue();
        if (instance.definition.webView() == null) {
          return Optional.empty();
        }
        return Optional.of(new ProxyTarget(instance.status, instance.origin));
      }
    }
    return Optional.empty();
  }

  /** A web-viewable service instance as the proxy sees it: status + the container-port origin. */
  public record ProxyTarget(ServiceStatus status, ProxyOrigin origin) {}

  private static boolean isLive(ServiceStatus status) {
    return status == ServiceStatus.STARTING
        || status == ServiceStatus.READY
        || status == ServiceStatus.RESTARTING;
  }

  private ServiceInstanceDto toInstanceDto(
      Instance instance, ServiceDefinitionDto declared, String workspaceId) {
    ServiceDefinitionDto definition = declared != null ? declared : instance.definition;
    String proxyPath =
        definition.webView() != null
            ? ServiceProxyPath.servedBase(
                workspaceId, definition.id(), definition.webView().basePath())
            : null;
    if (instance == null) {
      return new ServiceInstanceDto(
          definition,
          ServiceStatus.STOPPED,
          0,
          null,
          proxyPath,
          unknownHealth(definition.healthChecks()));
    }
    return new ServiceInstanceDto(
        definition,
        instance.status,
        instance.restartCount,
        null,
        proxyPath,
        unknownHealth(definition.healthChecks()));
  }

  /**
   * Health is reported by the daemon, not probed host-side (the host runs nothing in the
   * container), so every declared check reads UNKNOWN until daemon-driven health lands (the
   * wedged-service follow-up, docs/issues/2026-07-25_wedged-workspace-service-not-recovered.md).
   * The DTO stays one entry per declared check so the UI's dots align.
   */
  private static List<HealthCheckStatusDto> unknownHealth(List<HealthCheckDto> declared) {
    if (declared == null || declared.isEmpty()) {
      return List.of();
    }
    List<HealthCheckStatusDto> result = new ArrayList<>(declared.size());
    for (HealthCheckDto check : declared) {
      result.add(
          new HealthCheckStatusDto(
              check.name(), check.kind(), HealthCheckState.UNKNOWN, null, null, null));
    }
    return result;
  }

  private void transition(
      Instance instance,
      ServiceStatus status,
      ServiceEventSeverity severity,
      String summary,
      String logExcerpt) {
    instance.status = status;
    settleProcessSegment(instance, status, summary);
    changePublisher.fire(instance.repoId, instance.workspaceId, WorkspaceChangeHint.Topic.SERVICES);
    events.publish(
        new ServiceEventDto(
            instance.repoId,
            instance.workspaceId,
            instance.definition.id(),
            instance.definition.name(),
            ServiceEventKind.STATUS_CHANGED,
            severity,
            status,
            summary,
            logExcerpt,
            null,
            null,
            null,
            null,
            null,
            Instant.now()));
  }

  /**
   * Settle a process-tracked instance's {@code service:<name>} segment on the first decisive
   * status: READY (or a deliberate STOPPED during the window) settles {@code ok}, CRASHED settles
   * {@code failed} — with the transition summary appended as the segment's closing line. RESTARTING
   * is deliberately not terminal: the segment stays open across the backoff and settles with the
   * retry's outcome. Idempotent via the process's first-verdict-wins settle.
   */
  private static void settleProcessSegment(
      Instance instance, ServiceStatus status, String summary) {
    if (instance.process == null) {
      return;
    }
    String segment = TechnicalProcess.serviceSegment(instance.definition.name());
    switch (status) {
      case READY, STOPPED -> {
        instance.process.appendLine(segment, summary);
        instance.process.settleSegment(segment, true);
      }
      case CRASHED -> {
        instance.process.appendLine(segment, summary);
        instance.process.settleSegment(segment, false);
      }
      default -> {}
    }
  }

  // --- Daemon-backed projection (Part 4) ------------------------------------------------------

  /**
   * Projects the in-container daemon's service events onto this supervisor's display state machine
   * (SSE, {@code service:<name>} segment, web-view proxy origin), reusing {@link #transition}. The
   * daemon owns the process lifecycle, so nothing here spawns/restarts/polls — this only reflects
   * what it reports. Callbacks arrive on the control-socket thread; each synchronizes on the
   * supervisor monitor like every other transition path.
   *
   * <p>Streamed lines feed the crash-excerpt {@link TailSink}.
   */
  private final class ProjectionSink implements WorkspaceServiceDriver.ServiceEventSink {

    @Override
    public void onState(
        String repoId, String workspaceId, String serviceName, String state, Integer exitCode) {
      if (repoId == null) {
        return; // no repo context (daemon Hello not yet seen) — can't resolve the definition
      }
      ServiceStatus mapped = mapStatus(state);
      if (mapped == null) {
        LOG.debugf("Ignoring unknown service state '%s' for '%s'", state, serviceName);
        return;
      }
      synchronized (ServiceSupervisor.this) {
        Instance instance = findByName(repoId, workspaceId, serviceName);
        if (instance == null) {
          // The daemon reports a service the host didn't pre-register (a reconnect re-report after
          // a qits restart, or a config-declared service with no coupler run) — event-driven
          // adoption.
          ServiceDefinitionDto definition = resolveDefinition(workspaceId, serviceName);
          if (definition == null) {
            LOG.debugf(
                "Ignoring event for service '%s' with no config definition in workspace %s",
                serviceName, workspaceId);
            return;
          }
          instance = new Instance(repoId, workspaceId, definition);
          instance.tail = new TailSink();
          instances.put(new Key(repoId, workspaceId, definition.id()), instance);
        }
        if (mapped == ServiceStatus.READY) {
          resolveOrigin(instance); // the service is bound now — resolve the proxy target
        }
        if (mapped == ServiceStatus.RESTARTING) {
          instance.restartCount++;
        }
        String excerpt =
            mapped == ServiceStatus.CRASHED && instance.tail != null
                ? instance.tail.excerpt()
                : null;
        transition(instance, mapped, severityFor(mapped), summaryFor(mapped, exitCode), excerpt);
      }
    }

    @Override
    public void onLine(
        String repoId, String workspaceId, String serviceName, String stream, String line) {
      synchronized (ServiceSupervisor.this) {
        Instance instance = findByName(repoId, workspaceId, serviceName);
        if (instance == null) {
          return;
        }
        if (instance.tail != null) {
          instance.tail.write(line + "\n"); // feeds the crash excerpt on a later CRASHED transition
        }
        // A process-tracked auto-start streams its startup output into the start's service segment,
        // so "clone → bootstrap → services" shows the dev server booting. appendLine self-limits:
        // it drops lines once the segment settles (READY/CRASHED) or the process is terminal.
        if (instance.process != null) {
          instance.process.appendLine(
              TechnicalProcess.serviceSegment(instance.definition.name()), line);
        }
      }
    }
  }

  /**
   * Find a repository-workspace's projected instance by service name (caller holds the monitor).
   * Keyed by repoId too: a workspace slug like {@code work} repeats across repositories, so name +
   * slug alone would cross-match another repo's service.
   */
  private Instance findByName(String repoId, String workspaceId, String serviceName) {
    for (Instance instance : instances.values()) {
      if (instance.repoId.equals(repoId)
          && instance.workspaceId.equals(workspaceId)
          && instance.definition.name().equals(serviceName)) {
        return instance;
      }
    }
    return null;
  }

  /**
   * Resolve a workspace's config-declared service definition by service name (the orphan case is
   * null).
   */
  private ServiceDefinitionDto resolveDefinition(String workspaceId, String serviceName) {
    try {
      for (ServiceDefinitionDto definition : resolveDefinitions(workspaceId)) {
        if (definition.name().equals(serviceName)) {
          return definition;
        }
      }
    } catch (RuntimeException e) {
      LOG.debugf(
          e, "service definition lookup failed for '%s' in workspace %s", serviceName, workspaceId);
    }
    return null;
  }

  private static ServiceStatus mapStatus(String state) {
    if (state == null) {
      return null;
    }
    return switch (state) {
      case "STARTING" -> ServiceStatus.STARTING;
      case "READY" -> ServiceStatus.READY;
      case "RESTARTING" -> ServiceStatus.RESTARTING;
      case "CRASHED" -> ServiceStatus.CRASHED;
      case "STOPPED" -> ServiceStatus.STOPPED;
      default -> null;
    };
  }

  private static ServiceEventSeverity severityFor(ServiceStatus status) {
    return switch (status) {
      case CRASHED -> ServiceEventSeverity.ERROR;
      case RESTARTING -> ServiceEventSeverity.WARNING;
      default -> ServiceEventSeverity.INFO;
    };
  }

  private static String summaryFor(ServiceStatus status, Integer exitCode) {
    String suffix = exitCode != null ? " (exit " + exitCode + ")" : "";
    return switch (status) {
      case STARTING -> "starting";
      case READY -> "ready";
      case RESTARTING -> "restarting" + suffix;
      case CRASHED -> "crashed" + suffix;
      case STOPPED -> "stopped" + suffix;
      default -> status.name();
    };
  }
}
