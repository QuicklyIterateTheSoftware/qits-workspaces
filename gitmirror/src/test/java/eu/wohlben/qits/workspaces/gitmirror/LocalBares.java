package eu.wohlben.qits.workspaces.gitmirror;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The test-side {@link GitRemotes}: repository ids mapped to local bares.
 *
 * <p>It counts the two calls separately, which is what proves the split is real — a read must never
 * consume the push hook the service suite stages a losing race with.
 */
final class LocalBares implements GitRemotes {

  private final Map<String, Path> bares = new HashMap<>();
  final AtomicInteger fetchUrlCalls = new AtomicInteger();
  final AtomicInteger pushUrlCalls = new AtomicInteger();

  void register(String repoId, Path bare) {
    bares.put(repoId, bare);
  }

  @Override
  public String fetchUrl(String repoId) {
    fetchUrlCalls.incrementAndGet();
    return url(repoId);
  }

  @Override
  public String pushUrl(String repoId) {
    pushUrlCalls.incrementAndGet();
    return url(repoId);
  }

  private String url(String repoId) {
    Path bare = bares.get(repoId);
    if (bare == null) {
      throw new IllegalStateException("no bare registered for " + repoId);
    }
    return bare.toAbsolutePath().toString();
  }
}
