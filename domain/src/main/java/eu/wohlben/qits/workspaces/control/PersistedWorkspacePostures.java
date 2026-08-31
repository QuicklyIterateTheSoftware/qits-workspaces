package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The shipped {@link WorkspacePostures}: admin mode is a column on the workspace row, so it is read
 * back from there; wrapper-main is derived from the row's branch and its repository's registry
 * entry, so it is computed and then remembered.
 *
 * <p>Reads through the ACTIVE finder, like {@link PersistedWorkspaceCredentials} beside it — a
 * resolved workspace has no container to describe, and an unknown id is not an admin workspace.
 * Every failure direction here therefore falls to <b>false</b>, which is the only direction a
 * privilege may fall to.
 *
 * <h2>Why wrapper-main is memoized</h2>
 *
 * <p>The answer is part of the container SPEC — it picks the image and two environment variables —
 * and a spec that differs from what is running is a {@code Recreate.ifChanged} replacement. So the
 * requirement is not "usually right", it is <b>the same answer at every ensure</b>, which a live
 * call to {@link RepositoryLookup} on its own cannot promise: an unreachable qits-projects throws,
 * the factory would read that as "not the wrapper", and the resume would present a plain-image spec
 * and destroy the editor's container.
 *
 * <p>The memo is what closes that, and it is sound because its three inputs do not move: a
 * workspace's branch is written once at creation and never updated, a repository's archetype is
 * what it was registered as, and its main branch is the ref the repository was cloned from. Both
 * answers are cached, positive and negative alike — a plain workspace re-deciding on every ensure
 * would be the same exposure pointed the other way. Row ids are never reused, so an entry can only
 * become dead weight, and a {@code (Long, Boolean)} per workspace ever created is not a size worth
 * a policy.
 *
 * <p><b>Only a fully-answered view may be written down.</b> An unreachable registry is not the only
 * way to not learn the answer: {@code archetype} and {@code mainBranch} are both nullable on the
 * wire, so a 200 can arrive carrying neither — and reading a missing field as "not a wrapper" would
 * be exactly the exposure above, with the difference that it gets <em>remembered</em>, for the life
 * of the process, off one half-answered read. So a view missing either field takes the same third
 * answer a thrown lookup does: false for this call, and nothing written down.
 *
 * <p>What the memo does <em>not</em> do is invent an answer it never had: a cold process whose first
 * read fails still answers false, and the container is replaced onto the plain image. What survives
 * that is the checkout — {@code qits.workspace.persist-workspace} puts {@code /workspace} on a
 * volume of its own — so the cost is the writable layer and the editor, not the work.
 */
@ApplicationScoped
public class PersistedWorkspacePostures implements WorkspacePostures {

  private static final Logger LOG = Logger.getLogger(PersistedWorkspacePostures.class);

  @Inject WorkspaceRepository workspaces;

  /**
   * The repository registry — mandatory in this context, and the only place the archetype and the
   * main branch can be learned. See the class note for why its answer is remembered rather than
   * asked afresh on every ensure.
   */
  @Inject RepositoryLookup repositories;

  /** Decided wrapper-main answers, by workspace row id. Never invalidated — see the class note. */
  private final Map<Long, Boolean> wrapperMain = new ConcurrentHashMap<>();

  @Override
  @Transactional
  public boolean isAdmin(Long rowId) {
    if (rowId == null) {
      return false;
    }
    return workspaces.findActiveById(rowId).map(w -> w.admin).orElse(false);
  }

  @Override
  @Transactional
  public boolean isWrapperMain(Long rowId) {
    if (rowId == null) {
      return false;
    }
    Boolean remembered = wrapperMain.get(rowId);
    if (remembered != null) {
      return remembered;
    }
    Optional<Workspace> found = workspaces.findActiveById(rowId);
    if (found.isEmpty()) {
      // Not remembered: an unknown id may simply be a row this transaction cannot see yet, and
      // caching "no" for it would outlive the reason.
      return false;
    }
    Workspace workspace = found.get();
    Boolean decided = decide(workspace);
    if (decided == null) {
      return false; // could not ask — say no for this call, and do not remember having said it
    }
    wrapperMain.put(rowId, decided);
    return decided;
  }

  /**
   * The predicate itself: the repository is a project's wrapper and this workspace claims its main
   * branch. {@code null} means the registry did not answer the question — it threw, or it answered
   * without an archetype or a main branch — which is deliberately a third answer: the caller says
   * false for the moment without writing that down.
   */
  private Boolean decide(Workspace workspace) {
    if (workspace.branch == null || workspace.branch.isBlank()) {
      return false;
    }
    Optional<RepositoryLookup.RepositoryView> repo;
    try {
      repo = repositories.find(workspace.repositoryId);
    } catch (RuntimeException unreachable) {
      LOG.warnf(
          unreachable,
          "could not read repository %s while deciding whether workspace %s is the wrapper's main"
              + " workspace; treating it as an ordinary workspace for this launch",
          workspace.repositoryId,
          workspace.id);
      return null;
    }
    if (repo.isEmpty()) {
      return false;
    }
    RepositoryLookup.RepositoryView view = repo.get();
    if (view.archetype() == null || view.mainBranch() == null || view.mainBranch().isBlank()) {
      // A 200 that did not answer the question. Both fields are nullable on the wire, and reading a
      // missing one as "not a wrapper" would be the unreachable case's exposure with a status code
      // in front of it — except that this one WOULD be remembered, so a single half-answered read
      // would describe the plain image at every ensure for the life of the process. Third answer.
      LOG.warnf(
          "repository %s answered without an archetype or a main branch while deciding whether"
              + " workspace %s is the wrapper's main workspace; treating it as an ordinary"
              + " workspace for this launch",
          workspace.repositoryId,
          workspace.id);
      return null;
    }
    return view.isWrapper() && workspace.branch.equals(view.mainBranch());
  }
}
