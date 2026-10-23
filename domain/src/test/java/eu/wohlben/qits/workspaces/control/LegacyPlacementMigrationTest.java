package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
 * {@code V30__workspace_leases.sql}'s first statement (epic qits-1481), run as the file spells it:
 * every row a runner held is copied into {@code legacy_placement}, with the engine id and host name
 * read from the runner's capabilities where it said them, and its name.
 *
 * <p>The suite's database has run V30 already, so {@code workspace_runner} and {@code
 * workspace.runner_id} are gone: the test puts both back inside a transaction of its own, writes the
 * rows, runs the statement read out of the migration file, asserts, and ROLLS BACK — postgres DDL is
 * transactional, so nothing it did survives ({@link DirectOnlyMigrationTest}'s arrangement).
 */
@QuarkusTest
public class LegacyPlacementMigrationTest {

  private static final String MIGRATION = "db/workspaces/migration/V30__workspace_leases.sql";

  @Inject WorkspaceRepository workspaceRepository;

  private static String copyStatement() throws Exception {
    String file;
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
      assertNotNull(in, MIGRATION + " is on the classpath");
      file = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int start = file.indexOf("insert into legacy_placement");
    int end = file.indexOf(";", start);
    assertTrue(start >= 0 && end > start, "the copy is in " + MIGRATION);
    return file.substring(start, end);
  }

  @Test
  public void everyRowARunnerHeldIsCopiedWithWhatItsRunnerSaid() throws Exception {
    String statement = copyStatement();
    assertThrows(
        RolledBack.class,
        () ->
            QuarkusTransaction.requiringNew()
                .run(
                    () -> {
                      workspaceRepository
                          .getEntityManager()
                          .unwrap(Session.class)
                          .doWork(connection -> runAndAssert(connection, statement));
                      throw new RolledBack();
                    }));
  }

  private static void runAndAssert(Connection connection, String statement) throws SQLException {
    UUID reporting = UUID.randomUUID();
    UUID silent = UUID.randomUUID();
    try (Statement ddl = connection.createStatement()) {
      ddl.execute(
          "create table workspace_runner (id uuid primary key, name varchar(64) not null,"
              + " capabilities jsonb)");
      ddl.execute("alter table workspace add column runner_id uuid");
      ddl.execute("delete from legacy_placement");
    }
    try (PreparedStatement runner =
        connection.prepareStatement(
            "insert into workspace_runner (id, name, capabilities) values (?, ?, ?::jsonb)")) {
      runner.setObject(1, reporting);
      runner.setString(2, "lab");
      runner.setString(3, "{\"engineId\":\"ENGINE-1\",\"hostname\":\"lab-box\",\"version\":\"1\"}");
      runner.executeUpdate();
      runner.setObject(1, silent);
      runner.setString(2, "ws-proof");
      // What qits-workspaces-runner-daemon actually reported: no engine id, no host name.
      runner.setString(3, "{\"version\":\"1\",\"docker\":true,\"arch\":\"amd64\",\"os\":\"linux\"}");
      runner.executeUpdate();
    }
    long onReporting = insert(connection, reporting);
    long onSilent = insert(connection, silent);
    long onNone = insert(connection, null);

    try (Statement run = connection.createStatement()) {
      run.executeUpdate(statement);
    }

    try (PreparedStatement read =
        connection.prepareStatement(
            "select old_runner_id, engine_id, hostname, runner_name from legacy_placement"
                + " where row_id = ?")) {
      read.setLong(1, onReporting);
      try (ResultSet row = read.executeQuery()) {
        assertTrue(row.next());
        assertEquals(reporting, row.getObject("old_runner_id"));
        assertEquals("ENGINE-1", row.getString("engine_id"));
        assertEquals("lab-box", row.getString("hostname"));
        assertEquals("lab", row.getString("runner_name"));
      }
      read.setLong(1, onSilent);
      try (ResultSet row = read.executeQuery()) {
        assertTrue(row.next());
        assertNull(row.getString("engine_id"));
        assertNull(row.getString("hostname"));
        assertEquals("ws-proof", row.getString("runner_name"));
      }
      read.setLong(1, onNone);
      try (ResultSet row = read.executeQuery()) {
        assertFalse(row.next(), "a row no runner held is not copied");
      }
    }
  }

  private static long insert(Connection connection, UUID runnerId) throws SQLException {
    String label = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
    try (PreparedStatement insert =
        connection.prepareStatement(
            "insert into workspace (id, workspace_id, repository_id, branch, status,"
                + " runtime_status, placement, admin, editor, created_at, runner_id)"
                + " values (nextval('workspace_seq'), ?, ?, ?, 'ACTIVE', 'STOPPED', 'TARGET',"
                + " false, false, now(), ?) returning id")) {
      insert.setString(1, label);
      insert.setString(2, "repo-" + label);
      insert.setString(3, label);
      insert.setObject(4, runnerId);
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    }
  }

  private static final class RolledBack extends RuntimeException {
    RolledBack() {
      super("rolled back on purpose");
    }
  }
}
