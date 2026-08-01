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
 * <p>{@link #read} still looks at the old location when the new one has nothing, so a deployment
 * upgrading across this change keeps answering for workspaces created before it, and {@link #delete}
 * removes both. Nothing writes to the old path again. When no workspace created before this change
 * can still be ACTIVE, the fallback and its config key can go.
 */
@ApplicationScoped
public class WorkspaceMetadataStore {

  @Inject ObjectMapper objectMapper;

  /** This service's own data tree — where the sidecars live now. */
  @ConfigProperty(name = "qits.workspaces.data-dir", defaultValue = "data/workspaces")
  String dataDir;

  /**
   * The shared repositories volume, read-only and only for sidecars written before the move. Keep
   * the key spelled as the repositories one: it names the same tree the other two services mount,
   * and a second key that had to equal it would only be a way to get it wrong.
   */
  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String legacyDataDir;

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
      file = fileFor(legacyMetadataDir(repoId), workspaceId);
    }
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

  /** Remove the sidecar from both locations; a missing file is not an error (discard is idempotent). */
  public void delete(String repoId, String workspaceId) {
    try {
      Files.deleteIfExists(fileFor(metadataDir(repoId), workspaceId));
      Files.deleteIfExists(fileFor(legacyMetadataDir(repoId), workspaceId));
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to delete workspace metadata for " + repoId + "/" + workspaceId, e);
    }
  }

  private Path metadataDir(String repoId) {
    return Path.of(dataDir, "metadata", repoId);
  }

  private Path legacyMetadataDir(String repoId) {
    return Path.of(legacyDataDir, repoId, "metadata");
  }

  private static Path fileFor(Path metadataDir, String workspaceId) {
    return metadataDir.resolve("workspace_" + workspaceId + ".json");
  }
}
