// ==================== STEP 1: Configuration Properties Class ====================

package com.example.dating.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * This class reads configuration from application.yml and makes it available
 * as a Spring Bean that can be injected into other classes.
 *
 * WHY? Instead of hardcoding API keys and URLs in code, we store them in
 * application.yml for easy configuration and security.
 */
@Configuration  // Tells Spring this is a configuration class
@ConfigurationProperties(prefix = "geocoding")  // Read all properties starting with "geocoding"
@Data  // Lombok generates getters/setters automatically
public class GeocodingProperties {

    // Which geocoding provider to use: "google" or "nominatim"
    // Default: "google"
    private String provider = "google";

    // Configuration for Google Maps API
    private GoogleConfig google = new GoogleConfig();

    // Configuration for Nominatim (free alternative)
    private NominatimConfig nominatim = new NominatimConfig();

    /**
     * Nested class for Google-specific configuration
     */
    @Data
    public static class GoogleConfig {
        private String apiKey;      // Your Google Maps API key
        private String baseUrl = "https://maps.googleapis.com/maps/api/geocode/json";
    }

    /**
     * Nested class for Nominatim-specific configuration
     */
    @Data
    public static class NominatimConfig {
        private String baseUrl = "https://nominatim.openstreetmap.org/search";
        private String userAgent = "DatingApp/1.0";  // Required by Nominatim
    }
}
