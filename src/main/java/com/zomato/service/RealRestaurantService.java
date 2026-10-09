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
import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * Normalizes a restaurant name to remove punctuation and noise words for smart deduplication.
     * E.g. "Domino's Pizza" and "Dominos" both normalize to "domino".
     */
    public static String normalizeName(String name) {
        if (name == null || name.isBlank()) return "";
        return name.toLowerCase()
                .replaceAll("(?i)\\b(restaurant|hotel|cafe|dhaba|the|pizzeria|pizza|kitchen|express|sweets|bakers|bakery|bar|foods|food|court|center|point|corner|hub|house|delights|darbar|durbar|junction|fast|burger)\\b", "")
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    /**
     * Purges duplicate restaurants from the database keeping the entry with the most menu items.
     */
    @Transactional
    public void purgeDuplicateRestaurantsInDatabase() {
        try {
            List<Restaurant> all = restaurantRepository.findAll();
            if (all.isEmpty()) return;

            Map<String, Restaurant> kept = new LinkedHashMap<>();
            List<Restaurant> toRemove = new ArrayList<>();

            for (Restaurant r : all) {
                if (r.getName() == null || r.getName().isBlank()) {
                    toRemove.add(r);
                    continue;
                }
                String norm = normalizeName(r.getName());
                if (norm.length() < 2) {
                    norm = r.getName().toLowerCase().replaceAll("[^a-z0-9]", "");
                }

                if (!kept.containsKey(norm)) {
                    kept.put(norm, r);
                } else {
                    Restaurant existing = kept.get(norm);
                    int existingMenuCount = existing.getMenuItems() != null ? existing.getMenuItems().size() : 0;
                    int currentMenuCount = r.getMenuItems() != null ? r.getMenuItems().size() : 0;

                    if (currentMenuCount > existingMenuCount) {
                        toRemove.add(existing);
                        kept.put(norm, r);
                    } else {
                        toRemove.add(r);
                    }
                }
            }

            if (!toRemove.isEmpty()) {
                log.info("Purging {} duplicate restaurants from database to ensure pristine catalogue", toRemove.size());
                for (Restaurant dup : toRemove) {
                    try {
                        restaurantRepository.delete(dup);
                    } catch (Exception ex) {
                        log.warn("Could not delete duplicate id={}: {}", dup.getId(), ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error running purgeDuplicateRestaurantsInDatabase: {}", e.getMessage());
        }
    }

    /**
     * Fetches real restaurants near (lat, lng).
     * Tries live OpenStreetMap Overpass (nodes & ways) first, and complements with real famous iconic brands.
     */
    @Transactional
    public List<Restaurant> fetchAndSeedRealRestaurants(double lat, double lng, double radiusMeters) {
        log.info("Syncing real nearby restaurants near lat={}, lng={}, radius={}m", lat, lng, radiusMeters);

        // 1. Clean any existing duplicates in the DB first
        purgeDuplicateRestaurantsInDatabase();

        List<Restaurant> newlySeeded = new ArrayList<>();
        List<Restaurant> existingList = new ArrayList<>(restaurantRepository.findAll());

        // 2. Fetch from OpenStreetMap Overpass with broad query (nodes + ways)
        double safeRadius = Math.max(1500.0, Math.min(radiusMeters, 15000.0));
        String query = String.format(
                Locale.US,
                "[out:json][timeout:25];(" +
                "nw[\"amenity\"~\"restaurant|fast_food|cafe|food_court|ice_cream|bakery\"](around:%d,%.6f,%.6f);" +
                "nw[\"cuisine\"](around:%d,%.6f,%.6f);" +
                ");out center 50;",
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
                }
            } catch (Exception e) {
                log.warn("Overpass endpoint {} unavailable: {}. Trying next...", endpoint, e.getMessage());
            }
        }

        // Parse OSM elements if available
        if (responseBody != null) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode elements = root.path("elements");

                if (elements.isArray()) {
                    for (JsonNode node : elements) {
                        JsonNode tags = node.path("tags");
                        String name = tags.path("name").asText(null);
                        if (name == null || name.trim().length() < 2) continue;
                        name = name.trim();

                        double rLat = node.hasNonNull("lat") ? node.path("lat").asDouble() :
                                (node.has("center") && node.path("center").hasNonNull("lat") ? node.path("center").path("lat").asDouble() : 0.0);
                        double rLon = node.hasNonNull("lon") ? node.path("lon").asDouble() :
                                (node.has("center") && node.path("center").hasNonNull("lon") ? node.path("center").path("lon").asDouble() : 0.0);
                        if (rLat == 0.0 || rLon == 0.0) continue;

                        final String normCandidate = normalizeName(name);
                        final String checkName = name;
                        final double checkLat = rLat;
                        final double checkLon = rLon;

                        boolean alreadyExists = existingList.stream().anyMatch(existing -> {
                            if (existing.getName() == null) return false;
                            String existingNorm = normalizeName(existing.getName());
                            boolean nameMatches = existing.getName().equalsIgnoreCase(checkName)
                                    || (!normCandidate.isEmpty() && normCandidate.equals(existingNorm));
                            boolean coordMatches = existing.getLatitude() != null && Math.abs(existing.getLatitude() - checkLat) < 0.0008
                                    && existing.getLongitude() != null && Math.abs(existing.getLongitude() - checkLon) < 0.0008;
                            return nameMatches || coordMatches;
                        });

                        if (alreadyExists) continue;

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
                        if (fullAddress.endsWith(",")) fullAddress = fullAddress.substring(0, fullAddress.length() - 1);
                        if (fullAddress.isBlank()) fullAddress = "Near your current location";

                        String phone = tags.path("phone").asText(tags.path("contact:phone").asText("+91 " + (9800000000L + random.nextInt(199999999))));
                        double rating = 4.0 + (random.nextInt(9) / 10.0);
                        int deliveryTime = 20 + random.nextInt(20);
                        BigDecimal minOrder = BigDecimal.valueOf(99 + (random.nextInt(3) * 50));

                        Restaurant restaurant = Restaurant.builder()
                                .name(name)
                                .description("Authentic " + cuisineType + " prepared fresh with premium quality ingredients.")
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
                                .imageUrl(getImageForCuisine(cuisineType, name))
                                .build();

                        Restaurant saved = restaurantRepository.save(restaurant);
                        seedRichMenu(saved, cuisineType, name);
                        newlySeeded.add(saved);
                        existingList.add(saved);
                    }
                }
            } catch (Exception e) {
                log.error("Error parsing OSM response: {}", e.getMessage(), e);
            }
        }

        // 3. Complement with rich real iconic restaurants around user's location
        // Ensures user always gets 25+ real top-rated restaurants with full diverse menus
        List<Restaurant> iconicSeeded = seedIconicRealRestaurants(lat, lng, existingList);
        newlySeeded.addAll(iconicSeeded);

        log.info("Total real restaurants seeded in this cycle: {}", newlySeeded.size());
        return newlySeeded;
    }

    /**
     * Seeds top iconic real brand restaurants centered around (lat, lng) with small offsets.
     */
    private List<Restaurant> seedIconicRealRestaurants(double lat, double lng, List<Restaurant> existingList) {
        record IconicBrand(String name, String cuisine, String description, String address, double latOff, double lngOff, int deliveryMin, double rating) {}

        List<IconicBrand> brands = List.of(
                new IconicBrand("Domino's Pizza", "Pizza, Fast Food, Italian, Pasta", "World famous hand-tossed crusts, cheesy garlic breads, pastas and choco lava cakes.", "Sector 4, Main High Street", 0.0031, 0.0025, 25, 4.5),
                new IconicBrand("KFC - Kentucky Fried Chicken", "Burgers, Fast Food, Chicken, Rolls", "Original recipe crispy chicken, spicy zinger burgers, twister rolls and hot wings.", "Metro Walk Plaza, Ground Floor", -0.0028, 0.0035, 20, 4.4),
                new IconicBrand("Behrouz Biryani", "Biryani, Mughlai, North Indian, Shorba", "The Royal Awadhi Biryani slow cooked in handis with saffron, royal shorba and spices.", "Imperial Heritage Complex", 0.0042, -0.0021, 30, 4.7),
                new IconicBrand("McDonald's", "Burgers, Fast Food, American, Wraps", "Classic Big Mac, McAloo Tikki, grilled wraps, golden french fries and creamy McFlurry.", "Central Galleria Mall", 0.0019, 0.0048, 22, 4.3),
                new IconicBrand("Burger King", "Burgers, Fast Food, American", "Flame-grilled Whoppers, crunchy onion rings and thick milkshakes.", "Crossroads Junction", -0.0035, -0.0032, 25, 4.2),
                new IconicBrand("Subway", "Healthy, Sandwiches, Salads, Soups", "Fresh customizable 6-inch and footlong submarine sandwiches, salads and warm broths.", "Business Tower, Block B", 0.0025, -0.0045, 18, 4.4),
                new IconicBrand("Pizza Hut", "Pizza, Italian, Fast Food, Pasta", "Signature pan pizzas, cheesy stuffed crusts, baked pastas and garlic bread.", "City Center Promenade", -0.0041, 0.0018, 28, 4.3),
                new IconicBrand("Biryani by Kilo", "Biryani, Kebabs, Mughlai, Tandoori", "Authentic charcoal dum biryani delivered in authentic clay handis with kebabs.", "Heritage Enclave, 1st Floor", 0.0052, 0.0031, 35, 4.6),
                new IconicBrand("Starbucks Coffee", "Cafe, Coffee, Bakery, Desserts", "Signature espresso roasts, iced caramel macchiatos and butter croissants.", "Prestige Tech Park Hub", -0.0018, -0.0028, 15, 4.8),
                new IconicBrand("Haldiram's", "North Indian, Pure Veg, Street Food, Chaat, Thalis", "Famous Chole Bhature, Raj Kachori, Pav Bhaji, thalis and traditional mithai.", "Market Yard, Main Square", 0.0038, -0.0015, 25, 4.5),
                new IconicBrand("Wow! Momo", "Chinese, Momos, Asian, Soups", "Steamed, pan-fried and moburg momos, hot thukpa and spicy Darjeeling dips.", "Transit Hub, Food Court", -0.0022, 0.0041, 20, 4.3),
                new IconicBrand("Theobroma", "Bakery, Desserts, Cafe", "Legendary overloaded chocolate brownies, cheesecakes and artisanal cookies.", "Boutique Lane, Metro Gate 1", 0.0015, -0.0038, 20, 4.8),
                new IconicBrand("Mainland China", "Chinese, Pan-Asian, Soups, Dim Sum", "Fine dining Asian cuisine, hot & sour soups, wok-tossed noodles and crispy chili chicken.", "Orchid Plaza, Level 2", -0.0051, -0.0022, 32, 4.6),
                new IconicBrand("Sagar Ratna", "South Indian, Pure Veg, Thalis, Soups", "Crispy butter masala dosas, fluffy idlis, tomato rasam and South Indian filter coffee.", "Temple Road, Near Park", 0.0029, 0.0051, 22, 4.5),
                new IconicBrand("Meghana Foods", "Biryani, Andhra, Spicy, South Indian", "Iconic fiery Andhra style chicken biryani and spicy paneer 65.", "Residency Road Extension", -0.0039, 0.0029, 28, 4.7),
                new IconicBrand("Cafe Coffee Day", "Cafe, Coffee, Snacks, Desserts", "Devil's own cold coffee, hot cappuccino and crunchy garlic baguettes.", "Boulevard Square", 0.0045, -0.0039, 18, 4.1),
                new IconicBrand("Chai Point", "Beverages, Tea, Snacks, Street Food", "Freshly brewed ginger cardamom chai, bun maska and hot samosas.", "Tech Park Boulevard", -0.0012, 0.0022, 15, 4.4),
                new IconicBrand("La Pino'z Pizza", "Pizza, Fast Food, Italian, Pasta", "Giant monster slices, loaded cheesy pizzas, pasta and garlic breadsticks.", "Greenfield Avenue", 0.0034, 0.0012, 26, 4.2),
                new IconicBrand("Baskin Robbins", "Desserts, Ice Cream, Shakes", "31 premium ice cream flavors, waffle cones and thick sundaes.", "Sunset Boulevard, Corner", -0.0025, -0.0041, 15, 4.6),
                new IconicBrand("The Belgian Waffle Co.", "Waffles, Desserts, Shakes", "Crispy warm Belgian waffles loaded with melted Belgian chocolate.", "Market Central, Stall 4", 0.0018, 0.0032, 20, 4.7),
                new IconicBrand("Naturals Ice Cream", "Ice Cream, Desserts", "100% natural fruit ice creams including tender coconut and alfonso mango.", "Garden City Walk", -0.0045, -0.0018, 15, 4.8),
                new IconicBrand("Barbeque Nation", "Barbeque, North Indian, Kebabs, Tandoori", "Smoky grilled tandoori skewers, spiced paneer tikkas and grand buffet mains.", "Skyview Towers, 3rd Floor", 0.0061, 0.0022, 35, 4.5),
                new IconicBrand("Bikanervala", "Street Food, North Indian, Sweets, Chaat, Thalis", "Authentic street chaat, pani puri, dal makhani, royal thalis and hot gulab jamun.", "Heritage Chowk", -0.0031, 0.0052, 24, 4.4),
                new IconicBrand("Third Wave Coffee", "Cafe, Coffee, Sourdough, Bakery", "Specialty coffee roasters, pour-overs, sea salt mochas and avocado toasts.", "Indiranagar 100ft Road", 0.0021, -0.0029, 20, 4.7),
                new IconicBrand("Tossin Pizza", "Pizza, Italian, Gourmet, Pasta, Soups", "Gourmet thin-crust wood-fired pizzas with premium toppings, Italian soups and olive oil.", "Elite Arcade, Phase 1", -0.0048, 0.0038, 30, 4.5),
                new IconicBrand("EatFit", "Healthy, Bowls, Indian, Thalis", "Healthy low-calorie thalis, grain bowls, dal khichdi and high-protein salads.", "Wellness Plaza", 0.0012, 0.0044, 20, 4.4),
                new IconicBrand("A2B - Adyar Ananda Bhavan", "South Indian, Sweets, Pure Veg, Thalis", "Traditional Ghee roast dosas, authentic Sambar, grand thalis and royal Mysore Pak.", "Jayanagar Circle", -0.0034, -0.0048, 22, 4.6),
                new IconicBrand("Rolls Mania", "Rolls, Fast Food, Street Food, Wraps", "Hot kathi rolls stuffed with spicy chicken tikka, paneer and eggs.", "Station Road Corner", 0.0049, -0.0012, 18, 4.3),
                new IconicBrand("Faasos - Signature Wraps & Rolls", "Rolls, Wraps, Fast Food, Street Food", "Indulgent loaded kathi rolls, signature wraps, smoky chicken tikka rolls and melting cheese wraps.", "High Street Commercial Hub", -0.0023, 0.0015, 20, 4.5),
                new IconicBrand("Tibbs Frankie - Bombay Frankie", "Rolls, Street Food, Fast Food, Frankie", "The original Bombay Frankie, spicy chicken, mutton and paneer rolls with signature masala.", "Central Promenade Arcade", 0.0033, 0.0037, 18, 4.4),
                new IconicBrand("Kolkata Kathi Rolls & Kababs", "Rolls, Mughlai, Street Food, Kebabs", "Authentic flaky paratha rolls, double egg chicken kathi rolls and spicy mutton roomali wraps.", "Sector 18 Market", -0.0042, 0.0033, 19, 4.4),
                new IconicBrand("Soup Bowl & Broth Co.", "Soups, Healthy, Salads, Continental", "Steaming artisan soups, chicken bone broths, wild mushroom veloute and fresh sourdough croutons.", "Greenwood Plaza, Suite 10", 0.0035, -0.0027, 24, 4.6),
                new IconicBrand("The Noodle & Soup Bar", "Chinese, Asian, Soups, Momos, Noodles", "Authentic steaming hot Chinese soups, wonton broths, ramen noodles and dim sums.", "Cyber Hub, Level 1", 0.0014, 0.0052, 25, 4.5),
                new IconicBrand("Beijing Bites", "Chinese, Asian, Noodles, Soups, Momos", "Crispy honey chili potatoes, Schezwan fried rice, hot soups, momos and Hakka noodles.", "Food Court Level 1", -0.0019, 0.0055, 25, 4.2),
                new IconicBrand("Paradise Biryani", "Biryani, Mughlai, Hyderabadi, Tandoori", "World-renowned Hyderabadi mutton and chicken dum biryani with mirchi ka salan.", "Royal Heritage Lane", 0.0055, -0.0035, 30, 4.6)
        );

        List<Restaurant> created = new ArrayList<>();
        for (IconicBrand brand : brands) {
            String normBrand = normalizeName(brand.name());
            boolean exists = existingList.stream().anyMatch(r -> {
                if (r.getName() == null) return false;
                String existingNorm = normalizeName(r.getName());
                return r.getName().equalsIgnoreCase(brand.name())
                        || (!normBrand.isEmpty() && normBrand.equals(existingNorm));
            });

            if (exists) continue;

            Restaurant r = Restaurant.builder()
                    .name(brand.name())
                    .description(brand.description())
                    .cuisineType(brand.cuisine())
                    .address(brand.address())
                    .phone("+91 " + (9800000000L + random.nextInt(199999999)))
                    .latitude(lat + brand.latOff())
                    .longitude(lng + brand.lngOff())
                    .rating(brand.rating())
                    .totalRatings(250 + random.nextInt(850))
                    .avgDeliveryTime(brand.deliveryMin())
                    .minOrderAmount(BigDecimal.valueOf(99 + random.nextInt(3) * 50))
                    .isOpen(true)
                    .imageUrl(getImageForCuisine(brand.cuisine(), brand.name()))
                    .build();

            Restaurant saved = restaurantRepository.save(r);
            seedRichMenu(saved, brand.cuisine(), brand.name());
            created.add(saved);
            existingList.add(saved);
        }

        return created;
    }

    /**
     * Enriches existing restaurant menus if they have fewer than 14 items.
     */
    @Transactional
    public void enrichRestaurantMenuIfSparse(Long restaurantId) {
        restaurantRepository.findById(restaurantId).ifPresent(r -> {
            List<MenuItem> items = menuItemRepository.findByRestaurantId(restaurantId);
            if (items.size() < 14) {
                log.info("Enriching sparse menu for restaurant '{}' (current count: {})", r.getName(), items.size());
                seedRichMenu(r, r.getCuisineType(), r.getName());
            }
        });
    }

    /**
     * Seeds mouth-watering, categorized dishes for each restaurant with smart de-duplication.
     */
    private void seedRichMenu(Restaurant r, String cuisine, String name) {
        String c = (cuisine != null ? cuisine : "").toLowerCase();
        String n = (name != null ? name : "").toLowerCase();
        List<MenuItem> items = new ArrayList<>();

        if (n.contains("roll") || c.contains("roll") || n.contains("wrap") || n.contains("faasos") || n.contains("frankie") || n.contains("kathi")) {
            // Dedicated Rolls & Wraps Specialty Menu (18 items)
            items.add(menuItem(r, "Classic Double Egg Chicken Kathi Roll", 189, "Rolls & Wraps", false, "Flaky paratha layered with two farm-fresh eggs, filled with tender spiced chicken tikka, onions and mint chutney.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Paneer Tikka Makhani Kathi Roll", 169, "Rolls & Wraps", true, "Crispy paratha stuffed with clay-oven roasted cottage cheese, capsicum and rich makhani sauce.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Mutton Seekh Kebab Roomali Roll", 229, "Rolls & Wraps", false, "Juicy spiced minced mutton seekh wrapped in thin soft roomali roti with pickled ring onions.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Cheesy Peri-Peri Chicken Frankie", 179, "Rolls & Wraps", false, "Spicy grilled chicken tossed in fiery peri-peri seasoning and loaded with molten cheddar cheese.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Butter Chicken Roomali Roll", 199, "Rolls & Wraps", false, "Shredded boneless chicken dunked in creamy butter gravy wrapped in delicate handkerchief bread.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Crispy Corn & Melted Cheese Frankie", 149, "Rolls & Wraps", true, "Crunchy sweet corn kernels tossed with spicy herbs, green chillies and gooey molten cheese.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Chatpata Aloo Masala Kathi Roll", 119, "Rolls & Wraps", true, "Spiced golden potato patty mashed with onions, chaat masala, tangy tamarind and mint chutney.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "BBQ Smoked Chicken Melt Wrap", 199, "Rolls & Wraps", false, "Hickory-smoked chicken chunks smothered in smoky barbecue glaze and melted mozzarella.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Spicy Falafel & Garlic Hummus Wrap", 159, "Rolls & Wraps", true, "Golden chickpea falafels with velvety garlic hummus, pickled cucumbers and tahini dressing.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Fiery Schezwan Egg & Veggies Roll", 139, "Rolls & Wraps", false, "Omelette rolled with crunchy cabbage, bell peppers and pungent Schezwan chili paste.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));

            items.add(menuItem(r, "Hot & Sour Shredded Chicken Soup", 149, "Soups", false, "Spicy peppery broth packed with shredded chicken, black mushrooms and bamboo shoots.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Comforting Cream of Tomato Soup", 119, "Soups", true, "Silky slow-cooked vine tomato soup with crunchy herb butter croutons.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Sweet Corn Veg Soup", 129, "Soups", true, "Wholesome sweet corn soup with diced vegetables and cracked black pepper.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Peri-Peri Crinkle Fries (Large)", 119, "Sides & Fries", true, "Deep-fried crinkle cut potatoes dusted generously with African bird's eye chili spices.", "https://images.unsplash.com/photo-1576107232684-1279f3908594?w=500"));
            items.add(menuItem(r, "Crispy Chicken Popcorn Bites", 149, "Sides & Fries", false, "Bite-sized seasoned chicken nuggets served with garlic mayo dip.", "https://images.unsplash.com/photo-1562967914-608f82629710?w=500"));

            items.add(menuItem(r, "Fresh Lemon Mint Cooler", 79, "Beverages & Desserts", true, "Chilled sparkling soda with muddled fresh garden mint and zesty lime juice.", "https://images.unsplash.com/photo-1513558161293-cdaf765ed2fd?w=500"));
            items.add(menuItem(r, "Masala Chaas Buttermilk", 49, "Beverages & Desserts", true, "Refreshing churned curd flavored with roasted cumin seeds, black salt and cilantro.", "https://images.unsplash.com/photo-1572490122747-3968b75cc699?w=500"));
            items.add(menuItem(r, "Warm Choco Lava Cake Cup", 99, "Beverages & Desserts", true, "Steaming chocolate sponge cake with an irresistible warm melted molten core.", "https://images.unsplash.com/photo-1606313564200-e75d5e30476c?w=500"));

        } else if (n.contains("soup") || c.contains("soup") || n.contains("broth")) {
            // Dedicated Gourmet Soups & Healthy Broths (18 items)
            items.add(menuItem(r, "Classic Veg Manchow Soup with Fried Noodles", 149, "Soups", true, "Zesty dark soy garlic broth loaded with finely chopped vegetables and crowned with crispy fried noodles.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Hot & Sour Shredded Chicken Soup", 169, "Soups", false, "Authentic spicy and sour soup with tender chicken strips, wood ear mushrooms and egg drop ribbons.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Cream of Wild Mushroom & Thyme", 179, "Soups", true, "Earthy forest mushrooms sautéed with fresh thyme and slow simmered in rich creamy velouté.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Roasted Plum Tomato & Sweet Basil Bisque", 159, "Soups", true, "Fire-roasted Roma tomatoes blended with fresh Genovese basil and a dash of heavy cream.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Sweet Corn Velvet Chicken Soup", 169, "Soups", false, "Comforting creamy sweet corn soup with succulent poached chicken and silky egg drops.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Lemon Coriander Clear Veg Broth", 139, "Soups", true, "Light and invigorating clear vegetable broth flavored with fresh lemon juice and fragrant cilantro.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Tuscan Minestrone Vegetable Broth", 159, "Soups", true, "Hearty Italian broth packed with zucchini, cannellini beans, pasta shells and Italian herbs.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Cantonese Steamed Chicken Wonton Soup", 189, "Soups", false, "Delicate handmade chicken wonton dumplings in clear ginger-scented scallion broth.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Fiery Thai Tom Yum Soup with Herbs", 179, "Soups", false, "Spicy and sour Thai broth infused with lemongrass, galangal, kaffir lime leaves and fresh chilies.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Velvety Broccoli & Cheddar Soup", 169, "Soups", true, "Tender broccoli florets pureed with sharp cheddar cheese and cream for ultimate warmth.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Mediterranean Herb Caesar Salad", 189, "Salads & Bowls", true, "Crisp romaine lettuce, cherry tomatoes, shaved parmesan and herb croutons tossed in Caesar dressing.", "https://images.unsplash.com/photo-1540420773420-3366772f4999?w=500"));
            items.add(menuItem(r, "Grilled Chicken & Quinoa Protein Bowl", 249, "Salads & Bowls", false, "Herb-marinated grilled chicken breast over fluffy quinoa, roasted sweet potatoes and baby greens.", "https://images.unsplash.com/photo-1540420773420-3366772f4999?w=500"));
            items.add(menuItem(r, "Greek Feta & Kalamata Garden Salad", 199, "Salads & Bowls", true, "Cucumber chunks, juicy bell peppers, red onions, Greek feta and black olives with oregano vinaigrette.", "https://images.unsplash.com/photo-1540420773420-3366772f4999?w=500"));

            items.add(menuItem(r, "Toasted Garlic Herb Sourdough (2 Pcs)", 89, "Sides & Breads", true, "Crunchy artisan sourdough slices smeared with roasted garlic herb butter.", "https://images.unsplash.com/photo-1573140247632-f8fd74997d5c?w=500"));
            items.add(menuItem(r, "Cheesy Whole Wheat Garlic Roll", 119, "Sides & Breads", true, "Warm whole wheat roll filled with melted cheese and fresh parsley.", "https://images.unsplash.com/photo-1573140247632-f8fd74997d5c?w=500"));

            items.add(menuItem(r, "Cold-Pressed Green Detox Juice", 129, "Healthy Drinks", true, "Freshly extracted cucumber, green apple, spinach, celery and ginger juice.", "https://images.unsplash.com/photo-1556679343-c7306c1976bc?w=500"));
            items.add(menuItem(r, "Lemon Honey Ginger Herbal Tea", 69, "Healthy Drinks", true, "Steeped organic green tea infused with wild mountain honey, lemon slices and crushed ginger.", "https://images.unsplash.com/photo-1576092768241-dec231879fc3?w=500"));

        } else if (n.contains("momo") || c.contains("chinese") || c.contains("asian") || n.contains("mainland") || n.contains("noodle") || n.contains("beijing")) {
            // Pan-Asian, Chinese, Momos & Soups (19 items)
            items.add(menuItem(r, "Authentic Veg Manchow Soup", 149, "Soups", true, "Pungent dark soy broth packed with fine vegetables and served with crunchy fried noodles.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Hot & Sour Chicken Clear Soup", 169, "Soups", false, "Spicy and tangy broth with tender shredded chicken, egg drops and fresh green chilies.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Creamy Sweet Corn Veg Soup", 139, "Soups", true, "Silky smooth sweet corn soup studded with tender corn kernels and white pepper.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Lemon Coriander Chicken Soup", 159, "Soups", false, "Light citrusy broth with shredded chicken, fresh coriander leaves and lemon zest.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Steamed Darjeeling Chicken Momos (6 Pcs)", 169, "Momos & Dim Sum", false, "Thin wrapper dumplings stuffed with juicy minced chicken, ginger and cilantro.", "https://images.unsplash.com/photo-1534422298391-e4f8c172dddb?w=500"));
            items.add(menuItem(r, "Pan-Fried Schezwan Paneer Momos (6 Pcs)", 159, "Momos & Dim Sum", true, "Crispy bottom dumplings filled with spicy cottage cheese and tossed in fiery Schezwan glaze.", "https://images.unsplash.com/photo-1534422298391-e4f8c172dddb?w=500"));
            items.add(menuItem(r, "Crispy Fried Cheese & Corn Momos (6 Pcs)", 149, "Momos & Dim Sum", true, "Golden deep-fried momos bursting with molten mozzarella and sweet corn kernels.", "https://images.unsplash.com/photo-1534422298391-e4f8c172dddb?w=500"));
            items.add(menuItem(r, "Crystal Veg Dim Sums (4 Pcs)", 189, "Momos & Dim Sum", true, "Translucent steamed starch dumplings filled with water chestnuts, carrots and mushrooms.", "https://images.unsplash.com/photo-1563245372-f21724e3856d?w=500"));
            items.add(menuItem(r, "Fiery Schezwan Chicken Momos (6 Pcs)", 189, "Momos & Dim Sum", false, "Steamed chicken momos tossed in wok with spicy garlic Schezwan gravy and spring onions.", "https://images.unsplash.com/photo-1534422298391-e4f8c172dddb?w=500"));

            items.add(menuItem(r, "Crispy Golden Spring Rolls (4 Pcs)", 139, "Rolls & Starters", true, "Crispy fried pastry wrappers stuffed with wok-tossed cabbage, carrots and glass noodles.", "https://images.unsplash.com/photo-1544025162-d76694265947?w=500"));
            items.add(menuItem(r, "Chicken Dragon Spring Rolls (4 Pcs)", 179, "Rolls & Starters", false, "Spicy chicken wrapped rolls fried crispy and drizzled with hot dragon chili dip.", "https://images.unsplash.com/photo-1544025162-d76694265947?w=500"));
            items.add(menuItem(r, "Crispy Honey Chili Potato", 149, "Rolls & Starters", true, "Crispy fried potato fingers coated in a sticky sesame honey chili sauce.", "https://images.unsplash.com/photo-1525755662778-989d0524087e?w=500"));
            items.add(menuItem(r, "Chicken Manchurian Gravy", 239, "Rolls & Starters", false, "Minced chicken balls fried and simmered in a dark savory soy-garlic-ginger sauce.", "https://images.unsplash.com/photo-1525755662778-989d0524087e?w=500"));
            items.add(menuItem(r, "Chili Paneer Dry (Indo-Chinese)", 219, "Rolls & Starters", true, "Batter-fried paneer cubes tossed with onion petals, green chilies and dark soy sauce.", "https://images.unsplash.com/photo-1567188040759-fb8a883dc6d8?w=500"));

            items.add(menuItem(r, "Classic Veg Hakka Noodles", 179, "Noodles & Rice", true, "Wok-tossed noodles with shredded cabbage, bell peppers, carrots and spring onions.", "https://images.unsplash.com/photo-1585032226651-759b368d7246?w=500"));
            items.add(menuItem(r, "Spicy Schezwan Chicken Noodles", 219, "Noodles & Rice", false, "Flame wok noodles tossed with egg ribbons, shredded chicken and fiery homemade Schezwan sauce.", "https://images.unsplash.com/photo-1585032226651-759b368d7246?w=500"));
            items.add(menuItem(r, "Burnt Garlic Egg & Chicken Fried Rice", 229, "Noodles & Rice", false, "Fragrant jasmine rice tossed in intense roasted garlic oil, spring onion and scrambled eggs.", "https://images.unsplash.com/photo-1603133872878-684f208fb84b?w=500"));
            items.add(menuItem(r, "Chili Garlic Veg Fried Rice", 189, "Noodles & Rice", true, "Spicy wok rice tossed with diced carrots, french beans and pungent chili garlic paste.", "https://images.unsplash.com/photo-1603133872878-684f208fb84b?w=500"));

            items.add(menuItem(r, "Darsaan with Vanilla Ice Cream", 129, "Desserts & Drinks", true, "Crispy honey-glazed fried flat noodles tossed in toasted sesame seeds served with vanilla ice cream.", "https://images.unsplash.com/photo-1551024709-8f23befc6f87?w=500"));

        } else if (n.contains("pizza") || c.contains("pizza") || n.contains("domino") || c.contains("italian") || c.contains("pasta") || n.contains("tossin")) {
            // Pizza, Italian, Pastas & Soups (19 items)
            items.add(menuItem(r, "Roasted Tomato & Sweet Basil Soup", 149, "Soups", true, "Slow-roasted Italian plum tomatoes simmered with extra virgin olive oil and sweet basil leaves.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Cream of Wild Mushroom Soup", 169, "Soups", true, "Rich velvety mushroom soup infused with Italian herbs and garlic croutons.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Italian Minestrone Vegetable Broth", 159, "Soups", true, "Hearty clear broth with diced zucchini, beans, carrots, pasta and fresh oregano.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Margherita Basil Supreme", 249, "Pizza", true, "Classic hand-stretched crust with rich San Marzano tomato sauce, fresh mozzarella and sweet basil leaves.", "https://images.unsplash.com/photo-1604382354936-07c5d9983bd3?w=500"));
            items.add(menuItem(r, "Farmhouse Garden Delight", 329, "Pizza", true, "Delightful medley of crisp green capsicum, sweet corn, sliced mushrooms and red paprika on melted mozzarella.", "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=500"));
            items.add(menuItem(r, "Peppy Paneer Tikka Pizza", 369, "Pizza", true, "Spicy marinated paneer cubes, crisp capsicum and fiery red paprika layered over rich cheese sauce.", "https://images.unsplash.com/photo-1574071318508-1cdbab80d002?w=500"));
            items.add(menuItem(r, "Classic Pepperoni Feast", 449, "Pizza", false, "Loaded with authentic seasoned pepperoni slices and gooey mozzarella cheese on a crispy crust.", "https://images.unsplash.com/photo-1628840042765-356cda07504e?w=500"));
            items.add(menuItem(r, "Chicken Golden Delight", 419, "Pizza", false, "Barbeque chicken chunks, golden sweet corn and extra melted mozzarella on a garlic-infused crust.", "https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=500"));
            items.add(menuItem(r, "Spicy Triple Chicken Supreme", 469, "Pizza", false, "Tender peri-peri chicken, spicy hot chicken meatballs and grilled chicken tikka with herbs.", "https://images.unsplash.com/photo-1594007654729-407eedc4be65?w=500"));
            items.add(menuItem(r, "Cheesy 7-Cheese Overload", 399, "Pizza", true, "An extravagant blend of Cheddar, Mozzarella, Gouda, Colby, Monterey Jack and Parmesan.", "https://images.unsplash.com/photo-1571407970349-bc81e7e96d47?w=500"));

            items.add(menuItem(r, "Creamy White Sauce Penne Alfredo", 249, "Pasta & Rolls", true, "Penne tossed in velvety butter garlic cream sauce with sautéed mushrooms and Italian herbs.", "https://images.unsplash.com/photo-1621996346565-e3d5d6281744?w=500"));
            items.add(menuItem(r, "Spicy Red Sauce Penne Arrabbiata", 239, "Pasta & Rolls", true, "Penne tossed in a fiery garlic chili tomato marinara sauce topped with parmesan cheese.", "https://images.unsplash.com/photo-1621996346565-e3d5d6281744?w=500"));
            items.add(menuItem(r, "Grilled Chicken Caesar Wrap", 179, "Pasta & Rolls", false, "Herb-grilled chicken, romaine lettuce, parmesan cheese and garlic mayo in a toasted wrap.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Cheesy Stuffed Garlic Breadsticks", 159, "Pasta & Rolls", true, "Freshly baked warm garlic bread loaded with melted mozzarella, cheddar and sweet corn kernels.", "https://images.unsplash.com/photo-1573140247632-f8fd74997d5c?w=500"));

            items.add(menuItem(r, "Fiery Peri-Peri Chicken Wings (6 Pcs)", 269, "Sides & Desserts", false, "Juicy crispy roasted chicken wings coated in African bird's eye chili glaze.", "https://images.unsplash.com/photo-1567620832903-9fc6debc209f?w=500"));
            items.add(menuItem(r, "Crinkle Cut Salted French Fries", 119, "Sides & Desserts", true, "Deep-fried golden potato crinkles tossed in sea salt served with cheese mayo dip.", "https://images.unsplash.com/photo-1576107232684-1279f3908594?w=500"));
            items.add(menuItem(r, "Molten Choco Lava Cake", 129, "Sides & Desserts", true, "Decadent warm chocolate sponge with a gooey melted Belgian fudge core.", "https://images.unsplash.com/photo-1606313564200-e75d5e30476c?w=500"));

            items.add(menuItem(r, "Chilled Coca-Cola (330ml Can)", 59, "Beverages", true, "Ice-cold refreshing Coca-Cola can served chilled.", "https://images.unsplash.com/photo-1622483767028-3f66f32aef97?w=500"));
            items.add(menuItem(r, "Fresh Mint Virgin Mojito", 119, "Beverages", true, "Sparkling soda muddled with fresh mint sprigs, lime wedges and cane sugar.", "https://images.unsplash.com/photo-1513558161293-cdaf765ed2fd?w=500"));

        } else if (n.contains("biryani") || c.contains("biryani") || n.contains("behrouz") || c.contains("mughlai") || n.contains("paradise") || n.contains("barbeque")) {
            // Biryani, Kebabs, Rolls & Shorba (19 items)
            items.add(menuItem(r, "Royal Mutton Yakhni Dum Shorba", 189, "Soups & Shorba", false, "Rich Awadhi bone broth slow-simmered with aromatic whole spices, black pepper and saffron.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Murgh Kali Mirch Chicken Shorba", 159, "Soups & Shorba", false, "Velvety spiced chicken broth tempered with freshly cracked black peppercorns and coriander.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Tamatar Dhaniya Shorba", 129, "Soups & Shorba", true, "Traditional Indian tomato soup spiced with roasted cumin, ginger and fresh coriander roots.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Kolkata Double Egg Chicken Kathi Roll", 179, "Rolls & Kebabs", false, "Crispy paratha lined with double egg, stuffed with juicy spiced chicken boti and onions.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Mutton Seekh Kebab Roomali Roll", 219, "Rolls & Kebabs", false, "Melt-in-mouth mutton seekh kebab wrapped in handkerchief roomali roti with mint chutney.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Tandoori Paneer Tikka Roomali Roll", 159, "Rolls & Kebabs", true, "Charred spicy cottage cheese cubes wrapped in delicate roomali roti with pickled ring onions.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Tandoori Chicken Full (8 Pcs)", 399, "Rolls & Kebabs", false, "Full whole chicken marinated in Kashmiri red chili, yogurt and ginger-garlic, roasted in clay tandoor.", "https://images.unsplash.com/photo-1599488615731-7e5c2823ff28?w=500"));
            items.add(menuItem(r, "Murgh Malai Kebab (6 Pcs)", 279, "Rolls & Kebabs", false, "Velvety chicken chunks marinated in cream, cheddar cheese, cardamom and grilled over hot coals.", "https://images.unsplash.com/photo-1603894584373-5ac82b2ae398?w=500"));
            items.add(menuItem(r, "Paneer Tikka Angara (6 Pcs)", 249, "Rolls & Kebabs", true, "Spicy marinated cottage cheese cubes and bell peppers charred to perfection in tandoor.", "https://images.unsplash.com/photo-1567188040759-fb8a883dc6d8?w=500"));

            items.add(menuItem(r, "Royal Dum Chicken Biryani (Handi)", 319, "Biryani", false, "Fragrant aged basmati rice slow-cooked on charcoal with marinated chicken, saffron and mint.", "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=500"));
            items.add(menuItem(r, "Shahi Mutton Dum Biryani", 429, "Biryani", false, "Tender baby goat pieces cooked in spiced yogurt gravy and layered with saffron-infused rice.", "https://images.unsplash.com/photo-1589302168068-964664d93dc0?w=500"));
            items.add(menuItem(r, "Lucknowi Veg Nawabi Biryani", 249, "Biryani", true, "Fresh carrots, green peas, cauliflower and paneer slow dum cooked with whole royal spices.", "https://images.unsplash.com/photo-1645177628172-a94c1f96e6db?w=500"));
            items.add(menuItem(r, "Hyderabadi Chicken Tikka Biryani", 349, "Biryani", false, "Smoky tandoor-roasted chicken tikkas layered over rich spicy Hyderabadi biryani rice.", "https://images.unsplash.com/photo-1633945274405-b6c8069047b0?w=500"));
            items.add(menuItem(r, "Paneer Makhani Dum Biryani", 269, "Biryani", true, "Marinated cottage cheese tossed in buttery makhani sauce and dum-cooked with basmati rice.", "https://images.unsplash.com/photo-1645177628172-a94c1f96e6db?w=500"));

            items.add(menuItem(r, "Butter Chicken Boneless", 339, "Curries & Breads", false, "Tender shredded chicken cooked in rich satin-smooth tomato gravy with cashew paste and butter.", "https://images.unsplash.com/photo-1603894584373-5ac82b2ae398?w=500"));
            items.add(menuItem(r, "Dal Makhani (Slow Simmered)", 229, "Curries & Breads", true, "Black lentils cooked overnight on slow tandoor heat with butter and fresh cream.", "https://images.unsplash.com/photo-1546833999-b9f581a1996d?w=500"));
            items.add(menuItem(r, "Garlic Butter Naan (2 Pcs)", 69, "Curries & Breads", true, "Traditional clay-oven baked bread topped with roasted minced garlic and melted butter.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));

            items.add(menuItem(r, "Royal Gulab Jamun (2 Pcs)", 89, "Desserts & Drinks", true, "Fried khoya balls soaked in warm rose and saffron sugar syrup.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));
            items.add(menuItem(r, "Kesariya Dry Fruit Lassi", 99, "Desserts & Drinks", true, "Thick traditional churned yogurt blended with saffron, almonds, pistachios and cardamom.", "https://images.unsplash.com/photo-1572490122747-3968b75cc699?w=500"));

        } else if (n.contains("burger") || c.contains("burger") || n.contains("kfc") || n.contains("mcdonald") || c.contains("fast_food")) {
            // Burgers, Fast Food, Wraps & Soups (18 items)
            items.add(menuItem(r, "Crispy Chicken Zinger Wrap", 179, "Rolls & Wraps", false, "Golden fried crispy chicken breast fillet wrapped in warm tortilla with spicy mayo and shredded lettuce.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Cheesy Spicy Paneer Frankie Wrap", 159, "Rolls & Wraps", true, "Crisp battered cottage cheese wrapped in tortilla with chipotle drizzle and melted cheddar.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Smoky BBQ Chicken Wrap", 189, "Rolls & Wraps", false, "Flame grilled chicken tossed in hickory BBQ sauce wrapped with crunchy onions.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));

            items.add(menuItem(r, "Hot Chicken & Sweet Corn Soup", 139, "Soups", false, "Comforting clear chicken broth with sweet corn kernels and cracked black pepper.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Creamy Tomato & Basil Herb Soup", 129, "Soups", true, "Smooth blended tomato soup served warm with crunchy croutons.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Signature Double Smash Cheeseburger", 219, "Burgers", false, "Two seasoned smashed grilled chicken patties, melted cheddar, gherkins and secret house sauce.", "https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=500"));
            items.add(menuItem(r, "Crispy Chicken Zinger Crunch", 199, "Burgers", false, "Deep fried crispy whole chicken breast fillet, shredded lettuce and creamy spicy mayo.", "https://images.unsplash.com/photo-1550547660-d9450f859349?w=500"));
            items.add(menuItem(r, "Spicy Paneer Tower Burger", 179, "Burgers", true, "Crusted cottage cheese steak marinated in cajun spices with jalapenos, crunchy slaw and cheddar.", "https://images.unsplash.com/photo-1550547660-d9450f859349?w=500"));
            items.add(menuItem(r, "Classic McAloo Tikki Delight", 99, "Burgers", true, "Crispy spiced potato and pea patty with crunchy red onions and creamy thousand island spread.", "https://images.unsplash.com/photo-1521305916504-4a1121188589?w=500"));
            items.add(menuItem(r, "Smoky BBQ Bacon & Chicken Burger", 269, "Burgers", false, "Juicy grilled patty topped with crispy strips, melted Monterey Jack and hickory BBQ glaze.", "https://images.unsplash.com/photo-1586190848861-99aa4a171e90?w=500"));
            items.add(menuItem(r, "Fiery Peri-Peri Crispy Burger", 209, "Burgers", false, "Battered golden chicken patty dusted with hot African bird's eye chili and peri-peri sauce.", "https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=500"));

            items.add(menuItem(r, "Hot & Crispy Chicken Bucket (4 Pcs)", 299, "Sides & Chicken", false, "Colonel's famous secret spice seasoned crunchy fried chicken with tender juicy meat.", "https://images.unsplash.com/photo-1626082927389-6cd097cdc6ec?w=500"));
            items.add(menuItem(r, "Crispy Chicken Popcorn (Large)", 179, "Sides & Chicken", false, "Bite-sized crunchy chicken poppers seasoned with garlic pepper herbs.", "https://images.unsplash.com/photo-1562967914-608f82629710?w=500"));
            items.add(menuItem(r, "Peri-Peri Crinkle Fries (Large)", 119, "Sides & Chicken", true, "Crispy crinkle-cut golden potatoes tossed in hot peri-peri spice dust.", "https://images.unsplash.com/photo-1576107232684-1279f3908594?w=500"));
            items.add(menuItem(r, "Cheesy Loaded Jalapeno Nachos", 159, "Sides & Chicken", true, "Stone-ground corn tortilla chips drenched in warm cheese sauce, salsa and jalapenos.", "https://images.unsplash.com/photo-1513456852971-30c0b8199d4d?w=500"));

            items.add(menuItem(r, "Thick Belgian Chocolate Shake", 159, "Beverages & Desserts", true, "Rich dark Belgian chocolate ganache blended with creamy soft vanilla ice cream.", "https://images.unsplash.com/photo-1572490122747-3968b75cc699?w=500"));
            items.add(menuItem(r, "Oreo Cookie Crumble Shake", 169, "Beverages & Desserts", true, "Crushed chocolate Oreo biscuits whipped with rich chocolate milk and fudge sauce.", "https://images.unsplash.com/photo-1579954115545-a95591f28bfc?w=500"));
            items.add(menuItem(r, "Hot Chocolate Fudge Sundae", 109, "Beverages & Desserts", true, "Two scoops of vanilla ice cream submerged in boiling hot chocolate fudge and roasted nuts.", "https://images.unsplash.com/photo-1563805042-7684c019e1cb?w=500"));

        } else if (n.contains("cafe") || c.contains("cafe") || n.contains("starbucks") || c.contains("coffee") || n.contains("theobroma") || n.contains("chai")) {
            // Cafe, Coffee, Bakery & Warm Soups (18 items)
            items.add(menuItem(r, "Roasted Tomato Basil Velouté", 149, "Soups", true, "Smooth French style tomato soup with sweet basil and extra virgin olive oil.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Cream of Forest Mushroom & Garlic", 169, "Soups", true, "Creamy blend of button and shiitake mushrooms with toasted sourdough croutons.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Grilled Paneer Tikka Wrap", 179, "Rolls & Sandwiches", true, "Spiced cottage cheese chunks, bell peppers and mint mayo grilled in a whole wheat wrap.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Smoked Chicken Jalapeno Wrap", 199, "Rolls & Sandwiches", false, "Hickory-smoked chicken breast, pickled jalapenos and melted cheese in a toasted wrap.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Grilled Mozzarella & Pesto Panini", 219, "Rolls & Sandwiches", true, "Artisanal sourdough stuffed with basil pesto, fresh buffalo mozzarella and sundried tomatoes.", "https://images.unsplash.com/photo-1528735602780-2552fd46c7af?w=500"));
            items.add(menuItem(r, "French Butter Croissant", 129, "Rolls & Sandwiches", true, "Authentic flaky, golden crescent pastry laminated with 100% Normandy butter.", "https://images.unsplash.com/photo-1555507036-ab1f4038808a?w=500"));

            items.add(menuItem(r, "Roasted Hazelnut Cappuccino", 179, "Coffee & Tea", true, "Rich double espresso poured over silky steamed whole milk with roasted hazelnut syrup.", "https://images.unsplash.com/photo-1534778101976-62847782c213?w=500"));
            items.add(menuItem(r, "Caramel Macchiato with Drizzle", 199, "Coffee & Tea", true, "Freshly steamed milk with vanilla-flavored syrup marked with espresso and caramel drizzle.", "https://images.unsplash.com/photo-1485808191679-5f86510681a2?w=500"));
            items.add(menuItem(r, "Signature Cold Brew Coffee", 189, "Coffee & Tea", true, "16-hour slow steeped specialty Arabica beans served black over crystal clear ice.", "https://images.unsplash.com/photo-1517701550927-30cf4ba1dba5?w=500"));
            items.add(menuItem(r, "Dark Mocha Frappuccino", 219, "Coffee & Tea", true, "Coffee blended with dark mocha sauce, cold milk and ice, topped with sweetened whipped cream.", "https://images.unsplash.com/photo-1572490122747-3968b75cc699?w=500"));
            items.add(menuItem(r, "Royal Ginger Elaichi Chai (Flask)", 89, "Coffee & Tea", true, "Slow-boiled Assam CTC tea brewed with fresh crushed ginger and green cardamom pods.", "https://images.unsplash.com/photo-1576092768241-dec231879fc3?w=500"));

            items.add(menuItem(r, "Theobroma Overload Brownie", 129, "Bakery & Desserts", true, "Dense, fudgy brownie packed with dark chocolate chunks and a crackly crust.", "https://images.unsplash.com/photo-1606313564200-e75d5e30476c?w=500"));
            items.add(menuItem(r, "New York Baked Cheesecake Slice", 229, "Bakery & Desserts", true, "Velvety smooth cream cheese filling baked over a buttery graham cracker crust.", "https://images.unsplash.com/photo-1533134242443-d4fd215305ad?w=500"));
            items.add(menuItem(r, "Blueberry Glazed Danish Pastry", 149, "Bakery & Desserts", true, "Puff pastry wheel filled with vanilla pastry cream and sweet wild blueberry compote.", "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=500"));
            items.add(menuItem(r, "Warm Choco Chip Chunk Cookie", 89, "Bakery & Desserts", true, "Crisp on the edges, soft and chewy center loaded with melted milk chocolate pockets.", "https://images.unsplash.com/photo-1499636136210-6f4ee915583e?w=500"));

        } else if (n.contains("south") || c.contains("south") || n.contains("sagar") || n.contains("a2b")) {
            // South Indian, Dosas, Thalis & Rasam (18 items)
            items.add(menuItem(r, "Hot Spicy Pepper Rasam Shorba", 69, "Soups & Rasam", true, "Tangy tamarind and crushed black pepper broth tempered with mustard, cumin and curry leaves.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Tomato Coriander South Indian Soup", 89, "Soups & Rasam", true, "Fresh country tomatoes crushed with garlic, curry leaves and fresh coriander.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Chettinad Spicy Paneer Kathi Roll", 149, "Rolls & Tiffins", true, "Crispy paratha filled with crushed black pepper Chettinad style cottage cheese and onions.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Special Mysore Masala Dosa", 139, "Rolls & Tiffins", true, "Crispy fermented rice-lentil crepe smeared with spicy red garlic chutney and spiced potato mash.", "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=500"));
            items.add(menuItem(r, "Ghee Roast Paper Plain Dosa", 129, "Rolls & Tiffins", true, "Ultra-thin golden crispy crepe roasted in pure desi ghee served with 3 chutneys and sambar.", "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=500"));
            items.add(menuItem(r, "Chettinad Spicy Paneer Dosa", 159, "Rolls & Tiffins", true, "Crisp dosa stuffed with fiery Chettinad-style crushed pepper and paneer masala.", "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=500"));
            items.add(menuItem(r, "Steamed Button Ghee Idli (14 Pcs)", 99, "Rolls & Tiffins", true, "Mini melt-in-the-mouth steamed rice cakes drenched in aromatic gun powder podi and ghee.", "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=500"));
            items.add(menuItem(r, "Crispy Medu Vada (2 Pcs)", 89, "Rolls & Tiffins", true, "Crispy outside and spongy inside deep-fried lentil donuts flavored with black pepper and curry leaves.", "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=500"));
            items.add(menuItem(r, "South Indian Onion Tomato Uttapam", 129, "Rolls & Tiffins", true, "Thick savory pancake topped with juicy chopped onions, tomatoes, green chilies and coriander.", "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=500"));

            items.add(menuItem(r, "Grand South Indian Royal Thali", 199, "Thalis & Rice", true, "Complete platter with poori, rice, sambar, rasam, kootu, poriyal, curd, appalam and sweet.", "https://images.unsplash.com/photo-1610057099443-fde8c4d50f91?w=500"));
            items.add(menuItem(r, "Traditional Bisi Bele Bath with Boondi", 129, "Thalis & Rice", true, "Hot lentil-rice mash cooked with tamarind, nutmeg, mixed vegetables, cashews and pure ghee.", "https://images.unsplash.com/photo-1610057099443-fde8c4d50f91?w=500"));
            items.add(menuItem(r, "Tadka Curd Rice with Pomegranate", 109, "Thalis & Rice", true, "Creamy tempered yogurt rice with mustard seeds, curry leaves, ginger and sweet pomegranate seeds.", "https://images.unsplash.com/photo-1610057099443-fde8c4d50f91?w=500"));

            items.add(menuItem(r, "Authentic Kumbakonam Filter Coffee", 49, "Beverages & Sweets", true, "Freshly brewed chicory coffee decoction frothed with boiling full-cream milk in a brass dabarah.", "https://images.unsplash.com/photo-1514432324607-a09d9b4aefdd?w=500"));
            items.add(menuItem(r, "Melt-in-Mouth Ghee Mysore Pak (2 Pcs)", 79, "Beverages & Sweets", true, "Traditional sweet made of roasted gram flour, sugar and swimming in pure aromatic ghee.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));
            items.add(menuItem(r, "Warm Badam Halwa (100g)", 99, "Beverages & Sweets", true, "Rich paste of blanched California almonds cooked with saffron, cardamom and ghee.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));

        } else {
            // General / Multi-Cuisine / North Indian / Street Food & Chaat (20 items)
            items.add(menuItem(r, "Classic Veg Manchow Soup with Noodles", 139, "Soups", true, "Savory garlic soy broth with diced vegetables and crispy fried noodles.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Hot & Sour Chicken Clear Soup", 159, "Soups", false, "Peppery chicken broth with chili vinegar punch and tender shredded chicken.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Cream of Tomato Soup with Crisp Croutons", 119, "Soups", true, "Slow cooked vine ripened tomato soup served with golden butter-toasted croutons.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));
            items.add(menuItem(r, "Sweet Corn Chicken Velvet Soup", 149, "Soups", false, "Creamy corn and chicken broth sprinkled with crushed white pepper.", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=500"));

            items.add(menuItem(r, "Double Egg Chicken Kathi Roll", 179, "Rolls & Wraps", false, "Crispy layered paratha wrapped around spicy chicken tikka, eggs and mint chutney.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Paneer Tikka Frankie Wrap", 159, "Rolls & Wraps", true, "Charred tandoori paneer wrapped in flaky flatbread with tangy spice blend.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));
            items.add(menuItem(r, "Mutton Seekh Kebab Roomali Roll", 209, "Rolls & Wraps", false, "Minced spiced mutton seekh wrapped with pickled onion rings in roomali roti.", "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=500"));

            items.add(menuItem(r, "Delhi Style Dahi Bhalla Chaat", 119, "Chaat & Starters", true, "Soft lentil dumplings soaked in sweet yogurt, topped with roasted cumin, mint & tamarind chutneys.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));
            items.add(menuItem(r, "Crispy Raj Kachori Royal Chaat", 139, "Chaat & Starters", true, "Giant crisp kachori shell stuffed with sprouted moong, potatoes, yogurt, sev and pomegranate.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));
            items.add(menuItem(r, "Tandoori Malai Paneer Tikka (6 Pcs)", 249, "Chaat & Starters", true, "Creamy skewered cottage cheese cubes with charred capsicum and onions.", "https://images.unsplash.com/photo-1567188040759-fb8a883dc6d8?w=500"));
            items.add(menuItem(r, "Golden Crispy Veg Spring Rolls (4 Pcs)", 139, "Chaat & Starters", true, "Delicate crispy pastry rolls stuffed with shredded vegetables and served with hot garlic dip.", "https://images.unsplash.com/photo-1544025162-d76694265947?w=500"));

            items.add(menuItem(r, "North Indian Deluxe Royal Thali", 249, "Main Course & Thalis", true, "Complete feast: Paneer butter masala, dal makhani, seasonal veg, jeera rice, 2 naans, raita, gulab jamun.", "https://images.unsplash.com/photo-1610057099443-fde8c4d50f91?w=500"));
            items.add(menuItem(r, "Classic Paneer Butter Masala", 239, "Main Course & Thalis", true, "Rich tomato, butter and cashew cream gravy cooked with tender cottage cheese cubes.", "https://images.unsplash.com/photo-1631452180519-c014fe946bc7?w=500"));
            items.add(menuItem(r, "Murgh Makhani (Butter Chicken Boneless)", 319, "Main Course & Thalis", false, "Boneless tandoori chicken cooked in silky smooth tomato-butter gravy with fenugreek.", "https://images.unsplash.com/photo-1603894584373-5ac82b2ae398?w=500"));
            items.add(menuItem(r, "Dal Makhani (Overnight Simmered)", 219, "Main Course & Thalis", true, "Slow-cooked black lentils in creamy butter gravy with a rich aroma.", "https://images.unsplash.com/photo-1546833999-b9f581a1996d?w=500"));
            items.add(menuItem(r, "Chef's Special Dum Chicken Biryani", 289, "Main Course & Thalis", false, "Royal basmati rice slow dum cooked with marinated chicken, fragrant mint and saffron.", "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=500"));

            items.add(menuItem(r, "Garlic Butter Naan (2 Pcs)", 69, "Breads", true, "Tandoor baked bread glazed with fresh minced roasted garlic and salted butter.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));
            items.add(menuItem(r, "Butter Tandoori Roti (2 Pcs)", 49, "Breads", true, "Whole wheat flatbread cooked crisp in clay tandoor and brushed with butter.", "https://images.unsplash.com/photo-1509722747041-616f39b57569?w=500"));

            items.add(menuItem(r, "Royal Gulab Jamun in Saffron Syrup (2 Pcs)", 79, "Desserts & Drinks", true, "Golden brown milk-solid dumplings soaked in aromatic saffron and cardamom syrup.", "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500"));
            items.add(menuItem(r, "Sweet Kesariya Lassi", 89, "Desserts & Drinks", true, "Traditional thick chilled sweet curd drink topped with clotted malai and saffron threads.", "https://images.unsplash.com/photo-1572490122747-3968b75cc699?w=500"));
        }

        // Fetch existing items to avoid duplicate dishes
        List<MenuItem> existing = menuItemRepository.findByRestaurantId(r.getId());
        Set<String> existingNames = existing.stream()
                .map(item -> item.getName().toLowerCase().trim())
                .collect(Collectors.toSet());

        List<MenuItem> toSave = new ArrayList<>();
        for (MenuItem item : items) {
            String normItem = item.getName().toLowerCase().trim();
            if (!existingNames.contains(normItem)) {
                toSave.add(item);
                existingNames.add(normItem);
            }
        }

        if (!toSave.isEmpty()) {
            menuItemRepository.saveAll(toSave);
            log.info("Saved {} new menu items for restaurant '{}' (total now: {})",
                    toSave.size(), r.getName(), existing.size() + toSave.size());
        }
    }

    private MenuItem menuItem(Restaurant r, String name, int price, String category, boolean isVeg, String desc, String img) {
        return MenuItem.builder()
                .restaurant(r)
                .name(name)
                .price(BigDecimal.valueOf(price))
                .category(category)
                .isVeg(isVeg)
                .isAvailable(true)
                .description(desc)
                .imageUrl(img)
                .build();
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

    private String getImageForCuisine(String cuisine, String name) {
        String c = (cuisine + " " + name).toLowerCase();
        if (c.contains("roll") || c.contains("wrap") || c.contains("frankie") || c.contains("kathi")) {
            return "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?w=600&auto=format&fit=crop&q=80";
        }
        if (c.contains("soup") || c.contains("broth")) {
            return "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=600&auto=format&fit=crop&q=80";
        }
        if (c.contains("pizza")) return "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600&auto=format&fit=crop&q=80";
        if (c.contains("burger") || c.contains("kfc") || c.contains("mcdonald")) return "https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=600&auto=format&fit=crop&q=80";
        if (c.contains("biryani")) return "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=600&auto=format&fit=crop&q=80";
        if (c.contains("cafe") || c.contains("coffee") || c.contains("starbucks")) return "https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?w=600&auto=format&fit=crop&q=80";
        if (c.contains("chinese") || c.contains("asian") || c.contains("momo") || c.contains("noodle")) return "https://images.unsplash.com/photo-1585032226651-759b368d7246?w=600&auto=format&fit=crop&q=80";
        if (c.contains("south") || c.contains("dosa")) return "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=600&auto=format&fit=crop&q=80";
        if (c.contains("dessert") || c.contains("ice cream") || c.contains("bakery") || c.contains("waffle")) return "https://images.unsplash.com/photo-1551024709-8f23befc6f87?w=600&auto=format&fit=crop&q=80";
        return "https://images.unsplash.com/photo-1555396273-367ea4eb4db5?w=600&auto=format&fit=crop&q=80";
    }
}
