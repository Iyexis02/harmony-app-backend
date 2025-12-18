package com.example.dating.services;

import com.example.dating.models.geocoding.GeocodingResult;

public interface GeocodingService {
    GeocodingResult geocode(String city, String country);
}
