package eu.wohlben.qits.domain.repository.control;

/**
 * A workspace's in-container {@code .qits-config.yml}, as read by its {@code workspace-daemon} from
 * the branch's checkout and reported over the control socket (docs/epics/qits-workspace-daemon/
 * Part 2). {@code config} is the parsed {@link QitsConfig} (empty for an absent/blank/invalid
 * file); {@code warning} is non-null only when the file was present but could not be read or parsed
 * — the "degrade loudly, never block" signal the UI surfaces. Framework-free so it lives in {@code
 * domain}; {@link WorkspaceConfigReader} returns it.
 */
public record WorkspaceConfigView(QitsConfig config, String warning) {}
