package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Test double for {@link TrustAnchorSource} (qits-2634): the platform CA's bundle as a test sets it.
 * Empty by default, which is the answer every other test in the suite composes its specs with, so
 * nothing else changes for having it on the classpath. A test that changes it calls {@link #reset}
 * in {@code @AfterEach}.
 */
@ApplicationScoped
public class FakeTrustAnchorSource implements TrustAnchorSource {

  private volatile String pem;

  /** The bundle a container created from now on is given. */
  public void hold(String pem) {
    this.pem = pem;
  }

  /** No bundle held, as before the first fetch. */
  public void reset() {
    this.pem = null;
  }

  @Override
  public Optional<String> pem() {
    return Optional.ofNullable(pem);
  }
}
