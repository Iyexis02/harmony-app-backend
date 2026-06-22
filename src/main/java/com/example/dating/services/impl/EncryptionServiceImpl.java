package com.example.dating.services.impl;

import com.example.dating.services.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

@Slf4j
@Service
public class EncryptionServiceImpl implements EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // 128 bits

    private final SecretKey secretKey;

    public EncryptionServiceImpl(@Value("${encryption.secret.key}") String encryptionKey) throws IllegalArgumentException  {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException(
                "ENCRYPTION_SECRET_KEY environment variable must be set. " +
                "Generate one with: openssl rand -base64 32"
            );
        }
        // Convert base64 encoded key to SecretKey
        byte[] decodedKey = Base64.getDecoder().decode(encryptionKey);
        this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
    }

    /**
     * Encrypts the given plaintext using AES-256-GCM
     * @param plaintext the text to encrypt
     * @return Base64 encoded string containing IV + encrypted data
     */
    public String encrypt(String plaintext) {
        try {
            // Generate random IV (Initialization Vector)
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Encrypt
            byte[] encryptedData = cipher.doFinal(plaintext.getBytes("UTF-8"));

            // Combine IV and encrypted data
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encryptedData.length);
            byteBuffer.put(iv);
            byteBuffer.put(encryptedData);

            // Encode to Base64 for storage
            return Base64.getEncoder().encodeToString(byteBuffer.array());

        } catch (NullPointerException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 IllegalBlockSizeException | BadPaddingException e) {
            log.error("Error during encrypting data", e);
            throw new RuntimeException("Encryption failed", e);
        } catch (Exception e) {
            log.error("Unexpected Error encrypting data", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts the given ciphertext using AES-256-GCM
     * @param ciphertext Base64 encoded string containing IV + encrypted data
     * @return decrypted plaintext
     */
    public String decrypt(String ciphertext) {
        try {
            // Decode from Base64
            byte[] decoded = Base64.getDecoder().decode(ciphertext);

            // Extract IV and encrypted data
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] encryptedData = new byte[byteBuffer.remaining()];
            byteBuffer.get(encryptedData);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Decrypt
            byte[] decryptedData = cipher.doFinal(encryptedData);

            return new String(decryptedData, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException
                 | BufferUnderflowException
                 | NoSuchAlgorithmException
                 | NoSuchPaddingException
                 | InvalidKeyException
                 | InvalidAlgorithmParameterException
                 | UnsupportedOperationException
                 | IllegalStateException
                 | IllegalBlockSizeException
                 | BadPaddingException e) {
            log.error("Error while decrypting data", e);
            throw new RuntimeException("Decryption failed", e);
        } catch (Exception e) {
            log.error("Unexpected error decrypting data", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }
}