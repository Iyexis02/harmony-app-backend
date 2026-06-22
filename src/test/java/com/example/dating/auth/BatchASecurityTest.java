package com.example.dating.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.user.domain.User;
import com.example.dating.services.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch A — Secret Logging Removal + JWT Claim Reduction
 *
 * <p>Verifies three things:
 * <ol>
 *   <li>No INFO-level log line contains the full claim dump (claims.toString) on token decode.</li>
 *   <li>Newly issued JWTs contain only {@code userId}, {@code tokenVersion}, {@code sub},
 *       {@code iat}, and {@code exp} — no PII claims ({@code email}, {@code authProvider},
 *       {@code emailVerified}).</li>
 *   <li>{@code getUserIdFromToken()} still resolves the correct userId from the cleaned token.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchASecurityTest {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtDecoder jwtDecoder;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger jwtLogger;

    // ─── Minimal test user ────────────────────────────────────────────────────

    private static User testUser() {
        return User.builder()
                .id(UUID.randomUUID().toString())
                .email("alice@example.com")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .tokenVersion(3)
                .registrationStage(RegistrationStage.FINISHED)
                .build();
    }

    // ─── Log capturing setup ──────────────────────────────────────────────────

    @BeforeEach
    void attachLogAppender() {
        jwtLogger = (Logger) LoggerFactory.getLogger(
                "com.example.dating.services.impl.JwtServiceImpl");
        listAppender = new ListAppender<>();
        listAppender.start();
        jwtLogger.addAppender(listAppender);
        // Ensure DEBUG messages are also captured so we can assert they are absent at INFO
        jwtLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogAppender() {
        jwtLogger.detachAppender(listAppender);
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JWT does not contain PII claims (email, authProvider, emailVerified)")
    void jwt_doesNotContainPiiClaims() {
        User user = testUser();
        String token = jwtService.generateToken(user);

        org.springframework.security.oauth2.jwt.Jwt decoded = jwtDecoder.decode(token);
        Map<String, Object> claims = decoded.getClaims();

        assertThat(claims).doesNotContainKey("email");
        assertThat(claims).doesNotContainKey("authProvider");
        assertThat(claims).doesNotContainKey("emailVerified");
    }

    @Test
    @DisplayName("JWT retains required non-PII claims (userId, tokenVersion, sub)")
    void jwt_retainsRequiredClaims() {
        User user = testUser();
        String token = jwtService.generateToken(user);

        org.springframework.security.oauth2.jwt.Jwt decoded = jwtDecoder.decode(token);
        Map<String, Object> claims = decoded.getClaims();

        assertThat(claims).containsKey("userId");
        assertThat(claims.get("userId")).isEqualTo(user.getId());

        assertThat(claims).containsKey("tokenVersion");
        assertThat(((Number) claims.get("tokenVersion")).intValue()).isEqualTo(3);

        // 'sub' is the OIDC subject — Spring maps it separately
        assertThat(decoded.getSubject()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("getUserIdFromToken() resolves correct userId from cleaned token")
    void getUserIdFromToken_resolvesCorrectly() {
        User user = testUser();
        String token = jwtService.generateToken(user);

        String resolvedId = jwtService.getUserIdFromToken(token);

        assertThat(resolvedId).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("No INFO log line contains the full claims map on token decode")
    void noInfoLog_containsFullClaimsMap() {
        User user = testUser();
        String token = jwtService.generateToken(user);

        // Trigger getUserIdFromToken — this was the call site of the removed log.info(claims.toString())
        jwtService.getUserIdFromToken(token);

        List<ILoggingEvent> infoEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();

        // No INFO line should contain the email or a raw claim map dump
        for (ILoggingEvent event : infoEvents) {
            String msg = event.getFormattedMessage();
            assertThat(msg)
                    .as("INFO log must not contain user email: " + msg)
                    .doesNotContain(user.getEmail());
            assertThat(msg)
                    .as("INFO log must not contain authProvider string: " + msg)
                    .doesNotContain("authProvider");
            assertThat(msg)
                    .as("INFO log must not contain emailVerified: " + msg)
                    .doesNotContain("emailVerified");
        }
    }

    @Test
    @DisplayName("No log line at any level leaks the raw claims map (email present in map)")
    void noLog_leaksRawClaimsMap() {
        User user = testUser();
        String token = jwtService.generateToken(user);

        jwtService.getUserIdFromToken(token);

        // Since PII is no longer in the token, it cannot appear in any log line
        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg)
                    .as("Log at " + event.getLevel() + " must not contain email: " + msg)
                    .doesNotContain("alice@example.com");
        }
    }

    @Test
    @DisplayName("tokenVersion defaults to 0 when null on the User object")
    void tokenVersion_defaultsToZero_whenNull() {
        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email("bob@example.com")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .tokenVersion(null)   // explicitly null
                .registrationStage(RegistrationStage.FINISHED)
                .build();

        String token = jwtService.generateToken(user);
        org.springframework.security.oauth2.jwt.Jwt decoded = jwtDecoder.decode(token);

        assertThat(((Number) decoded.getClaim("tokenVersion")).intValue()).isEqualTo(0);
    }
}
