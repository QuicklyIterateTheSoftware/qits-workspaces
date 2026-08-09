package eu.wohlben.qits.workspaces.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One durable service event: a supervisor transition or an observer finding, written as it is
 * published so the history survives the JVM (last night's crash, what the classifier saw, what was
 * sent to the agent). Everything is a snapshot — {@code commandId} is a plain column, not an FK,
 * because command rows can be deleted while the event should stay inspectable. The anchor columns
 * locate the excerpt in its source: {@code command_log_line} sequences for {@code source="output"},
 * 1-based file line numbers since {@code sourceEpoch} for a tailed file (whose content is
 * deliberately <em>not</em> copied here — the file is the durable store, the excerpt the display
 * copy).
 */
@Entity
@Table(name = "service_event")
public class ServiceEvent extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  @Column(name = "workspace_id", nullable = false)
  public String workspaceId;

  /**
   * The workspace table's surrogate key. {@code workspaceId} above is a recyclable branch-derived
   * label, so this is what says <em>which</em> workspace the event belonged to — the SPA's
   * recycled-label guard filters on it. Nullable (rows written before V4 have none) and no FK: the
   * event outlives the row, see V2's header.
   */
  @Column(name = "workspace_row_id")
  public Long workspaceRowId;

  @Column(name = "service_id", nullable = false)
  public String serviceId;

  @Column(name = "service_name", nullable = false)
  public String serviceName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public ServiceEventKind kind;

  @Enumerated(EnumType.STRING)
  public ServiceEventSeverity severity;

  @Enumerated(EnumType.STRING)
  public ServiceStatus status;

  @Column(length = 2000)
  public String summary;

  /*
   * `text`/`bytea` via columnDefinition, and NOT @Lob — the one entity mapping the move to postgres
   * had to change. On H2 a @Lob String was a clob and the two agreed; on postgres @Lob means a LARGE
   * OBJECT, so Hibernate binds an oid and the insert fails against the column V1 declares. Unbounded
   * either way, which is what these fields need.
   */
  @Column(name = "log_excerpt", columnDefinition = "text")
  public String logExcerpt;

  @Column(name = "command_id")
  public String commandId;

  /** {@code "output"} or the tailed file's workspace-relative path; null on plain transitions. */
  @Column(length = 1024)
  public String source;

  @Column(name = "anchor_from")
  public Long anchorFrom;

  @Column(name = "anchor_to")
  public Long anchorTo;

  @Column(name = "source_epoch")
  public Instant sourceEpoch;

  @Column(name = "at", nullable = false)
  public Instant timestamp;
}
