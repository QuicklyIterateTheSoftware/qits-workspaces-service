package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * This repository declares its own configuration keys, and this test holds what a hand edit can
 * break without anybody noticing.
 *
 * <p><b>Why the file exists.</b> qits-configuration marks a stored entry {@code orphaned} only when
 * the application has a declaration that does not list the key. The file lists every key this
 * service still reads, so the ones it does not list — the old named clients' keys — show as
 * orphaned and a person can remove them. A key missing from the file is therefore a live key the
 * UI invites somebody to delete.
 *
 * <p><b>What this test is NOT.</b> It is not a parser. qits-configuration owns the grammar and is
 * the one strict parser of this document; a second opinion here would disagree with it the day the
 * grammar grows. The document was checked against the real {@code DeclarationParser} by running it
 * when it was written. What stays here is the file being at the path the deployer fetches, the one
 * top-level key, no key twice, and every raw env name this service's own config interpolates being
 * declared — the last one guards the {@code qits} client's fallback keys, which nothing else in
 * this repository names.
 */
class OwnDeclarationTest {

  /** The exact path the deployer fetches at the released tag. Not a pattern. */
  private static final String DECLARATION_PATH = ".config/qits/configuration.yml";

  /** The config files whose {@code ${ENV_NAME…}} references this service reads at runtime. */
  private static final List<String> CONFIG_FILES =
      List.of(
          "service/src/main/resources/application.properties",
          "domain/src/main/resources/META-INF/microprofile-config.properties");

  /** A raw env name inside {@code ${…}}: upper case, then {@code :} (a fallback) or {@code }}. */
  private static final Pattern RAW_ENV_REFERENCE = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)[:}]");

  /** A declared key line: {@code env.<VAR>:} or one of the indexed families, as the store spells it. */
  private static final Pattern DECLARED_KEY =
      Pattern.compile("^(env\\.[A-Za-z_][A-Za-z0-9_]*|(mounts|publishes|groups|aliases)\\[[0-9]{1,4}]):$");

  /** The repository root, found by walking up from wherever surefire started this module. */
  private static Path root() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      if (Files.isRegularFile(at.resolve(DECLARATION_PATH))) {
        return at;
      }
    }
    throw new AssertionError("no " + DECLARATION_PATH + " above " + Path.of("").toAbsolutePath());
  }

  private static List<String> declaredKeys() throws IOException {
    List<String> keys = new ArrayList<>();
    for (String line : Files.readAllLines(root().resolve(DECLARATION_PATH))) {
      Matcher key = DECLARED_KEY.matcher(line.strip());
      if (key.matches()) {
        keys.add(key.group(1));
      }
    }
    return keys;
  }

  @Test
  void thisRepositoryCarriesItsDeclarationWhereTheDeployerLooksForIt() throws IOException {
    String raw = Files.readString(root().resolve(DECLARATION_PATH));

    assertFalse(raw.isBlank(), "the store refuses an empty document");
  }

  @Test
  void theTopLevelIsTheOneKeyTheStoreAccepts() throws IOException {
    // The store refuses a second top-level key rather than ignoring it. A stray unindented line is
    // the easiest way to break this file by hand, so it is the one shape worth pinning.
    List<String> topLevel = new ArrayList<>();
    for (String line : Files.readAllLines(root().resolve(DECLARATION_PATH))) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith(" ")) {
        continue;
      }
      topLevel.add(line);
    }

    assertEquals(List.of("keys:"), topLevel);
  }

  @Test
  void noKeyIsDeclaredTwice() throws IOException {
    // The store refuses duplicate keys outright, so a repeated name is a refused release rather than
    // one redundant line.
    Set<String> seen = new LinkedHashSet<>();
    for (String key : declaredKeys()) {
      assertTrue(seen.add(key), "declared twice: " + key);
    }

    assertFalse(seen.isEmpty(), "the document declares no keys at all");
  }

  @Test
  void everyRawEnvNameThisServiceInterpolatesIsDeclared() throws IOException {
    // `${QUARKUS_OIDC_CLIENT_CLIENT_ID:…}` and its siblings are the `qits` client's fallback, read by
    // raw env name and named nowhere in the code. Left out of the declaration, they would show as
    // orphaned while the service still reads them. QITS_RESOURCE_* is the deployer's to inject and
    // configuration's never to state, so it is the one family left out on purpose.
    Set<String> declared = new TreeSet<>(declaredKeys());
    Set<String> missing = new TreeSet<>();
    for (String file : CONFIG_FILES) {
      for (String line : Files.readAllLines(root().resolve(file))) {
        if (line.strip().startsWith("#")) {
          continue;
        }
        Matcher reference = RAW_ENV_REFERENCE.matcher(line);
        while (reference.find()) {
          String name = reference.group(1);
          if (!name.startsWith("QITS_RESOURCE_") && !declared.contains("env." + name)) {
            missing.add(name);
          }
        }
      }
    }

    assertTrue(missing.isEmpty(), "read by raw env name but not declared: " + missing);
  }
}
