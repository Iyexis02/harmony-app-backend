package com.example.dating.services;

public interface EmailService {
    void sendVerificationEmail(String toEmail, String userName, String verificationToken);
    void sendPasswordResetEmail(String toEmail, String userName, String resetToken);
    void sendWelcomeEmail(String toEmail, String userName);
}
