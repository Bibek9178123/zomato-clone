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
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class RealRestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";

    @Transactional
    public List<Restaurant> fetchAndSeedRealRestaurants(double lat, double lng, double radiusMeters) {
        log.info("Fetching real restaurants near lat={}, lng={}, radius={}m from OpenStreetMap", lat, lng, radiusMeters);

        // Clamp radius between 1000m and 10000m
        double safeRadius = Math.max(1000.0, Math.min(radiusMeters, 10000.0));

        // Overpass QL query: nodes with amenity in restaurant, fast_food, cafe
        String query = String.format(
                "[out:json][timeout:15];(" +
                "node[\"amenity\"=\"restaurant\"](around:%d,%.6f,%.6f);" +
                "node[\"amenity\"=\"fast_food\"](around:%d,%.6f,%.6f);" +
                "node[\"amenity\"=\"cafe\"](around:%d,%.6f,%.6f);" +
                ");out body 15;",
                (long) safeRadius, lat, lng,
                (long) safeRadius, lat, lng,
                (long) safeRadius, lat, lng
        );

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String postBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OVERPASS_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "ZomatoClone/1.0 (Food Delivery Platform)")
                    .POST(HttpRequest.BodyPublishers.ofString(postBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                log.warn("Overpass API returned status {} or empty body. Fallback to existing DB entries.", response.statusCode());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode elements = root.path("elements");

            if (!elements.isArray() || elements.isEmpty()) {
                log.info("No OpenStreetMap restaurant elements found in this radius.");
                return List.of();
            }

            List<Restaurant> newlySeeded = new ArrayList<>();

            for (JsonNode node : elements) {
                JsonNode tags = node.path("tags");
                String name = tags.path("name").asText(null);

                if (name == null || name.trim().length() < 2) {
                    continue;
                }
                name = name.trim();

                double rLat = node.path("lat").asDouble();
                double rLon = node.path("lon").asDouble();

                // Check if this restaurant already exists in database
                final String checkName = name;
                boolean alreadyExists = restaurantRepository.findAll().stream()
                        .anyMatch(existing -> existing.getName().equalsIgnoreCase(checkName)
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
                double rating = 4.0 + (random.nextInt(9) / 10.0); // 4.0 to 4.8
                int deliveryTime = 20 + random.nextInt(20); // 20 to 39 mins
                BigDecimal minOrder = BigDecimal.valueOf(99 + (random.nextInt(3) * 50)); // 99, 149, 199

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
                log.info("Auto-seeded real restaurant from OSM: {} at ({}, {})", name, rLat, rLon);
            }

            return newlySeeded;

        } catch (Exception e) {
            log.error("Error communicating with OpenStreetMap Overpass API: {}", e.getMessage());
            return List.of();
        }
    }

    private String formatCuisine(String cuisineTag, String amenityTag) {
        if (cuisineTag != null && !cuisineTag.isBlank()) {
            return cuisineTag.replace(";", ", ")
                    .replace("_", " ")
                    .toUpperCase().charAt(0) + cuisineTag.substring(1).replace(";", ", ");
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
