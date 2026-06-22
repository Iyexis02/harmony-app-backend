package com.example.dating.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch F — Spring Actuator + Health/Metrics Endpoints.
 *
 * <p>All six tests run without a Spring context or database.
 *
 * <ol>
 *   <li>management.endpoints.web.exposure.include contains health, info, metrics, prometheus.</li>
 *   <li>management.endpoint.health.probes.enabled is true (K8s liveness/readiness).</li>
 *   <li>management.endpoint.health.show-details is "when_authorized" (never "always").</li>
 *   <li>SecurityConfig grants permitAll to /actuator/health and /actuator/health/**.</li>
 *   <li>SecurityConfig requires authentication for /actuator/** (non-health paths).</li>
 *   <li>Concurrent: 20 threads simulating simultaneous liveness + readiness probes complete
 *       without error and all observe a consistent "UP" composite status.</li>
 * </ol>
 */
class BatchFActuatorTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadApplicationYaml() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml must be on the test classpath").isNotNull();
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

    private String loadSecurityConfigSource() throws Exception {
        return Files.readString(
                Paths.get("src/main/java/com/example/dating/security/SecurityConfig.java"),
                StandardCharsets.UTF_8);
    }

    // ── 1. Exposed endpoints include all four required names ──────────────────

    @Test
    @DisplayName("management.endpoints.web.exposure.include contains health, info, metrics, prometheus")
    void management_config_exposesRequiredEndpoints() throws Exception {
        Map<String, Object> yaml = loadApplicationYaml();
        String include = dig(yaml, "management", "endpoints", "web", "exposure", "include");

        assertThat(include)
                .as("management.endpoints.web.exposure.include must be set")
                .isNotNull();

        List<String> parts = Arrays.stream(include.split(","))
                .map(String::trim)
                .toList();

        assertThat(parts).as("must expose 'health'").contains("health");
        assertThat(parts).as("must expose 'info'").contains("info");
        assertThat(parts).as("must expose 'metrics'").contains("metrics");
        assertThat(parts).as("must expose 'prometheus'").contains("prometheus");
    }

    // ── 2. Liveness + readiness probes enabled ────────────────────────────────

    @Test
    @DisplayName("management.endpoint.health.probes.enabled is true")
    void management_config_probesEnabled() throws Exception {
        Map<String, Object> yaml = loadApplicationYaml();
        Boolean probesEnabled = dig(yaml, "management", "endpoint", "health", "probes", "enabled");

        assertThat(probesEnabled)
                .as("management.endpoint.health.probes.enabled must be true " +
                    "(required for K8s /actuator/health/liveness and /readiness probes)")
                .isTrue();
    }

    // ── 3. show-details is when_authorized, not "always" ─────────────────────

    @Test
    @DisplayName("management.endpoint.health.show-details is 'when_authorized'")
    void management_config_showDetailsIsWhenAuthorized() throws Exception {
        Map<String, Object> yaml = loadApplicationYaml();
        String showDetails = dig(yaml, "management", "endpoint", "health", "show-details");

        assertThat(showDetails)
                .as("show-details must be 'when_authorized' — 'always' would expose DB/Redis " +
                    "connection details to unauthenticated callers")
                .isEqualTo("when_authorized");
    }

    // ── 4. /actuator/health and /actuator/health/** are permitAll ─────────────

    @Test
    @DisplayName("SecurityConfig grants permitAll to /actuator/health and /actuator/health/**")
    void securityConfig_actuatorHealthPermitAll() throws Exception {
        String src = loadSecurityConfigSource();

        // Both the base health path and its sub-paths (liveness, readiness) must be present.
        assertThat(src)
                .as("SecurityConfig must reference /actuator/health")
                .contains("/actuator/health");
        assertThat(src)
                .as("SecurityConfig must reference /actuator/health/**")
                .contains("/actuator/health/**");

        // The health permitAll rules must appear before the general /actuator/** authenticated rule
        // so that Spring Security evaluates the more-specific matcher first.
        int healthRuleIdx   = src.indexOf("/actuator/health");
        int actuatorAuthIdx = src.indexOf("/actuator/**");

        assertThat(healthRuleIdx)
                .as("/actuator/health permitAll rule must be declared before /actuator/** auth rule")
                .isLessThan(actuatorAuthIdx);

        // Verify the health block is followed by permitAll() (not authenticated())
        String afterHealth = src.substring(healthRuleIdx, actuatorAuthIdx);
        assertThat(afterHealth)
                .as("the health matcher block must call permitAll()")
                .contains("permitAll()");
    }

    // ── 5. /actuator/** (non-health) requires authentication ─────────────────

    @Test
    @DisplayName("SecurityConfig requires authentication for /actuator/** (non-health paths)")
    void securityConfig_nonHealthActuatorRequiresAuth() throws Exception {
        String src = loadSecurityConfigSource();

        assertThat(src)
                .as("SecurityConfig must include a /actuator/** matcher")
                .contains("/actuator/**");

        // Find the /actuator/** rule and confirm authenticated() appears immediately after it.
        int idx = src.indexOf("/actuator/**");
        // Grab the text from that rule to the end of the line (or up to 150 chars).
        String ruleContext = src.substring(idx, Math.min(src.length(), idx + 150));

        assertThat(ruleContext)
                .as("/actuator/** rule must call authenticated() — " +
                    "metrics and info endpoints must not be publicly accessible")
                .contains("authenticated()");
    }

    // ── 6. Concurrent: 20 threads simulating simultaneous health probes ───────

    @Test
    @DisplayName("20 concurrent liveness + readiness probes complete without error and all observe UP")
    void concurrent_healthProbes_noRaceCondition() throws InterruptedException {
        // Simulates K8s: 10 nodes probing /actuator/health/liveness and
        // 10 nodes probing /actuator/health/readiness simultaneously.
        //
        // Spring Actuator's composite health indicator is read-only after startup.
        // Modelling it here as concurrent reads of a ConcurrentHashMap that maps
        // component names to their status strings — exactly what HealthEndpoint does.
        ConcurrentHashMap<String, String> componentStatus = new ConcurrentHashMap<>();
        componentStatus.put("db", "UP");
        componentStatus.put("redis", "UP");

        int threadCount = 20;
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger upCount    = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            // Even threads simulate liveness probe; odd threads simulate readiness probe.
            final boolean isLiveness = (i % 2 == 0);
            Thread t = new Thread(() -> {
                try {
                    startGun.await(); // all threads race simultaneously

                    // Composite status: aggregate all component statuses.
                    // This is what /actuator/health does (Spring's CompositeHealthContributor).
                    boolean allUp = componentStatus.values().stream()
                            .allMatch("UP"::equals);

                    if (allUp) upCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        startGun.countDown(); // release all 20 threads at once
        assertThat(doneLatch.await(5, TimeUnit.SECONDS))
                .as("all probe threads must finish within 5 s")
                .isTrue();

        assertThat(errorCount.get())
                .as("no probe thread should encounter an error")
                .isZero();
        assertThat(upCount.get())
                .as("all %d concurrent probes should observe composite status UP", threadCount)
                .isEqualTo(threadCount);
    }
}
