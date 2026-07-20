package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.error.PayloadTooLargeException;
import eu.wohlben.qits.domain.repository.entity.PromptAttachmentSource;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptAttachment;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptAttachmentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Ingest and removal of a workspace's prompt attachments (image rows beside the draft). Like the
 * draft, these are pure host-side data — no container is materialized — so they work on a {@code
 * STOPPED} workspace. Upload decodes the base64 payload, enforces a per-image byte cap (413) and a
 * PNG/JPEG magic-byte sniff (400 for anything else), and stores the <em>sniffed</em> media type —
 * the bytes are the truth, the client's claimed {@code mimeType} is only a hint.
 */
@ApplicationScoped
public class WorkspacePromptAttachmentService {

  @Inject WorkspaceResolver workspaceResolver;

  @Inject WorkspacePromptAttachmentRepository attachmentRepository;

  /** Per-image cap on the decoded bytes; over this yields a 413. */
  @ConfigProperty(name = "qits.workspace.prompt-attachment-max-bytes", defaultValue = "2097152")
  long maxBytes;

  /**
   * Ingests one image attachment. Validates the payload before touching the DB — invalid base64 or
   * a non-PNG/JPEG payload is a 400, an oversized one a 413. The stored {@code mimeType} is the
   * sniffed type (it wins over the claimed one); {@code claimedMimeType} is accepted for symmetry
   * with the client request but only the bytes decide.
   */
  @Transactional
  public WorkspacePromptAttachment addAttachment(
      String repoId,
      String workspaceId,
      String claimedMimeType,
      String label,
      String source,
      String dataBase64) {
    PromptAttachmentSource parsedSource = parseSource(source);

    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(dataBase64);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Attachment data is not valid base64", e);
    }
    if (bytes.length > maxBytes) {
      throw new PayloadTooLargeException("Attachment exceeds the " + maxBytes + "-byte limit");
    }
    String sniffed = sniffImageType(bytes);
    if (sniffed == null) {
      throw new BadRequestException("Attachment is not a PNG or JPEG image");
    }

    Workspace workspace = workspaceResolver.resolveActive(repoId, workspaceId);
    WorkspacePromptAttachment attachment = new WorkspacePromptAttachment();
    attachment.id = UUID.randomUUID().toString();
    attachment.workspaceId = workspace.id;
    attachment.mimeType = sniffed;
    attachment.label = label;
    attachment.source = parsedSource;
    attachment.bytes = bytes;
    attachmentRepository.persist(attachment);
    return attachment;
  }

  /** Removes one attachment scoped to its workspace; 404 if the workspace or the row is unknown. */
  @Transactional
  public void deleteAttachment(String repoId, String workspaceId, String attachmentId) {
    Workspace workspace = workspaceResolver.resolveActive(repoId, workspaceId);
    if (!attachmentRepository.deleteByWorkspaceIdAndId(workspace.id, attachmentId)) {
      throw new NotFoundException("Attachment not found: " + attachmentId);
    }
  }

  private static PromptAttachmentSource parseSource(String source) {
    try {
      return PromptAttachmentSource.valueOf(source.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown attachment source: " + source, e);
    }
  }

  /**
   * Magic-byte detection for the two accepted image types — {@code image/png} or {@code
   * image/jpeg}, else {@code null}. Signatures mirror {@code artifacts}' {@code MediaTypeSniffer};
   * inlined here so {@code domain} needs no dependency on {@code artifacts} for a two-signature
   * check.
   */
  private static String sniffImageType(byte[] b) {
    if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
      return "image/png";
    }
    if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
      return "image/jpeg";
    }
    return null;
  }

  private static boolean startsWith(byte[] b, int... prefix) {
    if (b == null || b.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((b[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
