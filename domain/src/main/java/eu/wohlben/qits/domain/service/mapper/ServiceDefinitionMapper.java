package eu.wohlben.qits.domain.service.mapper;

import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.service.dto.HealthCheckDto;
import eu.wohlben.qits.domain.service.dto.ServiceDefinitionDto;
import eu.wohlben.qits.domain.service.dto.WebViewDto;
import eu.wohlben.qits.domain.service.entity.RestartPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps a config-declared {@link QitsConfig.ServiceDecl} to the flat {@link ServiceDefinitionDto}
 * the supervisor runs. The file is authoritative, so there is no request to fail: omitted fields
 * fall back to the old store's defaults and lexically broken ones degrade to their safe default
 * instead of throwing (a bad ready pattern would otherwise blow up the supervisor's reader thread).
 */
@ApplicationScoped
public class ServiceDefinitionMapper {

  private static final Pattern SIGNAL_PATTERN = Pattern.compile("[A-Z][A-Z0-9]{0,9}");

  public ServiceDefinitionDto toDto(QitsConfig.ServiceDecl decl) {
    return new ServiceDefinitionDto(
        decl.id(),
        decl.name(),
        decl.description(),
        decl.start(),
        decl.readyPattern() == null || decl.readyPattern().isBlank() ? null : decl.readyPattern(),
        normalizeStopSignal(decl.stopSignal()),
        decl.restartPolicy() != null ? decl.restartPolicy() : RestartPolicy.ON_FAILURE,
        decl.autoStart() == null || decl.autoStart(),
        decl.maxRestarts() != null ? decl.maxRestarts() : 3,
        decl.otel() != null && decl.otel(),
        toWebViewDto(decl.webView()),
        decl.environment() != null ? Map.copyOf(decl.environment()) : Map.of(),
        decl.healthChecks() != null
            ? decl.healthChecks().stream().map(ServiceDefinitionMapper::toHealthCheckDto).toList()
            : List.of());
  }

  /** Trims/uppercases/strips SIG, defaults to {@code TERM} when absent or lexically invalid. */
  private static String normalizeStopSignal(String stopSignal) {
    if (stopSignal == null || stopSignal.isBlank()) {
      return "TERM";
    }
    String normalized = stopSignal.trim().toUpperCase();
    if (normalized.startsWith("SIG")) {
      normalized = normalized.substring(3);
    }
    return SIGNAL_PATTERN.matcher(normalized).matches() ? normalized : "TERM";
  }

  /** A web-view without a port is not web-viewable (paths alone are ignored). */
  private static WebViewDto toWebViewDto(QitsConfig.WebViewDecl decl) {
    if (decl == null || decl.port() == null) {
      return null;
    }
    return new WebViewDto(decl.port(), decl.entryPath(), decl.basePath());
  }

  private static HealthCheckDto toHealthCheckDto(QitsConfig.HealthCheckDecl decl) {
    return new HealthCheckDto(
        decl.name(),
        decl.kind(),
        decl.port(),
        decl.path(),
        decl.expectStatus(),
        decl.command(),
        decl.intervalMs(),
        decl.timeoutMs(),
        decl.healthyThreshold(),
        decl.unhealthyThreshold(),
        decl.initialDelayMs());
  }
}
