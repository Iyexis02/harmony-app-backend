package com.example.dating.models.geocoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Result object returned by all geocoding services
 * Contains coordinates, address info, and success status
 *
 * This is used internally to pass geocoding results between services
 * and can also be returned to the frontend if needed
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)  // Don't serialize null fields
public class GeocodingResult {

    // ==================== Core Fields ====================

    /**
     * Latitude in decimal degrees
     * Range: -90.0 to +90.0
     * Example: 45.8150108
     */
    private BigDecimal latitude;

    /**
     * Longitude in decimal degrees
     * Range: -180.0 to +180.0
     * Example: 15.9819189
     */
    private BigDecimal longitude;

    /**
     * City name
     * Example: "Zagreb", "New York", "London"
     */
    private String city;

    /**
     * Country name (full name, not code)
     * Example: "Croatia", "United States", "United Kingdom"
     */
    private String country;

    /**
     * Country code (ISO 3166-1 alpha-2)
     * Example: "HR", "US", "GB"
     */
    private String countryCode;

    /**
     * Human-readable formatted address
     * Example: "Zagreb, Croatia"
     */
    private String formattedAddress;

    // ==================== Status Fields ====================

    /**
     * Whether the geocoding was successful
     * true = coordinates are valid
     * false = geocoding failed, check errorMessage
     */
    private boolean success;

    /**
     * Error message if success = false
     * Example: "Location not found", "API quota exceeded"
     */
    private String errorMessage;

    // ==================== Optional Metadata ====================

    /**
     * Google Places ID (if using Google API)
     * Can be used to fetch more details later
     * Example: "ChIJXVkbRzedZUcRAPRMSy3jmAQ"
     */
    private String placeId;

    /**
     * Postal/ZIP code
     * Example: "10000", "10001", "SW1A 1AA"
     */
    private String postalCode;

    /**
     * State/Province/Region name
     * Example: "California", "Ontario", "Île-de-France"
     */
    private String region;

    /**
     * Neighborhood or district name
     * Example: "Manhattan", "Trnje", "Shibuya"
     */
    private String neighborhood;

    /**
     * Street address (if available)
     * Example: "123 Main St"
     */
    private String streetAddress;

    /**
     * Location type indicating geocoding precision
     * Values: "ROOFTOP", "RANGE_INTERPOLATED", "GEOMETRIC_CENTER", "APPROXIMATE"
     */
    private String locationType;

    /**
     * Source of the geocoding result
     * Example: "GOOGLE_MAPS", "NOMINATIM", "IP_GEOLOCATION"
     */
    private String source;

    /**
     * Timestamp when this result was generated
     */
    private Long timestamp;

    // ==================== Factory Methods ====================

