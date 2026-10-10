package eu.wohlben.qits.workspaces.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A framework-free description of one workspace container — what it is called, what it runs, what
 * it mounts and what it is allowed. It accumulates those parameters through fluent setters and
 * hands them back through same-named readers; it renders nothing and knows no runtime.
 *
 * <p><b>It used to render a {@code docker run} argv</b> ({@code toRunArgv}), because the host held
 * the docker socket and this was the last thing before {@code ProcessBuilder}. The socket is gone —
 * qits-containers owns the daemon and this service asks it over HTTP — so the flag order, the
 * repeated {@code -e}/{@code -v}/{@code --label} spellings and the {@code -d --init} prefix all went
 * with the process they were being assembled for. What is left is the part that was never about
 * docker: the set of decisions a workspace container is made of. {@code containershost/
 * WorkspaceContainers} is the one place that turns them into the orchestrator's wire spec, and it is
 * the only reader of this class outside its own tests.
 *
 * <p>Callers do not construct this directly with the cross-cutting config; they obtain a pre-seeded
 * instance from {@link WorkspaceContainerFactory} (which guarantees the shared credential volume,
 * the {@code qits.*} labels, the docker-host alias and the host uid are always present) and only
 * add what varies.
 *
 * <p><b>Insertion order is preserved for the labels, the environment and the mounts</b>, and that is
 * a property rather than an accident: the spec assembled from them is asserted literally by the
 * adapter's own test, and a set's iteration order is not a thing to assert against.
 */
public final class WorkspaceContainer {

  /** One named volume and where it lands inside the container. */
  public record Mount(String volumeName, String containerPath) {}

  private String name;
  private String user;
  private final Map<String, String> labels = new LinkedHashMap<>();
  private final List<String> addHosts = new ArrayList<>();
  private final Map<String, String> env = new LinkedHashMap<>();
  private final List<Mount> volumes = new ArrayList<>();
  private String network;
  private String memory;
  private String memorySwap;
  private String pidsLimit;
  private String cpus;
  private Integer oomScoreAdj;
  private String image;
  private boolean hostDockerSocket;


  public WorkspaceContainer name(String name) {
    this.name = name;
    return this;
  }

  public WorkspaceContainer user(String user) {
    this.user = user;
    return this;
  }

  public WorkspaceContainer label(String key, String value) {
    this.labels.put(key, value == null ? "" : value);
    return this;
  }

  /** Add a {@code --add-host=<hostSpec>} entry (e.g. {@code host.docker.internal:host-gateway}). */
  public WorkspaceContainer addHost(String hostSpec) {
    this.addHosts.add(hostSpec);
    return this;
  }

  /**
   * Set a container environment variable ({@code -e key=value}). Applied to the container's main
   * process, and inherited by everything the in-container daemon spawns under it — so it is the
   * seam for config that must reach every command in the container, not just the entrypoint.
   */
  public WorkspaceContainer env(String key, String value) {
    this.env.put(key, value == null ? "" : value);
    return this;
  }

  /** Mount {@code volumeName} at {@code mountPath}, read/write. */
  public WorkspaceContainer volume(String volumeName, String mountPath) {
    this.volumes.add(new Mount(volumeName, mountPath));
    return this;
  }

  /**
   * Attach the container to a user-defined network so qits (also on it) can reach the container's
   * ports by its DNS name — no host-port publishing. A blank name adds nothing.
   */
  public WorkspaceContainer network(String network) {
    this.network = network;
    return this;
  }

  /**
   * Cap the container's memory ({@code --memory}). With the cgroup limit in place every JVM inside
   * sizes its default heap against it (container support is on by default), so no per-tool {@code
   * -Xmx} plumbing is needed. Blank/null caps nothing. Unless {@link #memorySwap} grants headroom,
   * this value becomes the swap cap too, so the container can neither exceed the cap nor
   * swap-thrash the host past it.
   */
  public WorkspaceContainer memory(String limit) {
    this.memory = limit;
    return this;
  }

  /**
   * The memory+swap total ({@code --memory-swap}). Docker's semantics: the value INCLUDES {@link
   * #memory}, so {@code 4g}/{@code 8g} is 4G of RAM plus 4G of swap. Blank/null grants no swap —
   * the adapter then sends {@link #memory} for both, the hard cap this service always had.
   */
  public WorkspaceContainer memorySwap(String limit) {
    this.memorySwap = limit;
    return this;
  }

  /** Cap the container's process/thread count. Blank/null caps nothing. */
  public WorkspaceContainer pidsLimit(String pidsLimit) {
    this.pidsLimit = pidsLimit;
    return this;
  }

  /** Cap the container's CPU share. Blank/null caps nothing. */
  public WorkspaceContainer cpus(String cpus) {
    this.cpus = cpus;
    return this;
  }

  public WorkspaceContainer oomScoreAdj(Integer oomScoreAdj) {
    this.oomScoreAdj = oomScoreAdj;
    return this;
  }

  public WorkspaceContainer image(String image) {
    this.image = image;
    return this;
  }

  /**
   * Bind the host's docker socket into the container — <b>admin mode</b>, and the one privilege a
   * workspace can be granted beyond being a workspace.
   *
   * <p>A boolean and not a path, deliberately, and it is the same boolean qits-containers' own spec
   * carries for the same reason: what gets mounted must not be something anything upstream of the
   * orchestrator gets to choose. A container holding it is root-equivalent on the host, so the only
   * thing that may turn it on is the workspace row's own {@code admin} column ({@link
   * WorkspacePostures}), written by the request that created the workspace.
   */
  public WorkspaceContainer hostDockerSocket(boolean value) {
    this.hostDockerSocket = value;
    return this;
  }


  // --- what was decided, for the one adapter that turns it into a wire spec --------------------
  //
  // Same names as the setters, arity apart. There is deliberately no `command`: a workspace
  // container runs the image's qits-workspace-daemon ENTRYPOINT and nothing else, with no `sleep
  // infinity` fallback, so a container that cannot run the daemon fails to start rather than
  // lingering unmanaged. See WorkspaceContainerFactory.forWorkspace's closing paragraph.

  public String name() {
    return name;
  }

  public String user() {
    return user;
  }

  /**
   * The {@code qits.*} identity labels, in the order they were set. A {@link LinkedHashMap} copy
   * rather than {@code Map.copyOf}, which is unordered and would drop the one property the caller
   * asserts against.
   */
  public Map<String, String> labels() {
    return new LinkedHashMap<>(labels);
  }

  /** Host aliases, each already spelled {@code name:target}. */
  public List<String> addHosts() {
    return List.copyOf(addHosts);
  }

  /** The container's environment, in the order it was set. */
  public Map<String, String> env() {
    return new LinkedHashMap<>(env);
  }

  public List<Mount> volumes() {
    return List.copyOf(volumes);
  }

  public String network() {
    return network;
  }

  /** The memory cap, or null/blank for none — see {@link #memory(String)}. */
  public String memory() {
    return memory;
  }

  /** The memory+swap total, or null/blank for "no swap" — see {@link #memorySwap(String)}. */
  public String memorySwap() {
    return memorySwap;
  }

  public String pidsLimit() {
    return pidsLimit;
  }

  public String cpus() {
    return cpus;
  }

  /** The oom-score-adj, or null for none. Higher = reaped sooner under host memory pressure. */
  public Integer oomScoreAdj() {
    return oomScoreAdj;
  }

  public String image() {
    return image;
  }

  /** Whether this container is the admin kind — see {@link #hostDockerSocket(boolean)}. */
  public boolean hostDockerSocket() {
    return hostDockerSocket;
  }

}
