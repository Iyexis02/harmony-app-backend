package com.example.dating.services.impl;

import com.example.dating.config.GeocodingProperties;
import com.example.dating.models.geocoding.GeocodingResult;
import com.example.dating.models.geocoding.GoogleGeocodingResponse;
import com.example.dating.services.GeocodingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor  // Lombok generates constructor with final fields
@Slf4j
public class GeocodingServiceImpl implements GeocodingService {
    private final GeocodingProperties properties;

    public GeocodingResult geocode(String city, String country) {
        RestTemplate restTemplate = new RestTemplate();
        log.info("Geocoding: {}, {}", city, country);
        log.info("Using provider: {}", properties.getProvider());

        // Build the API URL
        String url = UriComponentsBuilder
                .fromUriString(properties.getGoogle().getBaseUrl())
                .queryParam("address", city + ", " + country)
                .queryParam("key", properties.getGoogle().getApiKey())
                .toUriString();

        log.debug("API URL: {}", url);

        try {
            // Make the API call
            GoogleGeocodingResponse response = restTemplate.getForObject(
                    url,
                    GoogleGeocodingResponse.class
            );

            if (response != null && "OK".equals(response.getStatus())) {
                var result = response.getResults().get(0);
                var location = result.getGeometry().getLocation();

                return GeocodingResult.builder()
                        .latitude(location.getLat())
                        .longitude(location.getLng())
                        .city(city)
                        .country(country)
                        .success(true)
                        .build();
            }

            return GeocodingResult.builder()
                    .success(false)
                    .errorMessage("Geocoding failed")
                    .build();

        } catch (Exception e) {
            log.error("Geocoding error: {}", e.getMessage());
            return GeocodingResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
