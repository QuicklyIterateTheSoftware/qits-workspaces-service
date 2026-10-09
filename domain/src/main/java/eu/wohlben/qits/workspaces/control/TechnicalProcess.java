package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One replayable, segmented unit of long-running back-office work ("start a workspace" is the first
 * instance) — the multi-segment generalization of {@code CommandSession}'s one-PTY replay ring.
 * Producers open named segments, append lines, and settle segments; every attached {@link Listener}
 * receives the same totally-ordered frame stream, and a late (re)subscriber first gets a replay of
 * everything buffered so far. The process is decoupled from any connection: listeners attach and
 * detach freely while the work keeps running.
 *
 * <p>Completion is a two-part predicate: the synchronous provision phase settles via {@link
 * #finishProvision}, and the asynchronous bootstrap phase (running on the CDI async observer
 * thread) reports via {@link #finishBootstrap} once the daemon's chain is over — passed through,
 * succeeded or failed. The terminal {@code done} frame fires once both parts are in — or
 * immediately on a provision failure. All mutation happens under the process monitor, so a freshly
 * attached listener never interleaves replayed and live frames (same trade-off as {@code
 * CommandSession}: a stuck listener briefly stalls the producer; dead listeners are pruned on the
 * next frame).
 *
 * <p>Buffers are bounded per segment: the head of the output is kept verbatim, the tail rolls, and
 * a replay marks the elided middle — {@code npm install} output is large, and the head (the command
 * banner) plus the tail (the failure) are the informative parts.
 */
public final class TechnicalProcess implements WorkspaceProcessTracker.Handle {

  /** A subscriber to the frame stream — the service module adapts this to an SSE {@code Multi}. */
  public interface Listener {
    void onFrame(TechnicalProcessFrame frame);

    /** The terminal {@code done} frame was delivered; no further frames will follow. */
    void onDone();

    /** Whether this listener can still receive frames; closed listeners are pruned. */
    boolean isOpen();
  }

  /** Per-segment byte budget for the kept head of the line buffer. */
  private static final int HEAD_CAPACITY_BYTES = 64 * 1024;

  /** Per-segment byte budget for the rolling tail of the line buffer. */
  private static final int TAIL_CAPACITY_BYTES = 192 * 1024;

  /** A runaway single line is truncated to this many characters. */
  private static final int MAX_LINE_CHARS = 16 * 1024;

  private static final class Segment {
    final String name;
    final List<String> head = new ArrayList<>();
    final Deque<String> tail = new ArrayDeque<>();
    int headBytes;
    int tailBytes;
    int elidedLines;
    Boolean ok; // null while open
    String hint; // optional failure classification, set at settle time
    String hintTarget; // optional UI target for the hint (e.g. the repo id to sign into)

    Segment(String name) {
      this.name = name;
    }
  }

  private final String id;
  private final String repoId;
  private final String workspaceId;

  /** Invoked exactly once, right after the terminal {@code done} frame went out. */
  private final Consumer<TechnicalProcess> onDone;

  private final Map<String, Segment> segments = new LinkedHashMap<>();
  private final List<Listener> listeners = new ArrayList<>();
  private final AtomicLong seq = new AtomicLong();

  private Boolean provisionOk; // null while the synchronous provision phase is still running
  private boolean bootstrapFinished; // false until the asynchronous bootstrap phase reported
  private boolean terminal;
  private boolean doneOk;

  /** Wall-clock of the last emitted frame — the registry's idle backstop measures against it. */
  private volatile long lastActivityMillis = System.currentTimeMillis();

  /** The workspace this runs against, by id; null for repository-scoped processes. */
  private final Long workspaceRowId;

  public Long workspaceRowId() {
    return workspaceRowId;
  }

  TechnicalProcess(
      String id,
      String repoId,
      String workspaceId,
      Long workspaceRowId,
      Consumer<TechnicalProcess> onDone) {
    this.id = id;
    this.repoId = repoId;
    this.workspaceId = workspaceId;
    this.workspaceRowId = workspaceRowId;
    this.onDone = onDone;
  }

  public String id() {
    return id;
  }

  public String repoId() {
    return repoId;
  }

  public String workspaceId() {
    return workspaceId;
  }

  public synchronized boolean isTerminal() {
    return terminal;
  }

  /**
   * Milliseconds since this process last emitted a frame (produced any output or settled anything).
   * The registry reaps a process only after it has been <em>idle</em> this long, so an actively
   * streaming chain — however long in total — is never cut mid-run, while a truly stuck one still
   * can't leak forever.
   */
  public long millisSinceLastActivity() {
    return System.currentTimeMillis() - lastActivityMillis;
  }

  /** Attach a listener: replay everything buffered, then stream live (or complete if terminal). */
  public synchronized void attach(Listener listener) {
    for (Segment segment : segments.values()) {
      listener.onFrame(TechnicalProcessFrame.segmentOpen(segment.name, seq.getAndIncrement()));
      for (String line : segment.head) {
        listener.onFrame(TechnicalProcessFrame.line(segment.name, seq.getAndIncrement(), line));
      }
      if (segment.elidedLines > 0) {
        listener.onFrame(
            TechnicalProcessFrame.line(
                segment.name,
                seq.getAndIncrement(),
                "… " + segment.elidedLines + " line(s) elided …"));
      }
      for (String line : segment.tail) {
        listener.onFrame(TechnicalProcessFrame.line(segment.name, seq.getAndIncrement(), line));
      }
      if (segment.ok != null) {
        listener.onFrame(
            TechnicalProcessFrame.segmentSettled(
                segment.name, seq.getAndIncrement(), segment.ok, segment.hint, segment.hintTarget));
      }
    }
    if (terminal) {
      listener.onFrame(TechnicalProcessFrame.done(seq.getAndIncrement(), doneOk));
      listener.onDone();
      return;
    }
    listeners.add(listener);
  }

  public synchronized void detach(Listener listener) {
    listeners.remove(listener);
  }

  /** Open a named segment (frames for it may then follow). No-op if it already exists. */
  public synchronized void openSegment(String name) {
    if (terminal || segments.containsKey(name)) {
      return;
    }
    segments.put(name, new Segment(name));
    broadcast(TechnicalProcessFrame.segmentOpen(name, seq.getAndIncrement()));
  }

  /**
   * Append one output line to a segment (opening it on first use). Lines to an already-settled
   * segment are dropped — a segment's story ends at its verdict.
   */
  public synchronized void appendLine(String segmentName, String line) {
    if (terminal || line == null) {
      return;
    }
    openSegment(segmentName);
    Segment segment = segments.get(segmentName);
    if (segment == null || segment.ok != null) {
      return;
    }
    String capped = line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line;
    buffer(segment, capped);
    broadcast(TechnicalProcessFrame.line(segmentName, seq.getAndIncrement(), capped));
  }

  /** Whether {@code segmentName} exists and is already settled. */
  public synchronized boolean isSegmentSettled(String segmentName) {
    Segment segment = segments.get(segmentName);
    return segment != null && segment.ok != null;
  }

  /** Settle a segment {@code ok}/{@code failed}. Idempotent — the first verdict wins. */
  public synchronized void settleSegment(String segmentName, boolean ok) {
    settleSegment(segmentName, ok, null, null);
  }

  /**
   * {@link #settleSegment(String, boolean)} with an optional failure-classification {@code hint}
   * (e.g. {@link TechnicalProcessFrame#HINT_REMOTE_AUTH}) and a {@code hintTarget} the UI acts on
   * (e.g. the repo id to sign into) — both stored on the segment so a late attacher's replay
   * carries them too.
   */
  public synchronized void settleSegment(
      String segmentName, boolean ok, String hint, String hintTarget) {
    if (terminal) {
      return;
    }
    openSegment(segmentName);
    Segment segment = segments.get(segmentName);
    if (segment == null || segment.ok != null) {
      return;
    }
    segment.ok = ok;
    segment.hint = hint;
    segment.hintTarget = hintTarget;
    broadcast(
        TechnicalProcessFrame.segmentSettled(
            segmentName, seq.getAndIncrement(), ok, hint, hintTarget));
    maybeFinish();
  }

  /**
   * The synchronous provision phase is over. On failure the process ends immediately (the bootstrap
   * phase never happens); on success it ends once {@link #finishBootstrap} reports too.
   */
  public synchronized void finishProvision(boolean ok) {
    if (terminal || provisionOk != null) {
      return;
    }
    provisionOk = ok;
    if (!ok) {
      finish();
      return;
    }
    maybeFinish();
  }

  /**
   * Fail the provision phase: settle whatever segment is still open as {@code failed} (appending
   * {@code message} to the last open one so the reason is in the stream even when nothing streamed
   * before the throw), then end the process.
   */
  public synchronized void failProvision(String message) {
    failProvision(message, null, null);
  }

  /**
   * {@link #failProvision(String)} with an optional failure-classification {@code hint} and {@code
   * hintTarget} riding the settle of every segment this fails (see {@link #settleSegment(String,
   * boolean, String, String)}).
   */
  public synchronized void failProvision(String message, String hint, String hintTarget) {
    if (terminal) {
      return;
    }
    Segment lastOpen = null;
    for (Segment segment : segments.values()) {
      if (segment.ok == null) {
        lastOpen = segment;
      }
    }
    if (lastOpen == null) {
      openSegment("error");
      lastOpen = segments.get("error");
    }
    if (message != null && !message.isBlank()) {
      appendLine(lastOpen.name, message);
    }
    for (Segment segment : segments.values()) {
      if (segment.ok == null) {
        segment.ok = false;
        segment.hint = hint;
        segment.hintTarget = hintTarget;
        broadcast(
            TechnicalProcessFrame.segmentSettled(
                segment.name, seq.getAndIncrement(), false, hint, hintTarget));
      }
    }
    finishProvision(false);
  }

  /**
   * The asynchronous bootstrap phase is over — the chain passed through (a plain restart, the kill
   * switch, no daemon control plane), succeeded, failed, or was handed to a manual run that owns
   * it. The verdict rides the {@code bootstrap:<name>} segments, not this call: it only says no
   * further phase is coming, so {@code done} fires as soon as the provision phase has reported too.
   * Every path through {@code WorkspaceBootstrapRunner} must call it, or the process stays open
   * until the registry's idle reaper. Idempotent.
   */
  public synchronized void finishBootstrap() {
    if (terminal || bootstrapFinished) {
      return;
    }
    bootstrapFinished = true;
    maybeFinish();
  }

  /**
   * Complete a process whose work turned out to be a no-op (e.g. the container was already
   * running): one informational segment, no bootstrap phase, immediate {@code done ok}.
   */
  public synchronized void completeNoOp(String segmentName, String note) {
    if (terminal) {
      return;
    }
    openSegment(segmentName);
    appendLine(segmentName, note);
    settleSegment(segmentName, true);
    finishBootstrap();
    finishProvision(true);
  }

  /**
   * Backstop for a process that never converges (e.g. a daemon that never reports its bootstrap
   * chain): force the terminal {@code done failed} frame without settling the
   * still-open segments. No-op once terminal.
   */
  public synchronized void forceFinish() {
    if (terminal) {
      return;
    }
    provisionOk = provisionOk != null && provisionOk;
    finish();
  }

  private void maybeFinish() {
    if (terminal || provisionOk == null || !provisionOk || !bootstrapFinished) {
      return;
    }
    finish();
  }

  private void finish() {
    boolean ok = provisionOk != null && provisionOk;
    if (ok) {
      for (Segment segment : segments.values()) {
        if (segment.ok == null || !segment.ok) {
          ok = false;
          break;
        }
      }
    }
    terminal = true;
    doneOk = ok;
    broadcast(TechnicalProcessFrame.done(seq.getAndIncrement(), ok));
    // Snapshot-then-clear before notifying: a listener's onDone typically completes its emitter,
    // whose termination hook calls detach() re-entrantly — iterating the live list would CME.
    List<Listener> notify = List.copyOf(listeners);
    listeners.clear();
    for (Listener listener : notify) {
      try {
        listener.onDone();
      } catch (RuntimeException ignored) {
        // a broken listener must not keep the process from completing
      }
    }
    onDone.accept(this);
  }

  private void broadcast(TechnicalProcessFrame frame) {
    lastActivityMillis = System.currentTimeMillis();
    for (Iterator<Listener> it = listeners.iterator(); it.hasNext(); ) {
      Listener listener = it.next();
      if (!listener.isOpen()) {
        it.remove();
        continue;
      }
      try {
        listener.onFrame(frame);
      } catch (RuntimeException e) {
        it.remove();
      }
    }
  }

  /** Head-and-tail bounded buffering: the head freezes, the tail rolls and counts elisions. */
  private static void buffer(Segment segment, String line) {
    int bytes = line.length() + 1;
    if (segment.headBytes + bytes <= HEAD_CAPACITY_BYTES) {
      segment.head.add(line);
      segment.headBytes += bytes;
      return;
    }
    segment.tail.addLast(line);
    segment.tailBytes += bytes;
    while (segment.tailBytes > TAIL_CAPACITY_BYTES && segment.tail.size() > 1) {
      segment.tailBytes -= segment.tail.removeFirst().length() + 1;
      segment.elidedLines++;
    }
  }
}
