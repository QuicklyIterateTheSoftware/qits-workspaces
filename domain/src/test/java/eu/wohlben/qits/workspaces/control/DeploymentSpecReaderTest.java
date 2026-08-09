package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one question this service asks a repository's {@code .config/qits/deployments.yml}: does this
 * repository deploy at all? The answer is the file's presence, and the release flow turns it into
 * "promote to the entry branch" or "the trunk push is the whole release".
 *
 * <p>This suite used to hold nine cases about parsing {@code deploy_branches} — comma splitting,
 * quoting, comments, repeated keys, the difference between an absent key and an explicitly blank
 * one. All of it went with the key. What is left is the distinction that was always the load-bearing
 * one, and the reason the check is {@code isRegularFile} rather than {@code exists}.
 *
 * <p>Plain JUnit: the reader is pure, so there is nothing here a {@code @QuarkusTest} would add.
 */
public class DeploymentSpecReaderTest {

  @TempDir Path checkout;

  @Test
  public void noFileMeansThisRepositoryDeploysNowhere() {
    // A library, an SPA on the npm registry, a docs repo: releasing one pushes the trunk and stops.
    assertFalse(DeploymentSpecReader.exists(checkout));
  }

  @Test
  public void aSpecFileMeansItDeploysWhateverIsInsideIt() throws Exception {
    // Every key in the file is the deployer's, and this reader opens none of them. A spec naming
    // only a health path still says "this repository is deployed".
    writeSpec("deployment_target: platform\nhealth_path: /idp/q/health/ready\n");

    assertTrue(DeploymentSpecReader.exists(checkout));
  }

  @Test
  public void anEmptySpecFileStillMeansItDeploys() throws Exception {
    // The ordinary shape after `deploy_branches` was deleted: an environment service takes every
    // default, so its file is comments alone. Presence is the signal, not content.
    writeSpec("# every default: an environment service, deployed wherever a tier listens.\n");

    assertTrue(DeploymentSpecReader.exists(checkout));
  }

  @Test
  public void aDirectoryWhereTheFileShouldBeIsNoSpec() throws Exception {
    // `isRegularFile`, not `exists`, and this is the case that separates them. It answers no rather
    // than throwing later: with nothing left to parse there is no read to fail.
    Files.createDirectories(checkout.resolve(DeploymentSpecReader.SPEC_PATH));

    assertFalse(DeploymentSpecReader.exists(checkout));
  }

  private void writeSpec(String yaml) throws Exception {
    Path file = checkout.resolve(DeploymentSpecReader.SPEC_PATH);
    Files.createDirectories(file.getParent());
    Files.writeString(file, yaml);
  }
}
