package com.zomato.service;

import com.zomato.model.Restaurant;
import com.zomato.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeolocationService {

    private final RestaurantRepository restaurantRepository;
    private final WebClient.Builder webClientBuilder;

    @Value("${google.maps.api-key}")
    private String googleMapsApiKey;

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double AVERAGE_SPEED_KMPH = 30.0;

    /**
     * Calculate distance between two coordinates using Haversine formula.
     */
    public double getDistance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Estimate delivery time based on distance (assuming average speed 30 km/h + 10 min prep).
     */
    public int getEstimatedDeliveryTime(double restaurantLat, double restaurantLng,
                                         double deliveryLat, double deliveryLng) {
        double distanceKm = getDistance(restaurantLat, restaurantLng, deliveryLat, deliveryLng);
        int travelMinutes = (int) Math.ceil((distanceKm / AVERAGE_SPEED_KMPH) * 60);
        return travelMinutes + 10; // 10 min preparation
    }

    /**
     * Get nearby restaurants within radius using DB Haversine query then re-verify with Java.
     */
    public List<Restaurant> getNearbyRestaurants(double lat, double lng, double radiusKm) {
        return restaurantRepository.findRestaurantsNearby(lat, lng, radiusKm)
                .stream()
                .filter(r -> r.getLatitude() != null && r.getLongitude() != null
                        && getDistance(lat, lng, r.getLatitude(), r.getLongitude()) <= radiusKm)
                .toList();
    }

    /**
     * Geocode an address to lat/lng using Google Maps Geocoding API.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Double> geocodeAddress(String address) {
        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json";
            Map<String, Object> result = webClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("maps.googleapis.com")
                            .path("/maps/api/geocode/json")
                            .queryParam("address", address)
                            .queryParam("key", googleMapsApiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (result != null && "OK".equals(result.get("status"))) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
                if (results != null && !results.isEmpty()) {
                    Map<String, Object> geometry = (Map<String, Object>) results.get(0).get("geometry");
                    Map<String, Double> location = (Map<String, Double>) geometry.get("location");
                    Map<String, Double> coords = new HashMap<>();
                    coords.put("lat", location.get("lat"));
                    coords.put("lng", location.get("lng"));
                    return coords;
                }
            }
        } catch (Exception e) {
            log.error("Failed to geocode address '{}': {}", address, e.getMessage());
        }
        return new HashMap<>();
    }
}
