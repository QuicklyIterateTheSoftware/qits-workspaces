package eu.wohlben.qits.workspaces.gitmirror;

/**
 * What {@code git tag -a} came to in the mirror.
 *
 * <p>{@link #alreadyExists} is the version-uniqueness guarantee firing at its cheapest point. The
 * mirror was refreshed from the git host at the top of the flow, so its tags are the host's tags,
 * and a name already released is refused here — before this run has pushed anything at all. The push
 * carries the same guarantee for a writer who arrives later.
 */
public record TagOutcome(boolean created, boolean alreadyExists, String output) {}
