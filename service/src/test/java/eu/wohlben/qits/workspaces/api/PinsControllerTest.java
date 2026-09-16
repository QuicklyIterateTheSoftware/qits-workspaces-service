package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import eu.wohlben.qits.workspaceeditor.WorkspaceEditorImage;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The launch-pin route — what a container start by this process would pull.
 *
 * <p>The versions are read off the <b>pinned dependencies</b> rather than written down here: they
 * are a release train's to move, and a literal would fail the suite on the next bump while proving
 * nothing. What is asserted about them is that the answer carries <em>this process's</em> value —
 * which is the whole job of this route, since qits-artifacts' GC plans a sweep against it and a pin
 * that disagreed with what a launch really pulls would be worse than no route at all. They used to
 * be read out of config, back when config was where the version came from; the keys are overrides
 * now and normally unset, so reading them here would assert against an absent value. The
 * <b>image</b> halves stay literals, because the registry-relative spelling is the contract and the
 * configured value is fully qualified.
 *
 * <p>The two omission rules are exercised against {@link PinsController#pins} directly. A blank
 * version is a config state, and reaching it through a {@code @TestProfile} would cost a Quarkus
 * restart to prove four lines of string handling.
 */
@QuarkusTest
public class PinsControllerTest {

  private static final String workspaceImageVersion = WorkspaceImage.VERSION;

  private static final String editorImageVersion = WorkspaceEditorImage.VERSION;

  @Test
  public void theTwoLaunchImagesAnswerRegistryRelativeAndInImageOrder() {
    JsonPath answer =
        given().when().get("/workspaces/api/pins").then().statusCode(200).extract().jsonPath();

    assertThat(answer.getString("generatedAt"), notNullValue());
    // Ordered by image, then by what launches it — the consumer diffs one run against the next,
    // and the registry host the launch reference carries is not the registry's own name for it.
    assertThat(
        answer.getList("pins.image", String.class),
        is(List.of("qits/workspace", "qits/workspace-editor")));
    assertThat(answer.getList("pins.launches", String.class), is(List.of("workspace", "editor")));
    assertThat(answer.getString("pins[0].version"), is(workspaceImageVersion));
    assertThat(answer.getString("pins[1].version"), is(editorImageVersion));
  }

  /** A half-composed reference names nothing, so the row is left out rather than half-answered. */
  @Test
  public void aBlankVersionOmitsTheRowAndAnEmptyAnswerIsValid() {
    List<PinsController.LaunchPin> oneBlank =
        PinsController.pins(
            "registry.dev.localhost:8080/qits/workspace",
            "2026.904.160522",
            "registry.dev.localhost:8080/qits/workspace-editor",
            "  ");
    assertThat(oneBlank.size(), is(1));
    assertThat(oneBlank.get(0).launches(), is("workspace"));

    assertThat(PinsController.pins("qits/workspace", null, "", ""), is(List.of()));
  }

  /**
   * A first segment carrying a {@code .} or a {@code :} is a registry host; anything else is the
   * namespace the registry itself holds the image under.
   */
  @Test
  public void onlyALeadingHostSegmentIsStripped() {
    assertThat(
        PinsController.registryRelative("registry.dev.localhost:8080/qits/workspace"),
        is("qits/workspace"));
    assertThat(
        PinsController.registryRelative("localhost:5000/qits/workspace"), is("qits/workspace"));
    assertThat(
        PinsController.registryRelative("qits/workspace-editor"), is("qits/workspace-editor"));
    assertThat(
        PinsController.registryRelative("qits/build-images/maven"), is("qits/build-images/maven"));
    assertThat(PinsController.registryRelative("workspace"), is("workspace"));
  }
}
