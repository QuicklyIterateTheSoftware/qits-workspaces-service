package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The framework-free process core: replay-then-live ordering, the two-part done predicate
 * (provision + declared daemon set), failure settling, and the head+tail buffer bound.
 */
class TechnicalProcessTest {

  /** Records every frame; the plain-JUnit stand-in for the SSE adapter. */
  private static final class RecordingListener implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();
    boolean done;

    @Override
    public void onFrame(TechnicalProcessFrame frame) {
      frames.add(frame);
    }

    @Override
    public void onDone() {
      done = true;
    }

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  private final List<TechnicalProcess> completed = new ArrayList<>();

  private TechnicalProcess process() {
    return new TechnicalProcess("p-1", "repo-1", "ws-1", 1L, completed::add);
  }

  private static List<String> kinds(RecordingListener listener) {
    return listener.frames.stream().map(TechnicalProcessFrame::kind).toList();
  }

  @Test
  void aLateSubscriberReplaysBufferedSegmentsThenReceivesLiveFramesThenDone() {
    TechnicalProcess process = process();
    process.openSegment("container");
    process.appendLine("container", "created");
    process.settleSegment("container", true);
    process.openSegment("clone");
    process.appendLine("clone", "Cloning…");

    RecordingListener late = new RecordingListener();
    process.attach(late);
    assertEquals(
        List.of("segment-open", "line", "segment-settled", "segment-open", "line"), kinds(late));

    process.appendLine("clone", "done.");
    process.settleSegment("clone", true);
    process.finishProvision(true);
    process.finishBootstrap();

    assertEquals(
        List.of(
            "segment-open",
            "line",
            "segment-settled",
            "segment-open",
            "line",
            "line",
            "segment-settled",
            "done"),
        kinds(late));
    assertTrue(late.done);
    assertEquals("ok", late.frames.get(late.frames.size() - 1).status());
    assertEquals(List.of("p-1"), completed.stream().map(TechnicalProcess::id).toList());
  }

  @Test
  void doneWaitsForTheBootstrapPhaseAfterASuccessfulProvision() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("container");
    process.settleSegment("container", true);
    process.finishProvision(true);
    assertFalse(process.isTerminal(), "the bootstrap phase has not reported — must stay open");

    process.finishBootstrap();

