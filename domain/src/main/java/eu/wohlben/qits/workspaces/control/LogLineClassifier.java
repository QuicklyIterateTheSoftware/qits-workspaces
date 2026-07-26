package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.LogSeverity;
import java.util.Optional;

/**
 * Classifies one raw captured log line into a {@link LogSeverity}, or empty for routine output.
 *
 * <p>The one port here that points <em>outwards</em>: this context implements it ({@link
 * LogLevelLineClassifier}) so the command context's batch log persister can stamp severities with
 * the same local vocabulary the LOG_LEVEL observer uses, and {@code ?severity=} filters on the
 * command log and on observer findings agree about what counts as an error. The command context
 * declares the identical two-method shape on its own side; an application running both registers
 * this implementation there with a one-line adapter.
 *
 * <p>Must be cheap and local — it runs per line in the async log-persistence path.
 */
public interface LogLineClassifier {

  Optional<LogSeverity> classify(String rawLine);
}
