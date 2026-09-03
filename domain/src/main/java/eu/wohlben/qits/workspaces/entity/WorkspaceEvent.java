package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * One entry in a workspace's history timeline — what happened and when, with the
 * branch/parent/target/ commit context snapshotted as strings. High-volume, so a sequence-generated
 * {@code Long} id (like {@code CommandLogLine}).
 *
 * <p><b>A {@link CausedRow}, and the one that carries this context's trace.</b> The workspace row
 * records why the unit of work exists; a timeline entry records a later moment with a cause of its
 * own — a MERGED or INTEGRATED entry answers to whatever asked for that landing, not to whoever
 * opened the workspace weeks earlier. Every {@code recordEvent} call site sits on the flow's own
 * thread with no executor between it and the caller, so the {@code CausationStamp} listener reads
 * the REST filter's restored scope and nothing has to be passed as data — including when the caller
 * is a machine driving these doors with its own bearer.
 */
@Entity
@Table(name = "workspace_event")
@EntityListeners(CausationStamp.class)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceEvent extends PanacheEntityBase implements CausedRow {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "workspace_event_seq")
  @SequenceGenerator(
      name = "workspace_event_seq",
      sequenceName = "workspace_event_SEQ",
      allocationSize = 50)
  public Long id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  @ManyToOne(optional = false)
  @JoinColumn(name = "workspace_id_fk", nullable = false)
  public Workspace workspace;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public WorkspaceEventType type;

  public String branch;
  public String parent;
  public String target;

  @Column(name = "commit_hash")
  public String commit;

  @Column(length = 2000)
  public String note;

  @Column(name = "at", nullable = false)
  public Instant at;
}
