package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * A config view from a daemon built before qits-947 still carries the {@code services:} key (and
 * its legacy {@code daemons:} alias), which {@link QitsConfig} no longer has a component for. Such a
 * view must still deserialize, or {@code WorkspaceDaemonRegistry.readConfig} degrades it to {@link
 * QitsConfig#EMPTY} and the workspace loses its bootstrap chain and actions with it.
 *
 * <p>The mapper here fails on unknown properties explicitly, so the test proves the record's own
 * annotation does the work rather than whatever the injected mapper's defaults happen to be.
 */
class QitsConfigTest {

  private final ObjectMapper strict =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Test
  void anOldDaemonsServicesKeyIsIgnoredAndTheRestStillReads() throws Exception {
    String json =
        """
        {
          "repository": {"mainBranch": "main"},
          "actions": [{"name": "lint", "execute": "npm run lint"}],
          "services": [{"name": "web", "start": "npm start", "autoStart": true,
                        "restartPolicy": "ON_FAILURE",
                        "webView": {"port": 4200}}],
          "daemons": [{"name": "legacy", "start": "true"}],
          "bootstrap": [{"name": "install", "execute": "npm ci"}]
        }
        """;

    QitsConfig config = strict.readValue(json, QitsConfig.class);

    assertEquals("main", config.repository().mainBranch());
    assertEquals("lint", config.actions().get(0).id());
    assertEquals("install", config.bootstrap().get(0).name());
  }
}
