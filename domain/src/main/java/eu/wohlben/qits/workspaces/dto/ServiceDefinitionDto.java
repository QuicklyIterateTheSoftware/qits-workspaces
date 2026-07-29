package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.control.RestartPolicy;
import java.util.List;
import java.util.Map;

/**
 * A workspace service definition, sourced from the workspace's committed {@code .qits-config.yml}
 * (the in-container read — there is no DB definition store). {@code id} is the config-declared
 * {@code id:} (defaulting to {@code name}); it keys the tmux session, the container exec calls, the
 * proxy path segment, and the start/stop REST paths.
 */
public record ServiceDefinitionDto(
    String id,
    String name,
    String description,
    String startScript,
    String readyPattern,
    String stopSignal,
    RestartPolicy restartPolicy,
    boolean autoStart,
    int maxRestarts,
    WebViewDto webView,
    Map<String, String> environment,
    List<HealthCheckDto> healthChecks) {}
