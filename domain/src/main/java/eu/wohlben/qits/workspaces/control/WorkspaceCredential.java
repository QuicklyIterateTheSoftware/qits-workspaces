package eu.wohlben.qits.workspaces.control;

/**
 * The credential one workspace container holds toward the platform: an idp client id and its secret,
 * commissioned for that container and decommissioned when it is torn down.
 *
 * <p>It rides into the container as {@code QITS_COMMISSIONED_CLIENT_ID} / {@code
 * QITS_COMMISSIONED_CLIENT_SECRET} ({@link WorkspaceContainerFactory}), which is what gives the
 * workspace an identity of its own for registry pulls and pushes through the edge once reads are
 * gated. Nothing in this service uses it — this service only mints it, carries it and gives it back.
 *
 * <p><b>The pair is what lives long; tokens stay disposable.</b> A workspace that runs for days holds
 * this pair and re-mints tokens underneath it, so the container's lifetime never depends on a token's
 * — see the superproject's {@code authenticated-reads-plan.md}.
 */
public record WorkspaceCredential(String clientId, String secret) {}