    assertTrue(process.isTerminal(), "both phases in — done");
    assertEquals("ok", listener.frames.get(listener.frames.size() - 1).status());
  }

  @Test
  void theBootstrapPhaseMayReportBeforeTheProvisionDoes() {
    TechnicalProcess process = process();
    process.finishBootstrap();
    assertFalse(process.isTerminal(), "the provision phase has not reported — must stay open");

    process.finishProvision(true);

    assertTrue(process.isTerminal());
  }

  @Test
  void aFailedBootstrapSegmentYieldsDoneFailed() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.finishProvision(true);
    process.openSegment(WorkspaceBootstrapRunner.bootstrapSegment("install"));
    process.settleSegment(WorkspaceBootstrapRunner.bootstrapSegment("install"), false);
    assertFalse(process.isTerminal(), "a settled segment alone does not end the process");

    process.finishBootstrap();

    assertTrue(process.isTerminal());
    assertEquals("failed", listener.frames.get(listener.frames.size() - 1).status());
  }

  @Test
  void finishBootstrapIsIdempotentAndANoOpOnceTerminal() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.finishProvision(true);
    process.finishBootstrap();
    int frames = listener.frames.size();

    process.finishBootstrap();

    assertEquals(frames, listener.frames.size(), "a second report emits nothing");
    assertEquals(1, completed.size(), "onDone ran exactly once");
  }

  @Test
  void failProvisionSettlesTheOpenSegmentWithTheMessageAndEndsImmediately() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("clone");
    process.failProvision("Clone into container failed: boom");

    assertTrue(process.isTerminal(), "provision failure ends without any bootstrap phase");
    TechnicalProcessFrame settle =
        listener.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("clone", settle.segment());
    assertEquals("failed", settle.status());
    assertTrue(
        listener.frames.stream()
            .anyMatch(f -> "line".equals(f.kind()) && f.line().contains("boom")));
    assertEquals("failed", listener.frames.get(listener.frames.size() - 1).status());
  }

  @Test
  void aHintedSettleBroadcastsTheHintAndReplaysItToLateSubscribers() {
    TechnicalProcess process = process();
    RecordingListener live = new RecordingListener();
    process.attach(live);
    process.openSegment("push:repo.git");
    process.settleSegment(
        "push:repo.git", false, TechnicalProcessFrame.HINT_REMOTE_AUTH, "child-7");

    TechnicalProcessFrame settle =
        live.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("remote-auth", settle.hint());
    assertEquals("child-7", settle.hintTarget());

    // The hint (and its target) is stored on the segment, so a late attacher's replay carries it
    // too.
    RecordingListener late = new RecordingListener();
    process.attach(late);
    TechnicalProcessFrame replayed =
        late.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("remote-auth", replayed.hint());
    assertEquals("child-7", replayed.hintTarget());

    // An unhinted settle stays hint-free.
    process.openSegment("other");
    process.settleSegment("other", false);
    TechnicalProcessFrame plain =
        live.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()) && "other".equals(f.segment()))
            .findFirst()
            .orElseThrow();
    assertNull(plain.hint());
    assertNull(plain.hintTarget());
  }

  @Test
  void aHintedFailProvisionHintsTheSegmentsItFails() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("pull:repo.git");
    process.failProvision(
        "fatal: Authentication failed for 'x'", TechnicalProcessFrame.HINT_REMOTE_AUTH, "repo-1");

    TechnicalProcessFrame settle =
        listener.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("remote-auth", settle.hint());
    assertEquals("repo-1", settle.hintTarget());
    assertTrue(process.isTerminal());
  }

  @Test
  void aTerminalProcessReplaysEverythingAndCompletesImmediately() {
    TechnicalProcess process = process();
    process.completeNoOp("container-start", "already running");

    RecordingListener late = new RecordingListener();
    process.attach(late);
    assertTrue(late.done);
    assertEquals(List.of("segment-open", "line", "segment-settled", "done"), kinds(late));
  }

  @Test
  void settleIsIdempotentAndLinesToSettledSegmentsAreDropped() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("s");
    process.settleSegment("s", true);
    process.settleSegment("s", false);
    process.appendLine("s", "late line");

    long settles = listener.frames.stream().filter(f -> "segment-settled".equals(f.kind())).count();
    long lines = listener.frames.stream().filter(f -> "line".equals(f.kind())).count();
    assertEquals(1, settles, "first verdict wins");
    assertEquals(0, lines, "a settled segment accepts no more lines");
  }

  @Test
  void theBufferKeepsHeadAndTailAndMarksTheElidedMiddleOnReplay() {
    TechnicalProcess process = process();
    process.openSegment("clone");
    // Well past 256 KB total so the rolling tail must evict: 40k lines à ~10 bytes.
    for (int i = 0; i < 40_000; i++) {
      process.appendLine("clone", "line-" + i);
    }

    RecordingListener late = new RecordingListener();
    process.attach(late);
    List<String> lines =
        late.frames.stream()
            .filter(f -> "line".equals(f.kind()))
            .map(TechnicalProcessFrame::line)
            .toList();
    assertEquals("line-0", lines.get(0), "the head survives verbatim");
    assertEquals("line-39999", lines.get(lines.size() - 1), "the tail ends at the newest line");
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("elided")),
        "the evicted middle is marked in the replay");
    assertTrue(lines.size() < 40_000, "the replay is bounded");
  }

  @Test
  void forceFinishEmitsDoneFailedWithoutSettlingOpenSegments() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("container");
    process.forceFinish();

    assertTrue(process.isTerminal());
    assertEquals("done", listener.frames.get(listener.frames.size() - 1).kind());
    assertEquals("failed", listener.frames.get(listener.frames.size() - 1).status());
    assertEquals(
        0,
        listener.frames.stream().filter(f -> "segment-settled".equals(f.kind())).count(),
        "open segments stay unsettled — the backstop only ends the stream");
  }
}
