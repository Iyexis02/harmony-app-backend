package com.example.dating.matching;

import com.example.dating.config.MdcTaskDecorator;
import com.example.dating.security.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch H — Structured Logging with Correlation IDs
 *
 * <p>Tests cover:
 * <ol>
 *   <li>Filter generates a UUID correlation ID when the request has none</li>
 *   <li>Filter honours a caller-supplied {@code X-Correlation-Id} header</li>
 *   <li>MDC key is set during the request and cleared after it completes</li>
 *   <li>Response carries {@code X-Correlation-Id} header in both cases</li>
 *   <li>MDC propagates across the async thread boundary via {@link MdcTaskDecorator}</li>
 *   <li>20 concurrent requests: each thread sees its own correlation ID — no cross-contamination</li>
 * </ol>
 */
class BatchHStructuredLoggingTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        // Guard: ensure MDC is clean between tests regardless of test outcome.
        MDC.clear();
    }

    // ── Test 1: filter generates ID when header is absent ──────────────────

    @Test
    void filter_generateCorrelationId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] capturedMdc = new String[1];

        filter.doFilter(request, response, (req, res) ->
                capturedMdc[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        // A valid UUID was generated and placed in MDC during the request.
        assertThat(capturedMdc[0]).isNotNull();
        // Verify it is a parseable UUID (not an arbitrary string).
        assertThat(UUID.fromString(capturedMdc[0])).isNotNull();

        // The same ID was echoed in the response header.
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(capturedMdc[0]);
    }

    // ── Test 2: filter honours a caller-supplied header ──────────────────

    @Test
    void filter_usesSuppliedException_whenHeaderPresent() throws Exception {
        String suppliedId = "frontend-trace-abc123";
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, suppliedId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] capturedMdc = new String[1];

        filter.doFilter(request, response, (req, res) ->
                capturedMdc[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertThat(capturedMdc[0]).isEqualTo(suppliedId);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(suppliedId);
    }

    // ── Test 3: MDC is cleared after the filter chain completes ──────────

    @Test
    void filter_clearsMdc_afterRequestCompletes() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            // MDC is set during the chain.
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotNull();
        });

        // MDC must be null after doFilter returns — not leaked to the next request.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    // ── Test 4: MDC is cleared even when filter chain throws ─────────────

    @Test
    void filter_clearsMdc_evenWhenChainThrows() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new RuntimeException("simulated downstream failure");
            });
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    // ── Test 5: MdcTaskDecorator propagates correlationId to async thread ─

    @Test
    void mdcTaskDecorator_propagatesCorrelationIdAcrossThreadBoundary() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        String expectedId = "test-correlation-" + UUID.randomUUID();

        // Set MDC on the "request" thread (simulating what CorrelationIdFilter does).
        MDC.put(CorrelationIdFilter.MDC_KEY, expectedId);

        // Decorate: snapshot is captured here, on the request thread.
        CompletableFuture<String> asyncResult = new CompletableFuture<>();
        Runnable decorated = decorator.decorate(() ->
                asyncResult.complete(MDC.get(CorrelationIdFilter.MDC_KEY)));

        // Clear MDC on this thread to simulate thread-pool reuse (pool thread had prior state).
        MDC.clear();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        // Run the decorated task (simulating async executor scheduling it).
        decorated.run();

        String captured = asyncResult.get(1, TimeUnit.SECONDS);
        assertThat(captured).isEqualTo(expectedId);

        // Decorator clears MDC after the task so the next task on this thread starts clean.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    // ── Test 6: 20 concurrent requests — each thread sees its own ID ──────

    /**
     * Concurrent isolation test.
     *
     * <p>20 threads each supply a distinct {@code X-Correlation-Id} header and capture
     * the MDC value seen inside the filter chain. All threads release simultaneously via
     * a {@link CyclicBarrier} to maximise contention. After all threads complete:
     * <ul>
     *   <li>Every thread saw its own (and only its own) correlation ID in the MDC.</li>
     *   <li>No cross-thread contamination occurred.</li>
     *   <li>MDC was cleared on each thread after the filter returned.</li>
     * </ul>
     *
     * <p>This test would fail if {@code MDC.put} / {@code MDC.remove} were operating on
     * a shared map instead of per-thread storage — validating that SLF4J's
     * {@link org.slf4j.MDC} is inherently thread-local.
     */
    @Test
    void filter_isolatesCorrelationIds_acrossConcurrentRequests() throws Exception {
        int threadCount = 20;
        CyclicBarrier barrier   = new CyclicBarrier(threadCount);
        Map<String, String> captured = new ConcurrentHashMap<>(); // threadId → mdcValue
        AtomicInteger failureCount   = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            String expectedId = "concurrent-correlation-" + i;
            Thread t = new Thread(() -> {
                try {
                    barrier.await(); // all threads start at the same moment

                    MockHttpServletRequest  req  = new MockHttpServletRequest();
                    MockHttpServletResponse res  = new MockHttpServletResponse();
                    req.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, expectedId);

                    filter.doFilter(req, res, (filterReq, filterRes) ->
                            captured.put(expectedId, MDC.get(CorrelationIdFilter.MDC_KEY)));

                    // MDC must be clear after the filter returns on this thread.
                    if (MDC.get(CorrelationIdFilter.MDC_KEY) != null) {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
            threads.add(t);
        }

        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join(5_000);
        }

        // No thread encountered an error.
        assertThat(failureCount.get())
                .as("unexpected failures in concurrent threads")
                .isZero();

        // Every expected entry was captured.
        assertThat(captured).hasSize(threadCount);

        // Each thread saw exactly its own correlation ID — no cross-contamination.
        for (int i = 0; i < threadCount; i++) {
            String expectedId = "concurrent-correlation-" + i;
            assertThat(captured.get(expectedId))
                    .as("MDC value seen by thread %d", i)
                    .isEqualTo(expectedId);
        }
    }
}
