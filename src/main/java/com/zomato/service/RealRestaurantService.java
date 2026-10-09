package com.zomato.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zomato.model.MenuItem;
import com.zomato.model.Restaurant;
import com.zomato.repository.MenuItemRepository;
import com.zomato.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class RealRestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    private static final List<String> OVERPASS_ENDPOINTS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
    );

    @Transactional
    public List<Restaurant> fetchAndSeedRealRestaurants(double lat, double lng, double radiusMeters) {
        log.info("Fetching real restaurants near lat={}, lng={}, radius={}m from OpenStreetMap", lat, lng, radiusMeters);

        // Clamp radius between 1000m and 10000m
        double safeRadius = Math.max(1000.0, Math.min(radiusMeters, 10000.0));

        // Overpass QL query: nodes with amenity in restaurant, fast_food, cafe (use Locale.US for decimal points)
        String query = String.format(
                Locale.US,
                "[out:json][timeout:15];(" +
                "node[\"amenity\"=\"restaurant\"](around:%d,%.6f,%.6f);" +
                "node[\"amenity\"=\"fast_food\"](around:%d,%.6f,%.6f);" +
                "node[\"amenity\"=\"cafe\"](around:%d,%.6f,%.6f);" +
                ");out body 15;",
                (long) safeRadius, lat, lng,
                (long) safeRadius, lat, lng,
                (long) safeRadius, lat, lng
        );

        String postBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        String responseBody = null;
        for (String endpoint : OVERPASS_ENDPOINTS) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .POST(HttpRequest.BodyPublishers.ofString(postBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                    responseBody = response.body();
                    log.info("Successfully fetched OSM data from endpoint: {}", endpoint);
                    break;
                } else {
                    log.warn("Overpass endpoint {} returned status {}. Trying next mirror...", endpoint, response.statusCode());
                }
            } catch (Exception e) {
                log.warn("Error calling Overpass endpoint {}: {} - {}. Trying next mirror...",
                        endpoint, e.getClass().getSimpleName(), e.getMessage());
            }
        }

        if (responseBody != null) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode elements = root.path("elements");

                if (elements.isArray() && !elements.isEmpty()) {
                    List<Restaurant> newlySeeded = new ArrayList<>();
                    List<Restaurant> existingList = restaurantRepository.findAll();

                    for (JsonNode node : elements) {
                        JsonNode tags = node.path("tags");
                        String name = tags.path("name").asText(null);

                        if (name == null || name.trim().length() < 2) {
                            continue;
                        }
                        name = name.trim();

                        double rLat = node.path("lat").asDouble();
                        double rLon = node.path("lon").asDouble();

                        final String checkName = name;
                        boolean alreadyExists = existingList.stream()
                                .anyMatch(existing -> (existing.getName() != null && existing.getName().equalsIgnoreCase(checkName))
                                        || (existing.getLatitude() != null && Math.abs(existing.getLatitude() - rLat) < 0.0005
                                        && existing.getLongitude() != null && Math.abs(existing.getLongitude() - rLon) < 0.0005));

                        if (alreadyExists) {
                            continue;
                        }

                        String cuisineTag = tags.path("cuisine").asText(null);
                        String amenityTag = tags.path("amenity").asText("restaurant");
                        String cuisineType = formatCuisine(cuisineTag, amenityTag);

                        String street = tags.path("addr:street").asText("");
                        String housenumber = tags.path("addr:housenumber").asText("");
                        String suburb = tags.path("addr:suburb").asText("");
                        String city = tags.path("addr:city").asText("");

                        StringBuilder addressBuilder = new StringBuilder();
                        if (!housenumber.isBlank()) addressBuilder.append(housenumber).append(", ");
                        if (!street.isBlank()) addressBuilder.append(street).append(", ");
                        if (!suburb.isBlank()) addressBuilder.append(suburb).append(", ");
                        if (!city.isBlank()) addressBuilder.append(city);

                        String fullAddress = addressBuilder.toString().trim();
                        if (fullAddress.endsWith(",")) {
                            fullAddress = fullAddress.substring(0, fullAddress.length() - 1);
                        }
                        if (fullAddress.isBlank()) {
                            fullAddress = "Near your current location";
                        }

                        String phone = tags.path("phone").asText(tags.path("contact:phone").asText("+91 98765 43210"));
                        double rating = 4.0 + (random.nextInt(9) / 10.0);
                        int deliveryTime = 20 + random.nextInt(20);
                        BigDecimal minOrder = BigDecimal.valueOf(99 + (random.nextInt(3) * 50));

                        Restaurant restaurant = Restaurant.builder()
                                .name(name)
                                .description("Authentic " + cuisineType + " prepared fresh with premium ingredients.")
                                .cuisineType(cuisineType)
                                .address(fullAddress)
                                .phone(phone)
                                .latitude(rLat)
                                .longitude(rLon)
                                .rating(rating)
                                .totalRatings(50 + random.nextInt(450))
                                .avgDeliveryTime(deliveryTime)
                                .minOrderAmount(minOrder)
                                .isOpen(true)
                                .imageUrl(getImageForCuisine(cuisineType))
                                .build();

                        Restaurant saved = restaurantRepository.save(restaurant);
                        seedCustomMenuItems(saved, cuisineType);
                        newlySeeded.add(saved);
                        existingList.add(saved);
                        log.info("Auto-seeded real restaurant from OSM: {} at ({}, {})", name, rLat, rLon);
                    }

                    if (!newlySeeded.isEmpty()) {
                        return newlySeeded;
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse OSM response: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        // If Overpass returned no results or all mirrors failed/blocked, seed realistic local restaurants around (lat, lng)
        return seedFallbackNearbyRestaurants(lat, lng);
    }

    private List<Restaurant> seedFallbackNearbyRestaurants(double lat, double lng) {
        log.info("Seeding realistic local nearby restaurants centered at lat={}, lng={}", lat, lng);
        List<Restaurant> existingList = restaurantRepository.findAll();

        record FallbackSpec(String name, String cuisine, String description, String address, double latOff, double lngOff, int deliveryMin, double rating) {}

        List<FallbackSpec> specs = List.of(
                new FallbackSpec("The Royal Biryani Durbar", "Biryani, North Indian, Mughlai", "Authentic slow-cooked dum biryani and smoky clay-oven kebabs.", "Main Boulevard, Near Tech Park", 0.0031, 0.0028, 25, 4.7),
                new FallbackSpec("Urban Oven & Pizzeria", "Pizza, Italian, Fast Food", "Artisanal wood-fired sourdough pizzas with fresh basil and mozzarella.", "Opposite Central Square", -0.0022, 0.0034, 30, 4.5),
                new FallbackSpec("Golden Dragon Asian Kitchen", "Chinese, Asian, Noodles", "Wok-tossed noodles, crispy dim sums, and spicy Schezwan specialties.", "Commercial Complex, 2nd Floor", 0.0041, -0.0021, 28, 4.4),
                new FallbackSpec("Green Leaf Pure Veg Delights", "South Indian, Pure Veg, North Indian", "Crispy butter dosas, filter coffee, and rich North Indian curries.", "Temple Road, Market Junction", -0.0032, -0.0031, 20, 4.6),
                new FallbackSpec("Smash Burger Co.", "Burgers, Fast Food, American", "Juicy smashed burgers and crispy paneer towers with peri peri fries.", "High Street Promenade", 0.0019, -0.0042, 22, 4.3),
                new FallbackSpec("The Daily Brew & Bakery", "Cafe, Coffee, Bakery, Desserts", "Specialty espresso roasts, handcrafted croissants, and decadent brownies.", "Corner Avenue, Metro Gate 2", -0.0038, 0.0019, 24, 4.8)
        );

        List<Restaurant> created = new ArrayList<>();
        for (FallbackSpec spec : specs) {
            boolean exists = existingList.stream()
                    .anyMatch(r -> r.getName() != null && r.getName().equalsIgnoreCase(spec.name()));
            if (exists) {
                continue;
            }

            Restaurant r = Restaurant.builder()
                    .name(spec.name())
                    .description(spec.description())
                    .cuisineType(spec.cuisine())
                    .address(spec.address())
                    .phone("+91 " + (9800000000L + random.nextInt(199999999)))
                    .latitude(lat + spec.latOff())
                    .longitude(lng + spec.lngOff())
                    .rating(spec.rating())
                    .totalRatings(120 + random.nextInt(400))
                    .avgDeliveryTime(spec.deliveryMin())
                    .minOrderAmount(BigDecimal.valueOf(99 + random.nextInt(3) * 50))
                    .isOpen(true)
                    .imageUrl(getImageForCuisine(spec.cuisine()))
                    .build();

            Restaurant saved = restaurantRepository.save(r);
            seedCustomMenuItems(saved, spec.cuisine());
            created.add(saved);
            existingList.add(saved);
            log.info("Seeded nearby restaurant: {} at ({}, {})", saved.getName(), saved.getLatitude(), saved.getLongitude());
        }

        return created;
    }

    private String formatCuisine(String cuisineTag, String amenityTag) {
        if (cuisineTag != null && !cuisineTag.isBlank()) {
            String cleaned = cuisineTag.replace(";", ", ").replace("_", " ").trim();
            if (!cleaned.isEmpty()) {
                return cleaned.substring(0, 1).toUpperCase() + cleaned.substring(1);
            }
        }
        if ("cafe".equalsIgnoreCase(amenityTag)) return "Cafe, Coffee, Bakery";
        if ("fast_food".equalsIgnoreCase(amenityTag)) return "Fast Food, Burgers, Quick Bites";
        return "North Indian, Multi-Cuisine";
    }

    private String getImageForCuisine(String cuisine) {
        String c = cuisine.toLowerCase();
        if (c.contains("pizza")) return "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600&auto=format&fit=crop&q=80";
        if (c.contains("burger")) return "https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=600&auto=format&fit=crop&q=80";
        if (c.contains("biryani")) return "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=600&auto=format&fit=crop&q=80";
        if (c.contains("cafe") || c.contains("coffee")) return "https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?w=600&auto=format&fit=crop&q=80";
        if (c.contains("chinese") || c.contains("asian") || c.contains("noodle")) return "https://images.unsplash.com/photo-1585032226651-759b368d7246?w=600&auto=format&fit=crop&q=80";
        if (c.contains("dessert") || c.contains("ice cream") || c.contains("bakery")) return "https://images.unsplash.com/photo-1551024709-8f23befc6f87?w=600&auto=format&fit=crop&q=80";
        return "https://images.unsplash.com/photo-1555396273-367ea4eb4db5?w=600&auto=format&fit=crop&q=80";
    }

    private void seedCustomMenuItems(Restaurant r, String cuisine) {
        String c = cuisine.toLowerCase();
        List<MenuItem> items = new ArrayList<>();

        if (c.contains("pizza") || c.contains("italian")) {
            items.add(MenuItem.builder().restaurant(r).name("Margherita Basil Supreme").price(BigDecimal.valueOf(249)).category("Pizza").isVeg(true).isAvailable(true).description("Fresh mozzarella, tomato sauce and organic basil leaves").imageUrl("https://images.unsplash.com/photo-1604382354936-07c5d9983bd3?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Pepperoni & Herb Feast").price(BigDecimal.valueOf(349)).category("Pizza").isVeg(false).isAvailable(true).description("Loaded spicy pepperoni with herbs").imageUrl("https://images.unsplash.com/photo-1628840042765-356cda07504e?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Cheesy Garlic Breadsticks").price(BigDecimal.valueOf(149)).category("Sides").isVeg(true).isAvailable(true).description("Baked garlic bread topped with melted cheddar").imageUrl("https://images.unsplash.com/photo-1573140247632-f8fd74997d5c?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Molten Choco Lava Cake").price(BigDecimal.valueOf(119)).category("Desserts").isVeg(true).isAvailable(true).description("Warm chocolate cake with a gooey fudge center").imageUrl("https://images.unsplash.com/photo-1606313564200-e75d5e30476c?w=400").build());
        } else if (c.contains("burger") || c.contains("fast food")) {
            items.add(MenuItem.builder().restaurant(r).name("Signature Smash Cheeseburger").price(BigDecimal.valueOf(199)).category("Burgers").isVeg(false).isAvailable(true).description("Double grilled patty with cheddar, pickles and house sauce").imageUrl("https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Crispy Paneer Tower Burger").price(BigDecimal.valueOf(179)).category("Burgers").isVeg(true).isAvailable(true).description("Crusted paneer patty with spicy mayo and lettuce").imageUrl("https://images.unsplash.com/photo-1550547660-d9450f859349?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Peri-Peri Crinkle Fries").price(BigDecimal.valueOf(99)).category("Sides").isVeg(true).isAvailable(true).description("Golden fries tossed in spicy peri-peri dust").imageUrl("https://images.unsplash.com/photo-1576107232684-1279f3908594?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Thick Chocolate Shake").price(BigDecimal.valueOf(139)).category("Beverages").isVeg(true).isAvailable(true).description("Rich Belgian chocolate blended with creamy ice cream").imageUrl("https://images.unsplash.com/photo-1572490122747-3968b75cc699?w=400").build());
        } else if (c.contains("cafe") || c.contains("coffee") || c.contains("bakery")) {
            items.add(MenuItem.builder().restaurant(r).name("Artisan Hazelnut Cappuccino").price(BigDecimal.valueOf(169)).category("Coffee").isVeg(true).isAvailable(true).description("Espresso with steamed milk foam and toasted hazelnut").imageUrl("https://images.unsplash.com/photo-1534778101976-62847782c213?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Grilled Spinach & Corn Panini").price(BigDecimal.valueOf(189)).category("Sandwiches").isVeg(true).isAvailable(true).description("Toasted sourdough stuffed with corn and herb cheese").imageUrl("https://images.unsplash.com/photo-1528735602780-2552fd46c7af?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Blueberry Glazed Croissant").price(BigDecimal.valueOf(129)).category("Bakery").isVeg(true).isAvailable(true).description("Flaky butter pastry with wild blueberry compote").imageUrl("https://images.unsplash.com/photo-1555507036-ab1f4038808a?w=400").build());
        } else {
            // General / Indian / Mughlai / Biryani
            items.add(MenuItem.builder().restaurant(r).name("Chef's Special Dum Biryani").price(BigDecimal.valueOf(289)).category("Biryani").isVeg(false).isAvailable(true).description("Slow-cooked fragrant basmati rice with exotic royal spices").imageUrl("https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Paneer Butter Masala & Naan Combo").price(BigDecimal.valueOf(229)).category("Main Course").isVeg(true).isAvailable(true).description("Creamy tomato gravy paneer served with 2 butter naans").imageUrl("https://images.unsplash.com/photo-1631452180519-c014fe946bc7?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Crispy Tandoori Kebab Platter").price(BigDecimal.valueOf(249)).category("Starters").isVeg(false).isAvailable(true).description("Clay oven roasted kebabs with mint chutney").imageUrl("https://images.unsplash.com/photo-1599488615731-7e5c2823ff28?w=400").build());
            items.add(MenuItem.builder().restaurant(r).name("Royal Gulab Jamun (2 Pcs)").price(BigDecimal.valueOf(79)).category("Desserts").isVeg(true).isAvailable(true).description("Soft dumplings soaked in warm saffron syrup").imageUrl("https://images.unsplash.com/photo-1601050690597-df0568f70950?w=400").build());
        }

        menuItemRepository.saveAll(items);
    }
}
