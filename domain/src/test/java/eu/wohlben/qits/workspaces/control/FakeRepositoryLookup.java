package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

  private final Map<String, String> archetypes = new ConcurrentHashMap<>();

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
   * turns empty into a 404, so "could not ask" has to be tellable from "not there": the wrapper-main
   * posture reads {@code find} and must not read an outage as "not a wrapper".
   *
   * <p>It is off unless a test turns it on, and a test that turns it on turns it back off: this is
   * one {@code @ApplicationScoped} bean for the whole module's suite.
   */
  private volatile boolean findOutage;

  /**
   * Every by-id resolution this fake has been asked for. A round-trip counter, because that is what
   * {@code find} is on a real platform — one HTTP call to qits-projects — and a caller that scans
   * candidate repositories per request is a defect no assertion about its ANSWER can see.
   */
  private final AtomicInteger findCalls = new AtomicInteger();

  /** How many times {@link #find} has been called since the last {@link #clear()}. */
  public int findCalls() {
    return findCalls.get();
  }

  @Override
  public Optional<RepositoryView> find(String repoId) {
    findCalls.incrementAndGet();
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
                archetypes.get(repoId)));
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
                    archetypes.get(entry.getKey())))
        .toList();
  }

  /** Make every by-id resolution fail the way an unreachable registry does. Reset it. */
  public void findOutage(boolean broken) {
    this.findOutage = broken;
  }

  /**
   * Register {@code repoId} as its project's WRAPPER — archetype {@code PROJECT}, which is what
   * makes a workspace on its main branch the editor's workspace. Ordinary {@link #register} leaves
   * the archetype null, which is a registry that does not answer with one and reads as "not a
   * wrapper" everywhere.
   */
  public void registerWrapper(String repoId, String mainBranch) {
    mainBranches.put(repoId, mainBranch);
    archetypes.put(repoId, RepositoryView.WRAPPER_ARCHETYPE);
  }

  /**
   * Register {@code repoId} as its project's wrapper under an explicit registered name — {@code
   * <slug>-<slug>}, which is what an editor origin's project label derives and recognises it by.
   */
  public void registerWrapper(String repoId, String mainBranch, String registeredName) {
    registerWrapper(repoId, mainBranch);
    names.put(repoId, registeredName);
  }

  /** Register {@code repoId} with an explicit archetype, whatever qits-projects would call it. */
  public void registerAs(String repoId, String mainBranch, String archetype) {
    mainBranches.put(repoId, mainBranch);
    archetypes.put(repoId, archetype);
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
    archetypes.clear();
    names.clear();
    projects.clear();
    findOutage = false;
    findCalls.set(0);
  }
}
