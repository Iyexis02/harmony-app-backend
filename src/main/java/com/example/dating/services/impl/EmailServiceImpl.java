package com.example.dating.services.impl;

import com.example.dating.services.EmailService;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    private final SendGrid sendGrid;

    @Value("${spring.mail.from}")
    private String fromEmail;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    public EmailServiceImpl(@Value("${sendgrid.api-key}") String apiKey) {
        this.sendGrid = new SendGrid(apiKey);
    }

    @Async
    @Override
    public void sendVerificationEmail(String toEmail, String userName, String verificationToken) {
        try {
            String verificationUrl = frontendUrl + "/verify-email?token=" + verificationToken;

            Email from = new Email(fromEmail);
            Email to = new Email(toEmail);
            String subject = "Verify Your Email - Dating App";
            Content content = new Content("text/plain", String.format(
                "Hello %s,\n\n" +
                "Thank you for registering! Please verify your email by clicking the link below:\n\n" +
                "%s\n\n" +
                "This link will expire in 24 hours.\n\n" +
                "If you didn't create an account, please ignore this email.\n\n" +
                "Best regards,\n" +
                "Dating App Team",
                userName, verificationUrl
            ));

            Mail mail = new Mail(from, subject, to, content);
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            log.info("Verification email sent to: {} (Status: {})", toEmail, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    @Async
    @Override
    public void sendPasswordResetEmail(String toEmail, String userName, String resetToken) {
        try {
            String resetUrl = frontendUrl + "/reset-password?token=" + resetToken;

            Email from = new Email(fromEmail);
            Email to = new Email(toEmail);
            String subject = "Reset Your Password - Dating App";
            Content content = new Content("text/plain", String.format(
                "Hello %s,\n\n" +
                "We received a request to reset your password. Click the link below to reset it:\n\n" +
                "%s\n\n" +
                "This link will expire in 1 hour.\n\n" +
                "If you didn't request this, please ignore this email.\n\n" +
                "Best regards,\n" +
                "Dating App Team",
                userName, resetUrl
            ));

            Mail mail = new Mail(from, subject, to, content);
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            log.info("Password reset email sent to: {} (Status: {})", toEmail, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }

    @Async
    @Override
    public void sendWelcomeEmail(String toEmail, String userName) {
        try {
            Email from = new Email(fromEmail);
            Email to = new Email(toEmail);
            String subject = "Welcome to Dating App!";
            Content content = new Content("text/plain", String.format(
                "Hello %s,\n\n" +
                "Welcome to Dating App! Your email has been verified.\n\n" +
                "You can now complete your profile and start connecting with others.\n\n" +
                "Best regards,\n" +
                "Dating App Team",
                userName
            ));

            Mail mail = new Mail(from, subject, to, content);
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            log.info("Welcome email sent to: {} (Status: {})", toEmail, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to send welcome email to {}: {}", toEmail, e.getMessage());
            // Don't throw - welcome email failure shouldn't break the flow
        }
    }
}
