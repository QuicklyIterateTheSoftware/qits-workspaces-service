package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The test-side {@link RepositoryLookup}: an in-memory registry of repository id → main branch.
 *
 * <p>{@code RepositoryLookup} is a mandatory injection point, so some implementation must exist for
 * the CDI container to start. Standing in for the repositories context with a map is also what lets
 * these tests assert on main-branch behaviour without a repositories database — {@link
 * #setMainBranch} replaces {@code RepositoryService.setMainBranch}.
 */
@ApplicationScoped
public class FakeRepositoryLookup implements RepositoryLookup {

  /**
   * The project every fake repository belongs to. One constant rather than a second map: no test
   * asserts on more than one project, and {@code SCMRelease} only needs the field to be
   * carried rather than to vary.
   */
  public static final String PROJECT_ID = "test-project";

  /**
   * How a fake repository's name is derived from its id. A registered id is opaque here — {@code
   * TestOrigin} mints one — so the name is derived rather than stored, which is enough to prove
   * the release flow carries a name that is NOT the id. That is the whole defect the field exists
   * for: on a real platform a self-seeded repository's id is a UUID and its name is not.
   */
  public static String nameOf(String repoId) {
    return "name-of-" + repoId;
  }

  private final Map<String, String> mainBranches = new ConcurrentHashMap<>();

  /**
   * Projects other than {@link #PROJECT_ID}, by repository. Only a test about two projects — the
   * Git ref narrowing, which must stop at a project's edge — sets one.
   */
  private final Map<String, String> projects = new ConcurrentHashMap<>();

  private String projectOf(String repoId) {
    return projects.getOrDefault(repoId, PROJECT_ID);
  }

  /** Register {@code repoId} with {@code master} as its main branch, in another project. */
  public void registerInProject(String repoId, String projectId) {
    register(repoId);
    projects.put(repoId, projectId);
  }

  /**
   * Explicit names, overriding {@link #nameOf}. The derived name is enough wherever a test only
   * needs "the name is not the id"; a test about a name's SHAPE — a wrapper is {@code
   * <slug>-<slug>} — has to be able to say what it is.
   */
  private final Map<String, String> names = new ConcurrentHashMap<>();

  /** The registered name of {@code repoId}: whatever a test set, else the derived one. */
  private String registeredName(String repoId) {
    return names.getOrDefault(repoId, nameOf(repoId));
  }

  /**
   * Whether a by-id resolution behaves as an unreachable qits-projects does — it throws. A caller
   * turns empty into a 404, so "could not ask" has to be tellable from "not there" wherever this
   * port decides something rather than merely enriching it.
   *
   * <p>It is off unless a test turns it on, and a test that turns it on turns it back off: this is
   * one {@code @ApplicationScoped} bean for the whole module's suite.
   */
  private volatile boolean findOutage;

  @Override
  public Optional<RepositoryView> find(String repoId) {
    if (findOutage) {
      throw new IllegalStateException("qits-projects unreachable (fake outage)");
    }
    String mainBranch = mainBranches.get(repoId);
    return mainBranch == null
        ? Optional.empty()
        : Optional.of(
            new RepositoryView(
                repoId,
                registeredName(repoId),
                projectOf(repoId),
                mainBranch,
                archetypeOf(repoId)));
  }

  @Override
  public List<RepositoryView> listByProject(String projectId) {
    return mainBranches.entrySet().stream()
        .filter(entry -> projectOf(entry.getKey()).equals(projectId))
        .map(
            entry ->
                new RepositoryView(
                    entry.getKey(),
                    registeredName(entry.getKey()),
                    projectId,
                    entry.getValue(),
                    archetypeOf(entry.getKey())))
        .toList();
  }

  /**
   * The repositories a test declared to be their project's WRAPPER. Empty by default, because most
   * repositories are not one and a fake that guessed would make the archetype look derivable — which
   * is exactly the mistake the per-project editor made.
   */
  private final java.util.Set<String> wrappers = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Register {@code repoId} as {@code projectId}'s wrapper — its superproject. */
  public void registerWrapper(String repoId, String projectId, String registeredName) {
    registerNamed(repoId, "master", registeredName);
    projects.put(repoId, projectId);
    wrappers.add(repoId);
  }

  private String archetypeOf(String repoId) {
    return wrappers.contains(repoId) ? RepositoryView.WRAPPER_ARCHETYPE : "LIBRARY";
  }

  /** Make every by-id resolution fail the way an unreachable registry does. Reset it. */
  public void findOutage(boolean broken) {
    this.findOutage = broken;
  }

  /**
   * Register {@code repoId} under an explicit registered name, overriding {@link #nameOf}. The
   * derived name is enough wherever a test only needs "the name is not the id"; a test about what
   * the daemon is told to clone has to be able to say what the name is.
   */
  public void registerNamed(String repoId, String mainBranch, String registeredName) {
    register(repoId, mainBranch);
    names.put(repoId, registeredName);
  }

  /** Make {@code repoId} resolvable, with {@code master} as its main branch. */
  public void register(String repoId) {
    register(repoId, "master");
  }

  /** Make {@code repoId} resolvable with an explicit main branch. */
  public void register(String repoId, String mainBranch) {
    mainBranches.put(repoId, mainBranch);
  }

  /** Repoint an already-registered repository's main branch. */
  public void setMainBranch(String repoId, String mainBranch) {
    mainBranches.put(repoId, mainBranch);
  }

  /** Drop everything — call from {@code @BeforeEach} when a test needs a clean registry. */
  public void clear() {
    mainBranches.clear();
    names.clear();
    projects.clear();
    wrappers.clear();
    findOutage = false;
  }
}
