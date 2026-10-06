package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Base64;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** The prompt attachments over HTTP: the row shape, the sniff, the caps and the scoping. */
@QuarkusTest
public class WorkspacePromptAttachmentControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  private Long workspace(String label) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, label, "master", label);
    return workspaceIds.of(repoId, label);
  }

  private static String base(Long id) {
    return "/workspaces/api/workspaces/" + id + "/prompt-attachments";
  }

  private static WorkspacePromptAttachmentController.AddAttachmentRequest paste(
      String label, String data) {
    return new WorkspacePromptAttachmentController.AddAttachmentRequest(
        "image/png", label, "PASTE", data);
  }

  @Test
  public void anAttachedImageComesBackWithItsBytesOldestFirst() throws Exception {
    Long id = workspace("attach-roundtrip");

    // No images is an ordinary state, not a missing resource.
    given().get(base(id)).then().statusCode(200).body("attachments", hasSize(0));

    String first =
        given()
            .contentType(ContentType.JSON)
            .body(paste("Pasted image 1", PromptAttachmentFixtures.ONE_PIXEL_PNG))
            .post(base(id))
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("mimeType", equalTo("image/png"))
            .body("label", equalTo("Pasted image 1"))
            .body("source", equalTo("PASTE"))
            .body("createdAt", notNullValue())
            .extract()
            .path("id");

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptAttachmentController.AddAttachmentRequest(
                "image/png", "Sketch 1", "SKETCH", PromptAttachmentFixtures.ONE_PIXEL_JPEG))
        .post(base(id))
        .then()
        .statusCode(201)
        // The bytes decide, not the claim: this was posted as image/png and is a JPEG.
        .body("mimeType", equalTo("image/jpeg"))
        .body("source", equalTo("SKETCH"));

    given()
        .get(base(id))
        .then()
        .statusCode(200)
        .body("attachments", hasSize(2))
        .body("attachments[0].id", equalTo(first))
        .body("attachments[0].label", equalTo("Pasted image 1"))
        // The list carries the payload — the draft blob references rows by id only, so this is the
        // one thing the compose UI can rebuild its thumbnails from after a reload.
        .body("attachments[0].dataBase64", equalTo(PromptAttachmentFixtures.ONE_PIXEL_PNG))
        .body("attachments[1].label", equalTo("Sketch 1"));
  }

  @Test
  public void contentHasAStableBrowserUrlWhenTheSketchIsUpdated() throws Exception {
    Long id = workspace("attachment-content");
    String attachmentId =
        given()
            .contentType(ContentType.JSON)
            .body(paste("Sketch 1", PromptAttachmentFixtures.ONE_PIXEL_PNG))
            .post(base(id))
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    String contentUrl = base(id) + "/" + attachmentId + "/content";

    byte[] first =
        given()
            .get(contentUrl)
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("Cache-Control", "no-cache")
            .extract()
            .asByteArray();
    assertArrayEquals(
        Base64.getDecoder().decode(PromptAttachmentFixtures.ONE_PIXEL_PNG), first);

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptAttachmentController.AddAttachmentRequest(
                "image/png", "Sketch 1", "SKETCH", PromptAttachmentFixtures.ONE_PIXEL_JPEG))
        .put(base(id) + "/" + attachmentId)
        .then()
        .statusCode(200)
        .body("id", equalTo(attachmentId))
        .body("mimeType", equalTo("image/jpeg"));

    byte[] updated =
        given()
            .get(contentUrl)
            .then()
            .statusCode(200)
            .contentType("image/jpeg")
            .extract()
            .asByteArray();
    assertArrayEquals(
        Base64.getDecoder().decode(PromptAttachmentFixtures.ONE_PIXEL_JPEG), updated);
  }

  @Test
  public void anythingThatIsNotAPngOrJpegIs400() throws Exception {
    Long id = workspace("attach-sniff");

    given()
        .contentType(ContentType.JSON)
        .body(paste("A gif", PromptAttachmentFixtures.ONE_PIXEL_GIF))
        .post(base(id))
        .then()
        .statusCode(400)
        .body("message", equalTo("Attachment is not a PNG or JPEG image"));

    given()
        .contentType(ContentType.JSON)
        .body(paste("Not base64", "!!!! not base64 !!!!"))
        .post(base(id))
        .then()
        .statusCode(400)
        .body("message", equalTo("Attachment data is not valid base64"));

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptAttachmentController.AddAttachmentRequest(
                "image/png", "Whence", "TELEPATHY", PromptAttachmentFixtures.ONE_PIXEL_PNG))
        .post(base(id))
        .then()
        .statusCode(400);

    given().get(base(id)).then().statusCode(200).body("attachments", hasSize(0));
  }

  @Test
  public void anOversizedImageIs413() throws Exception {
    Long id = workspace("attach-oversized");

    // A real PNG signature followed by enough padding to pass the per-image cap, so the size guard
    // is what refuses this and not the sniff.
    byte[] oversized = new byte[3 * 1024 * 1024];
    byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(signature, 0, oversized, 0, signature.length);

    given()
        .contentType(ContentType.JSON)
        .body(paste("Enormous", Base64.getEncoder().encodeToString(oversized)))
        .post(base(id))
        .then()
        .statusCode(413);

    given().get(base(id)).then().statusCode(200).body("attachments", hasSize(0));
  }

  @Test
  public void deleteIsScopedToItsWorkspace() throws Exception {
    Long mine = workspace("attach-mine");
    Long theirs = workspace("attach-theirs");

    String attachmentId =
        given()
            .contentType(ContentType.JSON)
            .body(paste("Pasted image 1", PromptAttachmentFixtures.ONE_PIXEL_PNG))
            .post(base(mine))
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // A row id from another workspace is not found HERE — which says nothing about whether it
    // exists elsewhere, and is what keeps one workspace from reaching into another's draft.
    given().delete(base(theirs) + "/" + attachmentId).then().statusCode(404);
    given().get(base(mine)).then().statusCode(200).body("attachments", hasSize(1));

    given().delete(base(mine) + "/" + attachmentId).then().statusCode(204);
    given().get(base(mine)).then().statusCode(200).body("attachments", hasSize(0));

    // Gone means gone; a repeat is a 404 rather than a silent success, because the caller named a
    // row rather than a state.
    given().delete(base(mine) + "/" + attachmentId).then().statusCode(404);
  }

  @Test
  public void anUnknownWorkspaceIs404OnEveryVerb() throws Exception {
    assertEquals(
        404,
        given()
            .get(base(999_999L))
            .thenReturn()
            .statusCode());
    given()
        .contentType(ContentType.JSON)
        .body(paste("Pasted image 1", PromptAttachmentFixtures.ONE_PIXEL_PNG))
        .post(base(999_999L))
        .then()
        .statusCode(404);
    given().delete(base(999_999L) + "/whatever").then().statusCode(404);
  }
}
