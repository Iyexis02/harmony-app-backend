package com.example.dating.models.geocoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Complete response model for Google Geocoding API
 * Automatically deserializes JSON responses from:
 * https://maps.googleapis.com/maps/api/geocode/json
 *
 * Documentation: https://developers.google.com/maps/documentation/geocoding/overview
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoogleGeocodingResponse {

    /**
     * List of geocoded results
     * Usually contains 1 result, but can have multiple for ambiguous addresses
     */
    private List<Result> results;

    /**
     * Status of the request
     * Possible values:
     * - "OK": Success
     * - "ZERO_RESULTS": No results found
     * - "OVER_QUERY_LIMIT": API quota exceeded
     * - "REQUEST_DENIED": Request was denied (invalid API key)
     * - "INVALID_REQUEST": Missing query parameters
     * - "UNKNOWN_ERROR": Server error
     */
    private String status;

    /**
     * Error message (only present when status != "OK")
     */
    @JsonProperty("error_message")
    private String errorMessage;

    // ==================== Result Class ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {

        /**
         * Array of separate address components
         * e.g., street, city, country, postal code
         */
        @JsonProperty("address_components")
        private List<AddressComponent> addressComponents;

        /**
         * Human-readable address of this location
         * e.g., "Zagreb, Croatia"
         */
        @JsonProperty("formatted_address")
        private String formattedAddress;

        /**
         * Geometry information (coordinates, bounds, viewport)
         */
        private Geometry geometry;

        /**
         * Unique identifier for this place
         * Can be used with Google Places API
         */
        @JsonProperty("place_id")
        private String placeId;

        /**
         * Array indicating the type of the returned result
         * e.g., ["locality", "political"], ["street_address"], ["country"]
         */
        private List<String> types;

        /**
         * Indicates that the geocoder did not return an exact match
         * Only present when partial_match = true
         */
        @JsonProperty("partial_match")
        private Boolean partialMatch;

        /**
         * Additional data about the specified location
         */
        @JsonProperty("plus_code")
        private PlusCode plusCode;
    }

    // ==================== Address Component Class ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AddressComponent {

        /**
         * Full text description of the address component
         * e.g., "Croatia", "Zagreb", "10000"
         */
        @JsonProperty("long_name")
        private String longName;

        /**
         * Abbreviated text description
         * e.g., "HR", "ZG"
         */
        @JsonProperty("short_name")
        private String shortName;

        /**
         * Array indicating the type of the address component
         * Common types:
         * - street_number
         * - route (street name)
         * - locality (city)
         * - administrative_area_level_1 (state/province)
         * - administrative_area_level_2 (county)
         * - country
         * - postal_code
         * - postal_code_suffix
         */
        private List<String> types;
    }

    // ==================== Geometry Class ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Geometry {

        /**
         * Geocoded latitude/longitude value
         */
        private Location location;

        /**
         * Precision of the geocoded location
         * Values:
         * - "ROOFTOP": Precise location (street address)
         * - "RANGE_INTERPOLATED": Approximation between two precise points
         * - "GEOMETRIC_CENTER": Center of a location (park, intersection)
         * - "APPROXIMATE": Approximate location (city, region)
         */
        @JsonProperty("location_type")
        private String locationType;

        /**
         * Recommended viewport for displaying this result
         */
        private Bounds viewport;

        /**
         * Bounding box which can fully contain the returned result
         * May not match the recommended viewport
         */
        private Bounds bounds;
    }

    // ==================== Location Class ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {

        /**
         * Latitude in decimal degrees
         * Range: -90 to +90
         */
        private BigDecimal lat;

        /**
         * Longitude in decimal degrees
         * Range: -180 to +180
         */
        private BigDecimal lng;
    }

    // ==================== Bounds Class ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bounds {

        /**
         * Northeast corner of the bounding box
         */
        private Location northeast;

        /**
         * Southwest corner of the bounding box
         */
        private Location southwest;
    }

    // ==================== Plus Code Class ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlusCode {

        /**
         * Global Plus Code (e.g., "8FW4V2JJ+RJ")
         */
        @JsonProperty("global_code")
        private String globalCode;

        /**
         * Compound Plus Code (e.g., "V2JJ+RJ Zagreb, Croatia")
         */
        @JsonProperty("compound_code")
        private String compoundCode;
    }

    // ==================== Helper Methods ====================

    /**
     * Check if the geocoding was successful
     */
    public boolean isSuccessful() {
        return "OK".equals(status);
    }

    /**
     * Check if results exist
     */
    public boolean hasResults() {
        return results != null && !results.isEmpty();
    }

    /**
     * Get the first (most relevant) result
     */
    public Result getFirstResult() {
        if (hasResults()) {
            return results.get(0);
        }
        return null;
    }

    /**
     * Get coordinates from first result
     */
    public Location getFirstLocation() {
        Result first = getFirstResult();
        if (first != null && first.getGeometry() != null) {
            return first.getGeometry().getLocation();
        }
        return null;
    }
}
