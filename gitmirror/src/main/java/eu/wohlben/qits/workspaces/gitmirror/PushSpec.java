package eu.wohlben.qits.workspaces.gitmirror;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code git push}: the refs it moves, the options it carries, and whether it is all-or-nothing.
 *
 * <p>There is no force flag and there will not be one. Fast-forward-only is receive-pack's own
 * property and it is what makes every push here a compare-and-swap; a caller that could ask for
 * {@code --force} would be a caller that could overwrite a release.
 */
public record PushSpec(List<Ref> refs, List<String> options, boolean atomic) {

  /**
   * One refspec. A null {@code source} is a <b>deletion</b> — {@code :refs/heads/x} — which is how a
   * branch is removed now that nothing writes the ref store by hand.
   */
  public record Ref(String source, String destination) {

    public static Ref update(String source, String destination) {
      return new Ref(source, destination);
    }

    public static Ref branch(String source, String branch) {
      return new Ref(source, "refs/heads/" + branch);
    }

    public static Ref tag(String source, String tag) {
      return new Ref(source, "refs/tags/" + tag);
    }

    public static Ref deleteBranch(String branch) {
      return new Ref(null, "refs/heads/" + branch);
    }

    String refspec() {
      return (source == null ? "" : source) + ":" + destination;
    }
  }

  public static PushSpec of(Ref... refs) {
    return new PushSpec(List.of(refs), List.of(), false);
  }

  /** The same push, carrying a {@code --push-option} the git host's protection hook reads. */
  public PushSpec withOption(String option) {
    List<String> next = new ArrayList<>(options);
    next.add(option);
    return new PushSpec(refs, List.copyOf(next), atomic);
  }

  /** All or nothing: one receive-pack, one pre-receive, one post-receive, both refs or neither. */
  public PushSpec asAtomic() {
    return new PushSpec(refs, options, true);
  }
}
