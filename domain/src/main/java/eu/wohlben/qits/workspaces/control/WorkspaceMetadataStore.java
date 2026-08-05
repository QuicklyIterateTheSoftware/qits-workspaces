package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The on-disk sidecar for a workspace: {@code <workspaces-data-dir>/metadata/<repoId>/workspace_<id>.json},
 * holding the workspace id and its parent branch.
 *
 * <p><b>It moved off the shared volume.</b> It used to live at {@code
 * <repositories-data-dir>/<repoId>/metadata/workspace_<id>.json} — inside the tree of bare
 * repositories that qits-artifacts serves and qits-projects clones into — for the historical reason
 * that this code was carved out of the monorepo's {@code MetadataService} and inherited its path. It
 * is not git, nothing else reads it, and a private file of one service has no business in another
 * service's storage. It is here now, beside this service's own database and event outbox.
 *
 * <p>The read fallback that carried a deployment across that move is gone, and so is the config key
 * it needed: no ACTIVE workspace has a sidecar in the old location any more, so it could never fire
 * again.
 *
 * <p><b>Nothing reads the sidecar.</b> Not this repository, not the workspace daemon — this
 * service's data tree is mounted into no container — and nothing else on the platform. The {@code
 * workspace} row carries the same two values. What is left is the write and the discard, kept as the
 * on-disk trace of a workspace; whether the file should exist at all is a separate question from
 * unmounting the volume, and is not answered here.
 */
@ApplicationScoped
public class WorkspaceMetadataStore {

  @Inject ObjectMapper objectMapper;

  /** This service's own data tree — where the sidecars live now. */
  @ConfigProperty(name = "qits.workspaces.data-dir", defaultValue = "data/workspaces")
  String dataDir;

  /** Write (or overwrite) the sidecar, creating the metadata dir on first use. */
  public void write(String repoId, WorkspaceMetadata metadata) {
    try {
      Path metadataDir = metadataDir(repoId);
      Files.createDirectories(metadataDir);
      objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(fileFor(metadataDir, metadata.workspaceId).toFile(), metadata);
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to write workspace metadata for " + repoId + "/" + metadata.workspaceId, e);
    }
  }

  /** Remove the sidecar; a missing file is not an error (discard is idempotent). */
  public void delete(String repoId, String workspaceId) {
    try {
      Files.deleteIfExists(fileFor(metadataDir(repoId), workspaceId));
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to delete workspace metadata for " + repoId + "/" + workspaceId, e);
    }
  }

  private Path metadataDir(String repoId) {
    return Path.of(dataDir, "metadata", repoId);
  }

  private static Path fileFor(Path metadataDir, String workspaceId) {
    return metadataDir.resolve("workspace_" + workspaceId + ".json");
  }
}
