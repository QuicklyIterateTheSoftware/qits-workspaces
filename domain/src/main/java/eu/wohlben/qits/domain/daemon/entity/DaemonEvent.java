package eu.wohlben.qits.domain.daemon.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One durable daemon event: a supervisor transition or an observer finding, written as it is
 * published so the history survives the JVM (last night's crash, what the classifier saw, what was
 * sent to the agent). Everything is a snapshot — {@code commandId} is a plain column, not an FK,
 * because command rows can be deleted while the event should stay inspectable. The anchor columns
 * locate the excerpt in its source: {@code command_log_line} sequences for {@code source="output"},
 * 1-based file line numbers since {@code sourceEpoch} for a tailed file (whose content is
 * deliberately <em>not</em> copied here — the file is the durable store, the excerpt the display
 * copy).
 */
@Entity
@Table(name = "daemon_event")
public class DaemonEvent extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  @Column(name = "workspace_id", nullable = false)
  public String workspaceId;

  @Column(name = "daemon_id", nullable = false)
  public String daemonId;

  @Column(name = "daemon_name", nullable = false)
  public String daemonName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public DaemonEventKind kind;

  @Enumerated(EnumType.STRING)
  public DaemonEventSeverity severity;

  @Enumerated(EnumType.STRING)
  public DaemonStatus status;

  @Column(length = 2000)
  public String summary;

  @Lob
  @Column(name = "log_excerpt")
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
