package com.example.dating.services.impl;

import com.example.dating.services.EmailService;
import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    /**
     * 3 attempts with jittered backoff (500–1500 ms between attempts) — defeats the
     * thundering-herd problem when Resend returns 429 to a burst of senders that would
     * otherwise all retry on the same fixed delay.
     */
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 500L;
    private static final long BACKOFF_JITTER_MS = 1000L;

    private static final String MDC_EMAIL_OUTCOME = "email_outcome";
    private static final String MDC_EMAIL_ATTEMPTS = "email_attempt_count";

    private final Resend resend;

    @Value("${resend.from}")
    private String fromEmail;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    public EmailServiceImpl(@Value("${resend.api-key}") String apiKey) {
        this.resend = new Resend(apiKey);
    }

    @Async
    @Override
    public void sendVerificationEmail(String toEmail, String userName, String verificationToken) {
        String verificationUrl = frontendUrl + "/verify-email?token=" + verificationToken;
        String text = "Hello " + userName + ",\n\n"
                + "Thank you for registering! Please verify your email by clicking the link below:\n\n"
                + verificationUrl + "\n\n"
                + "This link will expire in 24 hours.\n\n"
                + "If you didn't create an account, please ignore this email.\n\n"
                + "Best regards,\n"
                + "Dating App Team";
        sendWithRetry(toEmail, "Verify Your Email - Dating App", text, "verification");
    }

    @Async
    @Override
    public void sendPasswordResetEmail(String toEmail, String userName, String resetToken) {
        String resetUrl = frontendUrl + "/reset-password?token=" + resetToken;
        String text = "Hello " + userName + ",\n\n"
                + "We received a request to reset your password. Click the link below to reset it:\n\n"
                + resetUrl + "\n\n"
                + "This link will expire in 1 hour.\n\n"
                + "If you didn't request this, please ignore this email.\n\n"
                + "Best regards,\n"
                + "Dating App Team";
        sendWithRetry(toEmail, "Reset Your Password - Dating App", text, "password_reset");
    }

    @Async
    @Override
    public void sendWelcomeEmail(String toEmail, String userName) {
        String text = "Hello " + userName + ",\n\n"
                + "Welcome to Dating App! Your email has been verified.\n\n"
                + "You can now complete your profile and start connecting with others.\n\n"
                + "Best regards,\n"
                + "Dating App Team";
        // Welcome email is fire-and-forget — single attempt, no retry.
        try {
            send(toEmail, "Welcome to Dating App!", text);
            log.info("Welcome email sent to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send welcome email to {}: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Sends an email with retry. Adds {@code email_outcome} (success/failed) and
     * {@code email_attempt_count} to the MDC on each terminal log line so failure
     * rates can be charted from structured logs.
     */
    private void sendWithRetry(String toEmail, String subject, String text, String emailType) {
        Exception lastException = null;
        int attempt;
        for (attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                CreateEmailResponse response = send(toEmail, subject, text);
                try {
                    MDC.put(MDC_EMAIL_OUTCOME, "success");
                    MDC.put(MDC_EMAIL_ATTEMPTS, String.valueOf(attempt));
                    log.info("{} email sent to {} (id: {}, attempts: {})",
                            emailType, toEmail, response.getId(), attempt);
                } finally {
                    MDC.remove(MDC_EMAIL_OUTCOME);
                    MDC.remove(MDC_EMAIL_ATTEMPTS);
                }
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        // Jittered backoff: 500–1500 ms. Spreads concurrent retries across
                        // a 1-second window so a 429 burst does not all retry in lockstep.
                        Thread.sleep(BACKOFF_BASE_MS + ThreadLocalRandom.current().nextLong(BACKOFF_JITTER_MS));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("{} email send interrupted for {}", emailType, toEmail);
                        return;
                    }
                }
            }
        }

        try {
            MDC.put(MDC_EMAIL_OUTCOME, "failed");
            MDC.put(MDC_EMAIL_ATTEMPTS, String.valueOf(MAX_ATTEMPTS));
            log.error("Failed to send {} email to {} after {} attempts: {}",
                    emailType, toEmail, MAX_ATTEMPTS,
                    lastException != null ? lastException.getMessage() : "unknown");
        } finally {
            MDC.remove(MDC_EMAIL_OUTCOME);
            MDC.remove(MDC_EMAIL_ATTEMPTS);
        }
    }

    private CreateEmailResponse send(String toEmail, String subject, String text) throws Exception {
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(fromEmail)
                .to(toEmail)
                .subject(subject)
                .text(text)
                .build();
        return resend.emails().send(params);
    }
}
