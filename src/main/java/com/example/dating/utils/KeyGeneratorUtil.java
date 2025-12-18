package com.example.dating.utils;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class KeyGeneratorUtil {

    public static void main(String[] args) throws NoSuchAlgorithmException {
        // Generate AES-256 key
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256); // 256-bit key
        SecretKey secretKey = keyGenerator.generateKey();

        // Encode to Base64 for storage in application.yml
        String encodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());

        System.out.println("Add this to your application.yml:");
        System.out.println("encryption:");
        System.out.println("  secret:");
        System.out.println("    key: " + encodedKey);
    }
}