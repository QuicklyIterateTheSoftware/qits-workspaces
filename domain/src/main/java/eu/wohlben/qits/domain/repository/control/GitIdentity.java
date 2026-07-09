package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single configurable identity every commit qits manufactures is attributed to — the host-side
 * integration merges ({@code WorkspaceService.mergeIntoTarget}), the container-side parent merges,
 * and any commit made inside a workspace container (the coding agent, an action, an ad-hoc {@code
 * git commit}). First-class because the identity used to live as scattered {@code qits@local}
 * literals — and, on the host-side merge path, as an ambient-{@code ~/.gitconfig} dependency that
 * failed with "Committer identity unknown" in identity-less environments. One config-backed bean
 * means an operator sets {@code qits.git.author-name}/{@code qits.git.author-email} once and every
 * commit path follows. Two delivery forms:
 *
 * <ul>
 *   <li>{@link #envMap()} — container-level env ({@code GIT_AUTHOR_*} + {@code GIT_COMMITTER_*}),
 *       set by {@link WorkspaceContainerFactory} on every workspace container, so every git process
 *       in the container inherits it regardless of cwd or {@code .git/config} (identity env beats
 *       every git config level, {@code -c} included).
 *   <li>{@link #inlineArgs()} — {@code -c} overrides spliced into the one host {@code git merge}
 *       qits spawns directly, keeping the identity explicit at the single synthetic-commit call
 *       site instead of leaking env into every host git invocation.
 * </ul>
 */
@ApplicationScoped
public class GitIdentity {

  @ConfigProperty(name = "qits.git.author-name", defaultValue = "qits")
  String name;

  @ConfigProperty(name = "qits.git.author-email", defaultValue = "qits@local")
  String email;

  /**
   * The four identity env vars (author and committer halves, so both sides of a commit are
   * attributed), insertion-ordered so rendered argv stays deterministic.
   */
  public Map<String, String> envMap() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("GIT_AUTHOR_NAME", name);
    env.put("GIT_AUTHOR_EMAIL", email);
    env.put("GIT_COMMITTER_NAME", name);
    env.put("GIT_COMMITTER_EMAIL", email);
    return env;
  }

  /** Inline config overrides for a host git invocation: {@code -c user.email=… -c user.name=…}. */
  public List<String> inlineArgs() {
    return List.of("-c", "user.email=" + email, "-c", "user.name=" + name);
  }
}
