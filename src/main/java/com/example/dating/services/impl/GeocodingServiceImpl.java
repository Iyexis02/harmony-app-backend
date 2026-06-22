package com.example.dating.services.impl;

import com.example.dating.config.GeocodingProperties;
import com.example.dating.models.geocoding.GeocodingResult;
import com.example.dating.models.geocoding.GoogleGeocodingResponse;
import com.example.dating.services.GeocodingService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeocodingServiceImpl implements GeocodingService {

    private final GeocodingProperties properties;
    /** Reuses the singleton RestTemplate with 5 s connect / 10 s read timeout. */
    private final RestTemplate spotifyRestTemplate;

    @Override
    @CircuitBreaker(name = "geocoding", fallbackMethod = "geocodeFallback")
    public GeocodingResult geocode(String city, String country) {
        log.info("Geocoding: {}, {}", city, country);
        log.info("Using provider: {}", properties.getProvider());

        String url = UriComponentsBuilder
                .fromUriString(properties.getGoogle().getBaseUrl())
                .queryParam("address", city + ", " + country)
                .queryParam("key", properties.getGoogle().getApiKey())
                .toUriString();

        log.debug("API URL: {}", url);

        try {
            GoogleGeocodingResponse response = spotifyRestTemplate.getForObject(
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

    private GeocodingResult geocodeFallback(String city, String country, Throwable t) {
        log.warn("Geocoding circuit open for {}, {}: {}", city, country, t.getMessage());
        return GeocodingResult.builder()
                .success(false)
                .errorMessage("Geocoding service temporarily unavailable — circuit open")
                .build();
    }
}
