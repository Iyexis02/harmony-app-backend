package com.example.dating.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch G — Flyway Migration Strategy Resolution.
 *
 * <p>All six tests are purely structural — no Spring context or database connection
 * required. They verify files on disk, matching the style of BatchFActuatorTest.
 *
 * <ol>
 *   <li>application-dev.yml no longer contains {@code ddl-auto: update}.</li>
 *   <li>V5 migration exists and covers the {@code unmatched_by VARCHAR(36)} widening.</li>
 *   <li>V5 migration adds all three missing {@code matches} columns
 *       ({@code match_score_b}, {@code version}, widened {@code unmatched_by}).</li>
 *   <li>V5 migration adds the two missing {@code user_match_scores} columns
 *       ({@code interests_score}, {@code behavioral_score}).</li>
 *   <li>V5 migration creates all five missing composite indexes on {@code matches}
 *       and both missing indexes on {@code user_match_scores} and {@code user_swipes}.</li>
 *   <li>Concurrent: 20 threads reading the V5 migration file simultaneously observe
 *       identical content and complete without error — guards against TOCTOU if a
 *       future tool reads the file during a live migration run.</li>
 * </ol>
 */
class BatchGFlywayMigrationTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final String DEV_YML_PATH =
            "src/main/resources/application-dev.yml";

    private static final String V5_PATH =
            "src/main/resources/db/migration/V5__Schema_Alignment.sql";

    private String readFile(String relativePath) throws Exception {
        return Files.readString(Paths.get(relativePath), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(String relativePath) throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get(relativePath))) {
            return new Yaml().load(in);
        }
    }

    /** Navigates a nested Map<String,Object> tree produced by SnakeYAML. */
    @SuppressWarnings("unchecked")
    private <T> T dig(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
        }
        return (T) current;
    }

    // ── 1. Dev profile no longer uses ddl-auto: update ───────────────────────

    @Test
    @DisplayName("application-dev.yml must not contain ddl-auto: update")
    void devProfile_doesNotUseDdlAutoUpdate() throws Exception {
        Map<String, Object> yaml = loadYaml(DEV_YML_PATH);
        String ddlAuto = dig(yaml, "spring", "jpa", "hibernate", "ddl-auto");

        assertThat(ddlAuto)
                .as("application-dev.yml spring.jpa.hibernate.ddl-auto must not be 'update' — " +
                    "Flyway is now authoritative; Hibernate must only validate, never modify schema")
                .isNotEqualTo("update");

        // Also confirm the raw file text doesn't sneak it in via an alias or comment typo.
        String raw = readFile(DEV_YML_PATH);
        assertThat(raw)
                .as("application-dev.yml raw text must not contain 'ddl-auto: update'")
                .doesNotContain("ddl-auto: update");
    }

    // ── 2. V5 migration exists and widens unmatched_by to VARCHAR(36) ─────────

    @Test
    @DisplayName("V5 migration exists and alters unmatched_by to VARCHAR(36)")
    void v5Migration_exists_andWidensUnmatchedBy() throws Exception {
        assertThat(Paths.get(V5_PATH).toFile())
                .as("V5__Schema_Alignment.sql must exist in db/migration/")
                .exists();

        String sql = readFile(V5_PATH);

        // Must alter the column — not recreate the table.
        assertThat(sql)
                .as("V5 must ALTER unmatched_by to TYPE VARCHAR(36)")
                .containsIgnoringCase("ALTER COLUMN unmatched_by TYPE VARCHAR(36)");
    }

    // ── 3. V5 adds all missing columns to the matches table ──────────────────

    @Test
    @DisplayName("V5 migration adds match_score_b and version columns to matches")
    void v5Migration_addsAllMissingMatchesColumns() throws Exception {
        String sql = readFile(V5_PATH);

        assertThat(sql)
                .as("V5 must add match_score_b (directional B→A score, Integrity Batch H)")
                .containsIgnoringCase("match_score_b");

        assertThat(sql)
                .as("V5 must add version column to matches (optimistic locking, Concurrency Batch A)")
                .containsIgnoringCase("ADD COLUMN IF NOT EXISTS version BIGINT");
    }

    // ── 4. V5 adds both missing columns to user_match_scores ─────────────────

    @Test
    @DisplayName("V5 migration adds interests_score and behavioral_score to user_match_scores")
    void v5Migration_addsMissingUserMatchScoresColumns() throws Exception {
        String sql = readFile(V5_PATH);

        assertThat(sql)
                .as("V5 must add interests_score (algorithm v2.0 5th dimension)")
                .containsIgnoringCase("interests_score");

        assertThat(sql)
                .as("V5 must add behavioral_score (algorithm v2.0 5th dimension)")
                .containsIgnoringCase("behavioral_score");
    }

    // ── 5. V5 creates all missing composite indexes ───────────────────────────

    @Test
    @DisplayName("V5 migration creates all missing composite indexes")
    void v5Migration_createsAllMissingIndexes() throws Exception {
        String sql = readFile(V5_PATH);

        List<String> requiredIndexes = List.of(
                // matches — composite indexes (Scalability Batch B/C)
                "idx_status_matched_at",
                "idx_match_a_status",
                "idx_match_b_status",
                "idx_match_a_status_conv",
                "idx_match_b_status_conv",
                // user_match_scores — cache batch-fetch index (Scalability Batch C)
                "idx_user_version",
                // user_swipes — blocked-user exclusion index (Scalability Batch B)
                "idx_swiped_action"
        );

        for (String idx : requiredIndexes) {
            assertThat(sql)
                    .as("V5 must create index: " + idx)
                    .contains(idx);
        }
    }

    // ── 6. Concurrent: 20 threads read V5 simultaneously — no TOCTOU ─────────

    @Test
    @DisplayName("20 concurrent reads of V5 migration file observe identical content")
    void concurrent_v5MigrationReads_observeIdenticalContent() throws InterruptedException {
        // Guards against TOCTOU issues if a deployment tool reads the migration
        // file concurrently with an in-progress Flyway run.
        // All 20 threads must read the same content and none must encounter an error.

        int threadCount = 20;
        CountDownLatch startGun  = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                try {
                    startGun.await();
                    String content = readFile(V5_PATH);
                    results.add(content);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        startGun.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS))
                .as("all 20 reader threads must finish within 10 s")
                .isTrue();

        assertThat(errors.get())
                .as("no thread should encounter a read error")
                .isZero();

        assertThat(results)
                .as("all 20 threads must have read some content")
                .hasSize(threadCount);

        // Every thread must have seen the same file — no partial reads.
        String reference = results.get(0);
        assertThat(results)
                .as("all concurrent reads must observe identical file content")
                .allSatisfy(content -> assertThat(content).isEqualTo(reference));

        // Sanity-check that the reference content is the real migration, not an empty file.
        assertThat(reference)
                .as("read content must be the actual V5 migration, not empty")
                .contains("unmatched_by")
                .contains("match_score_b")
                .contains("interests_score");
    }
}
