package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.Uncaused;
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
 *
 * <p><strong>This table spent a release with no reader, and now has one:</strong> {@code
 * WorkspaceBootstrapRunController} serves {@code GET /workspaces/{id}/bootstrap-runs}. Its
 * predecessor {@code WorkspaceBootstrapController} was deleted with the rest of the host's {@code
 * /bootstrap-commands} surface — bootstrap runs <em>inside</em> the container and the daemon's own
 * {@code BootstrapRunner} does the work, so the host kept the record and gave up the route
 * (migration-path-conventions.md §1a) — and for a release nothing could ask what {@link
 * eu.wohlben.qits.workspaces.control.WorkspaceBootstrapRunner} had written here.
 *
 * <p>The new reader is not that controller returning: it is a <em>read of a host table</em>, and
 * the run verbs stay on the daemon's own API where their execution is. That distinction is the one
 * the "nothing forwards" rule turns on, and it is why the table earns its keep — these rows survive
 * any number of container recreates, which the daemon's own lifetime state does not. The declared
 * chain (what the steps are) is the daemon's {@code GET /bootstrap-commands}; a client joins the
 * two on {@link #bootstrapCommandId}.
 *
 * <p>{@code @Uncaused} by decision, and the row's own shape is the reason. It is an <b>updatable
 * singleton</b>: one row per {@code (workspace, bootstrapCommandId)}, inserted by the first run and
 * <em>overwritten</em> by every run after it. The stamp is insert-only, so a causation column would
 * name the first run's cause and then keep naming it while every column beside it moved on — worse
 * than empty. The write also sits on the async observer thread and the manual-run executor, where
 * no scope stands anyway; the run's own audit trail is the {@code command} rows.
 */
@Entity
@Table(
    name = "workspace_bootstrap_run",
    uniqueConstraints =
        @UniqueConstraint(
            name = "UQ_workspace_bootstrap_run",
            columnNames = {"workspace_id_fk", "bootstrap_command_id"}))
@Uncaused
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
