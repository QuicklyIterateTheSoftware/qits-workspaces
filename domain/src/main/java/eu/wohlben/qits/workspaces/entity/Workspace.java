package eu.wohlben.qits.workspaces.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A workspace, soft-deleted: cleanup/discard removes the on-disk workspace and branch but keeps
 * this row as the persistent record of the unit of work — its {@link #status}, the markdown {@link
 * #preamble} (why it was created) and {@link #result} (how it ended). Its {@link WorkspaceEvent}
 * timeline records what happened. There is no DB unique constraint on {@code (repositoryId,
 * workspaceId)} — resolved rows accumulate; the service enforces at most one {@code ACTIVE}
 * workspace per id.
 *
 * <p>Rows in other bounded contexts that belong to a workspace (commands, bootstrap runs, prompt
 * drafts) reference it by id and are not reachable from here. Because the delete is soft, no FK
 * cascade ever fires for them; {@link eu.wohlben.qits.workspaces.control.WorkspaceResolved} is the
 * event they clean up on.
 */
@Entity
@Table(name = "workspace")
public class Workspace extends PanacheEntityBase {

  @Id @GeneratedValue public Long id;

  @Column(name = "workspace_id", nullable = false)
  public String workspaceId;

  /**
   * The owning repository, by <strong>id only</strong>. Deliberately not a JPA {@code @ManyToOne}:
   * this context owns its own datasource and Flyway lineage (the {@code artifacts}/{@code ci}
   * precedent), so it can hold no foreign key into another context's tables. A repository deleted
   * elsewhere simply leaves its workspaces behind as dangling history; {@link RepositoryLookup} is
   * how the owning application is consulted about one.
   */
  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  @Column(name = "parent_id")
  public String parent;

  /**
   * The branch this workspace owns. Stored (not derived from an on-disk checkout) because the
   * checkout now lives inside the workspace's container, not on the host — there is no host path to
   * read {@code git branch --show-current} from.
   */
  @Column(name = "branch")
  public String branch;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public WorkspaceStatus status = WorkspaceStatus.ACTIVE;

  /**
   * The state of this workspace's container — a recreatable cache of the durable branch, not part
   * of the {@link #status} lifecycle. {@code RUNNING} is normally recomputed live from the
   * container listing; the persisted value carries the {@code STOPPED}/{@code PROVISIONING}/{@code
   * FAILED} signal across restarts and out-of-band container loss.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "runtime_status", nullable = false)
  public WorkspaceRuntimeStatus runtimeStatus = WorkspaceRuntimeStatus.STOPPED;

  /** The reason the last re-provision failed (when {@link #runtimeStatus} is FAILED); else null. */
  @Column(name = "runtime_error", length = 2000)
  public String runtimeError;

  /** Markdown: the reason/goal, authored at creation, editable while ACTIVE. */
  @Lob public String preamble;

  /** Markdown: the outcome, authored at integration or abandonment. */
  @Lob public String result;

  /** When the workspace was resolved (integrated/abandoned); null while ACTIVE. */
  @Column(name = "resolved_at")
  public Instant resolvedAt;

  /** When the row was created; null for rows that predate this column. */
  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  public Instant createdAt;
}