    /**
     * Create a successful geocoding result with minimal info
     *
     * @param lat Latitude
     * @param lon Longitude
     * @param city City name
     * @param country Country name
     * @return Successful GeocodingResult
     */
    public static GeocodingResult success(BigDecimal lat, BigDecimal lon,
                                          String city, String country) {
        return GeocodingResult.builder()
                .latitude(lat)
                .longitude(lon)
                .city(city)
                .country(country)
                .success(true)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create a successful geocoding result with full address
     *
     * @param lat Latitude
     * @param lon Longitude
     * @param city City name
     * @param country Country name
     * @param formattedAddress Full formatted address
     * @return Successful GeocodingResult
     */
    public static GeocodingResult successWithAddress(BigDecimal lat, BigDecimal lon,
                                                     String city, String country,
                                                     String formattedAddress) {
        return GeocodingResult.builder()
                .latitude(lat)
                .longitude(lon)
                .city(city)
                .country(country)
                .formattedAddress(formattedAddress)
                .success(true)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create a failed geocoding result
     *
     * @param errorMessage Error message describing why geocoding failed
     * @return Failed GeocodingResult
     */
    public static GeocodingResult failure(String errorMessage) {
        return GeocodingResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create a failed result with attempted location info
     * Useful for logging what was attempted
     */
    public static GeocodingResult failureWithContext(String errorMessage,
                                                     String city,
                                                     String country) {
        return GeocodingResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .city(city)
                .country(country)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ==================== Validation Methods ====================

    /**
     * Check if coordinates are present and valid
     * Latitude must be between -90 and +90
     * Longitude must be between -180 and +180
     *
     * @return true if coordinates are valid, false otherwise
     */
    public boolean hasValidCoordinates() {
        if (latitude == null || longitude == null) {
            return false;
        }

        double lat = latitude.doubleValue();
        double lon = longitude.doubleValue();

        return lat >= -90.0 && lat <= 90.0 &&
                lon >= -180.0 && lon <= 180.0;
    }

    /**
     * Check if result has basic location information
     * At minimum needs coordinates OR city+country
     *
     * @return true if has usable location data
     */
    public boolean hasLocationData() {
        return hasValidCoordinates() ||
                (city != null && !city.isBlank() &&
                        country != null && !country.isBlank());
    }

    /**
     * Check if coordinates are precise (rooftop or range interpolated)
     *
     * @return true if location type indicates high precision
     */
    public boolean isPreciseLocation() {
        return "ROOFTOP".equals(locationType) ||
                "RANGE_INTERPOLATED".equals(locationType);
    }

    /**
     * Check if this is an approximate location (city-level)
     *
     * @return true if location is approximate
     */
    public boolean isApproximateLocation() {
        return "APPROXIMATE".equals(locationType) ||
                "GEOMETRIC_CENTER".equals(locationType);
    }

    /**
     * Get precision radius in meters
     * Estimates how accurate the coordinates are
     *
     * @return Approximate radius in meters
     */
    public int getPrecisionRadiusMeters() {
        if (locationType == null) {
            return 5000; // Default: 5km
        }

        return switch (locationType) {
            case "ROOFTOP" -> 10;                    // ±10 meters
            case "RANGE_INTERPOLATED" -> 50;         // ±50 meters
            case "GEOMETRIC_CENTER" -> 500;          // ±500 meters
            case "APPROXIMATE" -> 5000;              // ±5 km
            default -> 5000;
        };
    }

    // ==================== Utility Methods ====================

    /**
     * Get a short display string for this location
     * Example: "Zagreb, Croatia"
     *
     * @return Display string
     */
    public String getDisplayString() {
        if (formattedAddress != null && !formattedAddress.isBlank()) {
            return formattedAddress;
        }

        if (city != null && country != null) {
            return city + ", " + country;
        }

        if (latitude != null && longitude != null) {
            return String.format("%.4f, %.4f",
                    latitude.doubleValue(),
                    longitude.doubleValue());
        }

        return "Unknown location";
    }

    /**
     * Convert to a simple coordinate string
     * Example: "45.8150, 15.9819"
     *
     * @return Coordinate string or "Unknown"
     */
    public String getCoordinateString() {
        if (hasValidCoordinates()) {
            return String.format("%.4f, %.4f",
                    latitude.doubleValue(),
                    longitude.doubleValue());
        }
        return "Unknown";
    }

    /**
     * Create a copy of this result with rounded coordinates
     * Useful for privacy (reduce precision)
     *
     * @param scale Number of decimal places (2 = ~1km, 3 = ~111m)
     * @return New GeocodingResult with rounded coordinates
     */
    public GeocodingResult withRoundedCoordinates(int scale) {
        if (!hasValidCoordinates()) {
            return this;
        }

        return GeocodingResult.builder()
                .latitude(latitude.setScale(scale, java.math.RoundingMode.HALF_UP))
                .longitude(longitude.setScale(scale, java.math.RoundingMode.HALF_UP))
                .city(city)
                .country(country)
                .countryCode(countryCode)
                .formattedAddress(formattedAddress)
                .success(success)
                .errorMessage(errorMessage)
                .placeId(placeId)
                .postalCode(postalCode)
                .region(region)
                .neighborhood(neighborhood)
                .locationType(locationType)
                .source(source)
                .timestamp(timestamp)
                .build();
    }

    /**
     * Check if this result is from a specific source
     *
     * @param sourceName Source to check (e.g., "GOOGLE_MAPS")
     * @return true if matches
     */
    public boolean isFromSource(String sourceName) {
        return source != null && source.equalsIgnoreCase(sourceName);
    }

    @Override
    public String toString() {
        if (!success) {
            return "GeocodingResult{failed: " + errorMessage + "}";
        }

        StringBuilder sb = new StringBuilder("GeocodingResult{");
        sb.append("location=").append(getDisplayString());

        if (hasValidCoordinates()) {
            sb.append(", coords=").append(getCoordinateString());
        }

        if (locationType != null) {
            sb.append(", precision=").append(locationType);
        }

        if (source != null) {
            sb.append(", source=").append(source);
        }

        sb.append("}");
        return sb.toString();
    }
}