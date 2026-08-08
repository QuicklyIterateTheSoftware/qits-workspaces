package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reader of a repository's {@code .config/qits/deployments.yml}, and the three answers the
 * release flow turns into "promote these refs", "promote the configured ones" and "promote
 * nothing".
 *
 * <p>Plain JUnit: the reader is pure, so there is nothing here a {@code @QuarkusTest} would add.
 */
public class DeploymentSpecReaderTest {

  @TempDir Path checkout;

  private void writeSpec(String yaml) throws Exception {
    Path file = checkout.resolve(DeploymentSpecReader.SPEC_PATH);
    Files.createDirectories(file.getParent());
    Files.writeString(file, yaml);
  }

  // -----------------------------------------------------------------------------------------
  // the three answers
  // -----------------------------------------------------------------------------------------

  @Test
  public void noFileIsNoSpec() {
    assertEquals(Optional.empty(), DeploymentSpecReader.read(checkout));
  }

  /**
   * A spec that says nothing about deploy branches is not the same answer as no spec: the caller
   * falls back to its configured list here and promotes nothing there.
   */
  @Test
  public void aSpecWithoutTheKeyDeclaresNothing() throws Exception {
    writeSpec("deployment_target: platform\nhealth_path: /q/health/ready\n");

    DeploymentSpecReader.Spec spec = DeploymentSpecReader.read(checkout).orElseThrow();
    assertFalse(spec.declaresDeployBranches());
    assertEquals(List.of(), spec.deployBranches());
  }

  @Test
  public void theKeyIsReadAsACommaSeparatedListInTheOrderWritten() throws Exception {
    writeSpec("deploy_branches: environment/prod, platform/main\n");

    DeploymentSpecReader.Spec spec = DeploymentSpecReader.read(checkout).orElseThrow();
    assertTrue(spec.declaresDeployBranches());
    assertEquals(List.of("environment/prod", "platform/main"), spec.deployBranches());
  }

  /**
   * An explicitly blank value is an explicit "none" — declared, and deploying nowhere. It has to
   * stay distinct from the key being absent, which falls back.
   */
  @Test
  public void anEmptyValueIsADeclaredNoneRatherThanAnAbsentKey() {
    DeploymentSpecReader.Spec spec = DeploymentSpecReader.parse("deploy_branches:\n");

    assertTrue(spec.declaresDeployBranches());
    assertEquals(List.of(), spec.deployBranches());
  }

  // -----------------------------------------------------------------------------------------
  // lenient where the deployer's own parser is strict
  // -----------------------------------------------------------------------------------------

  /**
   * The choice this reader exists to make: it is a <b>second</b> reader of a file the deployer owns,
   * so a key the deployer adds tomorrow must not fail every release in the platform until this copy
   * is taught about it.
   */
  @Test
  public void unknownKeysAreIgnoredRatherThanRefused() {
    DeploymentSpecReader.Spec spec =
        DeploymentSpecReader.parse(
            """
            deployment_target: platform
            available_on_env: false
            branch: platform/main
            health_path: /q/health/ready
            a_key_this_reader_has_never_heard_of: whatever
            deploy_branches: environment/prod
            """);

    assertEquals(List.of("environment/prod"), spec.deployBranches());
  }

  /** Neither a malformed line nor an indented one is this reader's to refuse. */
  @Test
  public void malformedAndIndentedLinesAreSkipped() {
    DeploymentSpecReader.Spec spec =
        DeploymentSpecReader.parse(
            "---\nnot a key value line\n  indented: value\ndeploy_branches: environment/prod\n");

    assertEquals(List.of("environment/prod"), spec.deployBranches());
  }

  /** A repeated key takes the last, which is what a person editing from the bottom expects. */
  @Test
  public void aRepeatedKeyTakesTheLast() {
    DeploymentSpecReader.Spec spec =
        DeploymentSpecReader.parse("deploy_branches: environment/old\ndeploy_branches: environment/prod\n");

    assertEquals(List.of("environment/prod"), spec.deployBranches());
  }

  @Test
  public void commentsQuotesBlanksAndDuplicatesAreAllHandled() {
    DeploymentSpecReader.Spec spec =
        DeploymentSpecReader.parse(
            "# how this repo deploys\ndeploy_branches: \"environment/prod, , environment/prod,"
                + " platform/main\"  # two refs\n");

    assertEquals(
        List.of("environment/prod", "platform/main"),
        spec.deployBranches(),
        "trimmed, blanks dropped so a trailing comma is not a ref named \"\", and de-duplicated");
  }
}
