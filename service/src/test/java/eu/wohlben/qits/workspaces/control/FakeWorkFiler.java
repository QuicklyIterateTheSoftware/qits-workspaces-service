package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link WorkFiler} that records what a capture filed (qits-1152, D25) and answers a made-up
 * ticket. It wins over {@code wiring/HttpWorkFiler}, which is a {@code @DefaultBean}.
 */
@ApplicationScoped
public class FakeWorkFiler implements WorkFiler {

  /** One filed ticket. */
  public record Filing(String projectId, String title, String impetus) {}

  // Private, with methods: a test holds a client proxy, and a field read on it reads the proxy's.
  private final List<Filing> filed = Collections.synchronizedList(new ArrayList<>());

  public int count() {
    return filed.size();
  }

  @Override
  public Filed file(String projectId, String title, String impetus) {
    filed.add(new Filing(projectId, title, impetus));
    int n = filed.size();
    return new Filed("00000000-0000-4000-8000-" + String.format("%012d", n), "capture-" + n);
  }

  public Filing last() {
    synchronized (filed) {
      return filed.get(filed.size() - 1);
    }
  }
}
