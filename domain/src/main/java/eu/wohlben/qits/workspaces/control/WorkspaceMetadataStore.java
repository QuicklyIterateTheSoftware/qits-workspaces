package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The on-disk sidecar for a workspace: {@code <dataDir>/<repoId>/metadata/workspace_<id>.json},
 * holding the workspace id and its parent branch.
 *
 * <p>Carved out of the monorepo's {@code MetadataService}, which also owns repository-level
 * metadata and therefore stays with the repositories context. Only the two workspace verbs it
 * actually used are here, and the <strong>file layout and JSON shape are deliberately unchanged</strong>
 * — the sidecars are a shared on-disk contract, and an application running both this jar and the
 * repositories context must keep reading each other's files.
 */
@ApplicationScoped
public class WorkspaceMetadataStore {

  @Inject ObjectMapper objectMapper;

  /**
   * The repositories data dir, not a workspaces-specific one: the sidecars sit beside the bare
   * origins that {@code WorkspaceService} already reads from the same key, and both contexts must
   * agree on the path. A second key that had to equal this one would only be a way to get it wrong.
   */
  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
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

  /** The sidecar, or empty when the workspace never had one (or it was already discarded). */
  public Optional<WorkspaceMetadata> read(String repoId, String workspaceId) {
    Path file = fileFor(metadataDir(repoId), workspaceId);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(file.toFile(), WorkspaceMetadata.class));
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to read workspace metadata for " + repoId + "/" + workspaceId, e);
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
    return Path.of(dataDir, repoId, "metadata");
  }

  private static Path fileFor(Path metadataDir, String workspaceId) {
    return metadataDir.resolve("workspace_" + workspaceId + ".json");
  }
}
