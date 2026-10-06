package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

/**
 * {@code V15__direct_only_admin_editor.sql}'s first statement (qits-780), run as the file spells it:
 * the leftover main workspaces are abandoned, whatever their placement, with a history entry, and
 * nothing else moves.
 *
 * <p>The suite's database has run V15 already, and its check now refuses the one row the statement
 * exists for — an ACTIVE regular DIRECT row — so the test drops the check inside a transaction of
 * its own, writes the rows, runs the statement read straight out of the migration file, asserts, and
 * ROLLS BACK: postgres DDL is transactional, so the constraint is back the moment the test ends and
 * nothing it wrote survives. The check itself is {@code WorkspaceRunnersTest}'s.
 */
@QuarkusTest
public class DirectOnlyMigrationTest {

  private static final String MIGRATION = "db/workspaces/migration/V15__direct_only_admin_editor.sql";

  @Inject WorkspaceRepository workspaceRepository;

  /** The migration's first statement: from its {@code with resolved as (} to its {@code ;}. */
  private static String resolutionStatement() throws IOException {
    String file;
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
      assertNotNull(in, MIGRATION + " is on the classpath");
      file = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int start = file.indexOf("with resolved as (");
    int end = file.indexOf("from resolved;", start) + "from resolved".length();
    assertTrue(start >= 0 && end > start, "the resolution statement is in " + MIGRATION);
    return file.substring(start, end);
  }

  @Test
  public void theLeftoverMainWorkspacesAreAbandonedAndNothingElseMoves() throws Exception {
    String statement = resolutionStatement();
    String tag = UUID.randomUUID().toString().substring(0, 8);

    // The transaction ends by throwing, so everything in it — the dropped check included — is
    // undone; an assertion that fails inside it fails the test instead.
    assertThrows(
        RolledBack.class,
        () ->
            QuarkusTransaction.requiringNew()
                .run(
                    () -> {
                      workspaceRepository
                          .getEntityManager()
                          .unwrap(Session.class)
                          .doWork(connection -> runAndAssert(connection, statement, tag));
                      throw new RolledBack();
                    }));
  }

  private static void runAndAssert(Connection connection, String statement, String tag)
      throws SQLException {
    try (Statement ddl = connection.createStatement()) {
      ddl.execute("alter table workspace drop constraint ck_workspace_direct_only_admin_editor");
    }
    long directMain = insert(connection, tag + "-dm", "DIRECT", false, null);
    long runnerMain = insert(connection, tag + "-rm", "RUNNER", false, "");
    long runnerWork = insert(connection, tag + "-rw", "RUNNER", false, "main");
    long directWork = insert(connection, tag + "-dw", "DIRECT", false, "main");
    long adminMain = insert(connection, tag + "-am", "DIRECT", true, null);

    try (Statement run = connection.createStatement()) {
      run.executeUpdate(statement);
    }

    // A main workspace is retired as such, wherever it was last placed — the live one had been
    // moved to RUNNER by the qits-776 sweep.
    assertAbandoned(connection, directMain);
    assertAbandoned(connection, runnerMain);
    // A regular row WITH a parent is real work and is never touched; the check below the statement
    // is what refuses the DIRECT one of those.
    assertActive(connection, runnerWork);
    assertActive(connection, directWork);
    // Admin and editor rows are the direct path's own.
    assertActive(connection, adminMain);
  }

  /** How the test's transaction ends: by throwing, so nothing it did is committed. */
  private static final class RolledBack extends RuntimeException {
    RolledBack() {
      super("rolled back on purpose");
    }
  }

  private static long insert(
      Connection connection, String label, String placement, boolean admin, String parent)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "insert into workspace (id, workspace_id, repository_id, parent_id, branch, status,"
                + " runtime_status, placement, admin, editor, created_at)"
                + " values (nextval('workspace_seq'), ?, ?, ?, 'main', 'ACTIVE', 'STOPPED', ?, ?,"
                + " false, now()) returning id")) {
      insert.setString(1, label);
      insert.setString(2, "repo-" + label);
      insert.setString(3, parent);
      insert.setString(4, placement);
      insert.setBoolean(5, admin);
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    }
  }

  private static void assertAbandoned(Connection connection, long id) throws SQLException {
    try (PreparedStatement read =
        connection.prepareStatement(
            "select status, resolved_at, result, runtime_status from workspace where id = ?")) {
      read.setLong(1, id);
      try (ResultSet row = read.executeQuery()) {
        assertTrue(row.next());
        assertEquals("ABANDONED", row.getString("status"), "row " + id);
        assertNotNull(row.getTimestamp("resolved_at"), "row " + id + " is resolved now");
        assertTrue(row.getString("result").contains("V15"), row.getString("result"));
        assertEquals("STOPPED", row.getString("runtime_status"));
      }
    }
    try (PreparedStatement events =
        connection.prepareStatement(
            "select type, branch, note from workspace_event where workspace_id_fk = ?")) {
      events.setLong(1, id);
      try (ResultSet event = events.executeQuery()) {
        assertTrue(event.next(), "row " + id + " has its ABANDONED history entry");
        assertEquals("ABANDONED", event.getString("type"));
        assertEquals("main", event.getString("branch"));
        assertTrue(event.getString("note").contains("the branch is kept"));
        assertTrue(!event.next(), "exactly one entry");
      }
    }
  }

  private static void assertActive(Connection connection, long id) throws SQLException {
    try (PreparedStatement read =
        connection.prepareStatement("select status, resolved_at from workspace where id = ?")) {
      read.setLong(1, id);
      try (ResultSet row = read.executeQuery()) {
        assertTrue(row.next());
        assertEquals("ACTIVE", row.getString("status"), "row " + id + " is untouched");
        assertNull(row.getTimestamp("resolved_at"));
      }
    }
  }
}
