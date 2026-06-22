package com.example.dating.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Batch H — assigns a correlation ID to every inbound HTTP request.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Reads {@value CORRELATION_ID_HEADER} from the request. If absent or blank,
 *       a random UUID is generated.</li>
 *   <li>Writes the ID into the SLF4J MDC under the key {@value MDC_KEY} so that
 *       every log statement produced on this thread (and on any {@code @Async} thread
 *       decorated by {@link com.example.dating.config.MdcTaskDecorator}) carries the
 *       same {@code correlationId} field.</li>
 *   <li>Echoes the final ID in the {@value CORRELATION_ID_HEADER} response header so
 *       clients can include it in bug reports.</li>
 *   <li>Clears the MDC key in a {@code finally} block to prevent leakage into
 *       subsequent requests handled by the same Tomcat thread.</li>
 * </ol>
 *
 * <p>Ordering: {@link Ordered#HIGHEST_PRECEDENCE} ensures this filter runs before
 * Spring Security's filter chain, {@link RateLimitFilter}, and
 * {@link EmailVerificationFilter}, so all downstream log statements—including
 * security rejections—carry the correlation ID.
 *
 * <p>Spring Boot auto-registers this as a top-level servlet filter (correct — it must
 * run outside, not inside, the Spring Security chain). No
 * {@code FilterRegistrationBean} override is needed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** HTTP header name used to propagate the correlation ID. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** MDC key under which the correlation ID is stored for logging. */
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
