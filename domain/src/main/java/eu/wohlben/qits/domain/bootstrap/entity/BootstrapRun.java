package eu.wohlben.qits.domain.bootstrap.entity;

import eu.wohlben.qits.domain.repository.entity.Workspace;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * The most recent outcome of one bootstrap command in one workspace — a single row per {@code
 * (workspace, bootstrapCommandId)}, overwritten on every run, so the workspace surface can show
 * "skipped/succeeded/failed at &lt;time&gt;" per command. Full run history still lives in the
 * {@code command} audit rows (a SKIPPED run leaves none — the check script decided nothing needed
 * to happen). {@link #bootstrapCommandId} is a plain snapshot column, not a foreign key (the {@code
 * Command.actionId} precedent), so reconcile-time deletion of a command never breaks recorded
 * state.
 */
@Entity
@Table(
    name = "workspace_bootstrap_run",
    uniqueConstraints =
        @UniqueConstraint(
            name = "UQ_workspace_bootstrap_run",
            columnNames = {"workspace_id_fk", "bootstrap_command_id"}))
public class BootstrapRun extends PanacheEntityBase {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  public String id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "workspace_id_fk", nullable = false)
  public Workspace workspace;

  @Column(name = "bootstrap_command_id", nullable = false)
  public String bootstrapCommandId;

  /** The command's stored name at run time (snapshot, like {@code Command.actionName}). */
  @Column(name = "command_name", nullable = false)
  public String commandName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public BootstrapOutcome outcome;

  /** The audit {@code command} row of the execute run; null for SKIPPED (nothing ran). */
  @Column(name = "command_id")
  public String commandId;

  /** Exit code of the execute script; null for SKIPPED. */
  @Column(name = "exit_code")
  public Integer exitCode;

  @Column(name = "ran_at", nullable = false)
  public Instant ranAt;
}
