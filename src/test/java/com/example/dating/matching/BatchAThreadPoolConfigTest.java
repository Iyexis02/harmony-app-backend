package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch A — Connection Pool, Thread Pool, and Async Executor Configuration
 *
 * Verifies:
 * 1. HikariCP maximum-pool-size is 30 (not the default 10).
 * 2. The async executor is a bounded ThreadPoolTaskExecutor (not SimpleAsyncTaskExecutor).
 * 3. Async tasks run on threads named with the "async-" prefix.
 * 4. 30 concurrent tasks complete without thread starvation or rejection.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchAThreadPoolConfigTest {

    @Autowired
    private HikariDataSource hikariDataSource;

    @Autowired
    private ThreadPoolTaskExecutor taskExecutor;

    // -------------------------------------------------------------------------
    // 1. HikariCP pool size
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HikariCP maximum-pool-size is 30 (not the default 10)")
    void hikariMaxPoolSizeIs30() {
        int maxPoolSize = hikariDataSource.getMaximumPoolSize();
        System.out.println("HikariCP maximum-pool-size: " + maxPoolSize);
        assertEquals(30, maxPoolSize,
            "Expected maximum-pool-size=30 from application.yml; got " + maxPoolSize +
            ". Default is 10 — if this fails, the hikari config block is missing or not applied.");
    }

    @Test
    @DisplayName("HikariCP minimum-idle is 10")
    void hikariMinIdleIs10() {
        int minIdle = hikariDataSource.getMinimumIdle();
        System.out.println("HikariCP minimum-idle: " + minIdle);
        assertEquals(10, minIdle);
    }

    @Test
    @DisplayName("HikariCP connection-timeout is 5000 ms")
    void hikariConnectionTimeoutIs5000() {
        long timeout = hikariDataSource.getConnectionTimeout();
        System.out.println("HikariCP connection-timeout: " + timeout + " ms");
        assertEquals(5000L, timeout);
    }

    @Test
    @DisplayName("HikariCP leak-detection-threshold is 30000 ms")
    void hikariLeakDetectionThresholdIs30000() {
        long threshold = hikariDataSource.getLeakDetectionThreshold();
        System.out.println("HikariCP leak-detection-threshold: " + threshold + " ms");
        assertEquals(30000L, threshold);
    }

    // -------------------------------------------------------------------------
    // 2. Async executor configuration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Async executor is a bounded ThreadPoolTaskExecutor (not SimpleAsyncTaskExecutor)")
    void asyncExecutorIsBoundedThreadPool() {
        System.out.println("Async executor type: " + taskExecutor.getClass().getSimpleName());
        assertNotNull(taskExecutor, "taskExecutor bean must be present");
        // If AsyncConfig did not override getAsyncExecutor(), Spring would use
        // SimpleAsyncTaskExecutor which is NOT a ThreadPoolTaskExecutor.
        assertInstanceOf(ThreadPoolTaskExecutor.class, taskExecutor);
    }

    @Test
    @DisplayName("Async executor core pool size is 10")
    void asyncExecutorCorePoolSizeIs10() {
        int core = taskExecutor.getCorePoolSize();
        System.out.println("Async executor corePoolSize: " + core);
        assertEquals(10, core);
    }

    @Test
    @DisplayName("Async executor max pool size is 50")
    void asyncExecutorMaxPoolSizeIs50() {
        int max = taskExecutor.getMaxPoolSize();
        System.out.println("Async executor maxPoolSize: " + max);
        assertEquals(50, max);
    }

    @Test
    @DisplayName("Async executor queue capacity is 200")
    void asyncExecutorQueueCapacityIs200() {
        int queueCapacity = taskExecutor.getQueueCapacity();
        System.out.println("Async executor queueCapacity: " + queueCapacity);
        assertEquals(200, queueCapacity);
    }

    // -------------------------------------------------------------------------
    // 3. Thread name prefix
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Async tasks run on threads named with the 'async-' prefix")
    void asyncTasksUseAsyncThreadNamePrefix() throws InterruptedException {
        List<String> observedThreadNames = Collections.synchronizedList(new ArrayList<>());
        int taskCount = 20;
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            taskExecutor.execute(() -> {
                try {
                    observedThreadNames.add(Thread.currentThread().getName());
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        assertTrue(finished, "All 20 async tasks should complete within 10 seconds");
        assertEquals(taskCount, observedThreadNames.size());

        System.out.println("Observed thread names (sample): " + observedThreadNames.subList(0, Math.min(5, observedThreadNames.size())));

        long wrongThreadCount = observedThreadNames.stream()
            .filter(name -> !name.startsWith("async-"))
            .count();

        assertEquals(0, wrongThreadCount,
            "All tasks must run on 'async-' prefixed threads. Found wrong names: " +
            observedThreadNames.stream().filter(n -> !n.startsWith("async-")).toList());
    }

    // -------------------------------------------------------------------------
    // 4. Concurrent load: 30 tasks must complete without rejection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("30 concurrent tasks complete without rejection (CallerRunsPolicy backpressure)")
    void thirtyconcurrent_tasksComplete() throws InterruptedException {
        int taskCount = 30;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(taskCount);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    // Simulate brief work
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    errors.add("Task " + taskId + " interrupted: " + e.getMessage());
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, taskExecutor));
        }

        startGate.countDown(); // release all tasks simultaneously

        boolean allDone = doneLatch.await(15, TimeUnit.SECONDS);
        assertTrue(errors.isEmpty(), "No tasks should error: " + errors);
        assertTrue(allDone, "All 30 concurrent tasks must complete within 15 seconds. " +
            "If this fails, the executor is too small or tasks are being dropped.");

        System.out.println("All " + taskCount + " concurrent tasks completed successfully.");
    }
}
