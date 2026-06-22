package com.example.dating.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Batch H — propagates the SLF4J MDC context from the request thread to threads
 * in the {@code @Async} executor pool.
 *
 * <p>Without this decorator, every {@code @Async} method (e.g. {@code MatchLifecycleListener}
 * event handlers, {@code BehavioralProfileService.updateAfterSwipe}) inherits whatever MDC
 * state happened to be left by the previous task on that thread — never the MDC of the
 * originating request. Correlation IDs would be absent or wrong in async log lines.
 *
 * <p>Implementation contract:
 * <ol>
 *   <li>{@link #decorate} is called on the submitting (request) thread. It snapshots the
 *       current MDC map at that moment.</li>
 *   <li>The returned {@link Runnable} is later executed on an async thread. It restores the
 *       snapshot, runs the task, then clears the MDC to prevent leakage into the next task
 *       executed by the same thread.</li>
 * </ol>
 *
 * <p>Registered in {@link AsyncConfig#taskExecutor()} via
 * {@code executor.setTaskDecorator(new MdcTaskDecorator())}.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Snapshot MDC on the submitting (HTTP request) thread.
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                // Always clear so the next task on this thread starts with a clean MDC.
                MDC.clear();
            }
        };
    }
}
