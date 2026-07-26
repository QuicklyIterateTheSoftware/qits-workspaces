package eu.wohlben.qits.domain.bootstrap.dto;

import eu.wohlben.qits.domain.bootstrap.entity.BootstrapOutcome;
import java.time.Instant;

/**
 * The most recent outcome of one bootstrap command in one workspace. {@code commandId} links to the
 * audit command row (and its log); null for SKIPPED runs, which never spawn one.
 */
public record BootstrapRunDto(
    String bootstrapCommandId,
    String commandName,
    BootstrapOutcome outcome,
    String commandId,
    Integer exitCode,
    Instant ranAt) {}
