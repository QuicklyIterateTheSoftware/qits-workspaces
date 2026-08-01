package eu.wohlben.qits.workspaces.gitmirror;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The name and address every commit this module manufactures is attributed to. A record rather than
 * a port: the identity is one pair of strings, and where it is configured is {@code domain}'s
 * business.
 *
 * <p>Delivered in both forms at once, exactly as {@code GitIdentity} does today, because they are
 * not redundant: {@link #env()} is what actually guarantees attribution (identity env outranks every
 * git config level, {@code -c} included, so an ambient {@code GIT_AUTHOR_*} inherited from the host
 * would otherwise win), while {@link #inlineArgs()} keeps it explicit in the argv a log line shows.
 */
public record CommitIdentity(String name, String email) {

  /** The four identity env vars, insertion-ordered so rendered argv stays deterministic. */
  public Map<String, String> env() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("GIT_AUTHOR_NAME", name);
    env.put("GIT_AUTHOR_EMAIL", email);
    env.put("GIT_COMMITTER_NAME", name);
    env.put("GIT_COMMITTER_EMAIL", email);
    return env;
  }

  /** The {@code -c} form of the same identity. */
  public List<String> inlineArgs() {
    return List.of("-c", "user.email=" + email, "-c", "user.name=" + name);
  }
}
