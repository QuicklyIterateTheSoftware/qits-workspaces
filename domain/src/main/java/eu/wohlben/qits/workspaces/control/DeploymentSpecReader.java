package eu.wohlben.qits.workspaces.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a repository's own {@code .config/qits/deployments.yml} out of a checkout, for the one
 * question this service asks it: <b>which refs does this repository deploy from?</b>
 *
 * <p>The file is flat {@code key: value} lines with no nesting and no lists, so this is a line
 * reader rather than a YAML library — the same shape qits-platform-deployments' {@code
 * DeploymentSpecParser} has, vendored rather than depended on. A dependency from the release flow
 * onto the deployer would put the deployer's whole module on this classpath to read one key, and the
 * two components ship on their own schedules.
 *
 * <h2>The one key this reader is about</h2>
 *
 * <pre>
 * deploy_branches: environment/prod          # comma-separated; one entry is the ordinary case
 * </pre>
 *
 * Everything else in the file — {@code deployment_target}, {@code available_on_env}, {@code branch},
 * {@code health_path}, and whatever the deployer adds next — belongs to the deployer and is read
 * here as nothing at all.
 *
 * <h2>Lenient, where the canonical parser is strict — deliberately</h2>
 *
 * The deployer's parser rejects an unknown key, because a typo there decides where a container runs
 * and answering it with a default would silently deploy the wrong topology. <b>This reader ignores
 * every key it does not know</b>, and that is the opposite choice for the opposite reason: it is a
 * <em>second</em> reader of a file it does not own, so a key added on the deployer's side must not
 * fail every release in the platform until this copy is taught about it. The two parsers evolve
 * without lockstep; the strictness that guards the file stays where the file is owned.
 *
 * <p>The same leniency covers shape: a line that is not {@code key: value}, an indented line, a
 * repeated key — none of them is this reader's to refuse. A repeated {@code deploy_branches} takes
 * the last one, which is what a person editing the file from the bottom expects.
 *
 * <h2>The three answers, and why they are three</h2>
 *
 * <ul>
 *   <li><b>No file</b> — {@link #read} is empty. The repository has never said it deploys, so the
 *       release flow promotes nothing.
 *   <li><b>A file with no {@code deploy_branches}</b> — a {@link Spec} whose {@link
 *       Spec#deployBranches()} is empty. The repository has a spec but has not declared its deploy
 *       refs yet, and the caller falls back to its configured list.
 *   <li><b>A file declaring the key</b> — those refs exactly, in the order written. An explicitly
 *       blank value is an explicit "none", and stays distinct from the key being absent.
 * </ul>
 *
 * <p>An unreadable file <b>throws</b> rather than reading as absent. It is a local file in a
 * checkout this process just made, so a failure there is a defect and not a configuration; the
 * release flow reads it before it pushes anything, so the loud answer costs nothing that landed.
 */
public final class DeploymentSpecReader {

  /** Where a repository declares how it deploys, relative to a checkout's root. */
  public static final String SPEC_PATH = ".config/qits/deployments.yml";

  private static final String DEPLOY_BRANCHES = "deploy_branches";

  private DeploymentSpecReader() {}

  /**
   * What this reader takes from the file.
   *
   * @param deployBranches the refs the repository deploys from, in the order written, trimmed and
   *     de-duplicated — <b>empty when the key is absent</b>, which {@link #declaresDeployBranches()}
   *     is how you tell from an explicit empty declaration
   * @param declaresDeployBranches whether {@code deploy_branches} was written at all
   */
  public record Spec(List<String> deployBranches, boolean declaresDeployBranches) {}

  /**
   * Read the spec out of a checkout.
   *
   * @param checkout the root of a checked-out tree — the release flow's merge worktree
   * @return empty when the repository carries no spec file
   * @throws UncheckedIOException when the file is there and cannot be read
   */
  public static Optional<Spec> read(Path checkout) {
    Path file = checkout.resolve(SPEC_PATH);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(parse(Files.readString(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + SPEC_PATH, e);
    }
  }

  /** The line reader itself, separated out so it can be exercised without a filesystem. */
  public static Spec parse(String yaml) {
    List<String> branches = null;
    for (String raw : (yaml == null ? "" : yaml).split("\\R", -1)) {
      String line = stripComment(raw).strip();
      if (line.isEmpty() || line.equals("---")) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon < 1) {
        continue;
      }
      if (!DEPLOY_BRANCHES.equals(line.substring(0, colon).strip())) {
        continue;
      }
      branches = split(unquote(line.substring(colon + 1).strip()));
    }
    return branches == null ? new Spec(List.of(), false) : new Spec(branches, true);
  }

  /**
   * Comma-separated, trimmed, blanks dropped so a trailing comma is not a ref named {@code ""}, and
   * de-duplicated so a typo cannot promote to one branch twice. The same normalisation the
   * configured fallback list gets, because the two are alternatives for one answer.
   */
  private static List<String> split(String value) {
    Set<String> branches = new LinkedHashSet<>();
    for (String branch : value.split(",")) {
      String trimmed = branch.strip();
      if (!trimmed.isEmpty()) {
        branches.add(trimmed);
      }
    }
    return List.copyOf(branches);
  }

  /**
   * Drops a {@code #} comment. A {@code #} only starts one at the beginning of the line or after
   * whitespace — YAML's own rule, and the reason a ref named {@code fix#123} keeps its hash.
   */
  private static String stripComment(String line) {
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
        && value.charAt(value.length() - 1) == value.charAt(0)) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
