package com.zomato.service;

import com.zomato.dto.request.MenuItemRequest;
import com.zomato.dto.request.RestaurantRequest;
import com.zomato.dto.response.MenuItemDTO;
import com.zomato.dto.response.RestaurantDTO;
import com.zomato.exception.BusinessException;
import com.zomato.exception.ResourceNotFoundException;
import com.zomato.model.MenuItem;
import com.zomato.model.Restaurant;
import com.zomato.model.User;
import com.zomato.repository.MenuItemRepository;
import com.zomato.repository.RestaurantRepository;
import com.zomato.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final UserRepository userRepository;
    private final RealRestaurantService realRestaurantService;

    @Cacheable(value = "restaurants", key = "#id")
    public RestaurantDTO getRestaurantById(Long id) {
        log.debug("Fetching restaurant from DB for id: {}", id);
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        return mapToDTO(restaurant);
    }

    @Cacheable(value = "menuItems", key = "#restaurantId")
    public List<MenuItemDTO> getMenuByRestaurantId(Long restaurantId) {
        log.debug("Fetching menu from DB for restaurantId: {}", restaurantId);
        List<MenuItem> items = menuItemRepository.findByRestaurantIdAndIsAvailableTrue(restaurantId);
        if (items.size() < 8) {
            realRestaurantService.enrichRestaurantMenuIfSparse(restaurantId);
            items = menuItemRepository.findByRestaurantIdAndIsAvailableTrue(restaurantId);
        }
        return items.stream().map(this::mapMenuItemToDTO).toList();
    }

    public List<RestaurantDTO> getAllRestaurants() {
        realRestaurantService.purgeDuplicateRestaurantsInDatabase();
        return deduplicateDTOList(restaurantRepository.findAll().stream().map(this::mapToDTO).toList());
    }

    @Transactional
    public RestaurantDTO createRestaurant(RestaurantRequest request, Long ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found: " + ownerId));
        Restaurant restaurant = Restaurant.builder()
                .name(request.getName())
                .description(request.getDescription())
                .cuisineType(request.getCuisineType())
                .address(request.getAddress())
                .phone(request.getPhone())
                .email(request.getEmail())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .minOrderAmount(request.getMinOrderAmount())
                .avgDeliveryTime(request.getAvgDeliveryTime())
                .owner(owner)
                .isOpen(true)
                .build();
        Restaurant saved = restaurantRepository.save(restaurant);
        log.info("Restaurant created: {} by owner {}", saved.getName(), ownerId);
        return mapToDTO(saved);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "restaurants", key = "#id"),
        @CacheEvict(value = "menuItems", key = "#id")
    })
    public RestaurantDTO updateRestaurant(Long id, RestaurantRequest request) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        if (request.getName() != null) restaurant.setName(request.getName());
        if (request.getDescription() != null) restaurant.setDescription(request.getDescription());
        if (request.getCuisineType() != null) restaurant.setCuisineType(request.getCuisineType());
        if (request.getAddress() != null) restaurant.setAddress(request.getAddress());
        if (request.getPhone() != null) restaurant.setPhone(request.getPhone());
        if (request.getMinOrderAmount() != null) restaurant.setMinOrderAmount(request.getMinOrderAmount());
        if (request.getAvgDeliveryTime() != null) restaurant.setAvgDeliveryTime(request.getAvgDeliveryTime());
        Restaurant updated = restaurantRepository.save(restaurant);
        return mapToDTO(updated);
    }

    @Transactional
    @CacheEvict(value = "restaurants", key = "#id")
    public RestaurantDTO toggleRestaurantStatus(Long id) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        restaurant.setOpen(!restaurant.isOpen());
        Restaurant updated = restaurantRepository.save(restaurant);
        log.info("Restaurant {} status toggled to: {}", id, updated.isOpen());
        return mapToDTO(updated);
    }

    public List<RestaurantDTO> getNearbyRestaurants(double lat, double lng, double radiusKm) {
        realRestaurantService.purgeDuplicateRestaurantsInDatabase();
        List<RestaurantDTO> list = restaurantRepository.findRestaurantsNearby(lat, lng, radiusKm)
                .stream().map(r -> {
                    RestaurantDTO dto = mapToDTO(r);
                    if (r.getLatitude() != null && r.getLongitude() != null) {
                        dto.setDistanceKm(calculateDistanceKm(lat, lng, r.getLatitude(), r.getLongitude()));
                    }
                    return dto;
                }).toList();
        return deduplicateDTOList(list);
    }

    private List<RestaurantDTO> deduplicateDTOList(List<RestaurantDTO> list) {
        if (list == null || list.isEmpty()) return List.of();
        Map<String, RestaurantDTO> unique = new LinkedHashMap<>();
        for (RestaurantDTO dto : list) {
            if (dto.getName() == null) continue;
            String norm = RealRestaurantService.normalizeName(dto.getName());
            if (norm.length() < 2) {
                norm = dto.getName().toLowerCase().replaceAll("[^a-z0-9]", "");
            }
            if (!unique.containsKey(norm)) {
                unique.put(norm, dto);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth's radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(R * c * 10.0) / 10.0;
    }

    @Transactional
    @CacheEvict(value = "menuItems", key = "#restaurantId")
    public MenuItemDTO addMenuItem(Long restaurantId, MenuItemRequest request) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        MenuItem menuItem = MenuItem.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .category(request.getCategory())
                .isVeg(request.isVeg())
                .imageUrl(request.getImageUrl())
                .isAvailable(true)
                .restaurant(restaurant)
                .build();
        MenuItem saved = menuItemRepository.save(menuItem);
        log.info("Menu item added: {} to restaurant {}", saved.getName(), restaurantId);
        return mapMenuItemToDTO(saved);
    }

    @Transactional
    public MenuItemDTO updateMenuItem(Long menuItemId, MenuItemRequest request) {
        MenuItem menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + menuItemId));
        if (request.getName() != null) menuItem.setName(request.getName());
        if (request.getDescription() != null) menuItem.setDescription(request.getDescription());
        if (request.getPrice() != null) menuItem.setPrice(request.getPrice());
        if (request.getCategory() != null) menuItem.setCategory(request.getCategory());
        if (request.getImageUrl() != null) menuItem.setImageUrl(request.getImageUrl());
        MenuItem updated = menuItemRepository.save(menuItem);
        return mapMenuItemToDTO(updated);
    }

    @Transactional
    public void deleteMenuItem(Long menuItemId) {
        MenuItem menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + menuItemId));
        menuItem.setAvailable(false);
        menuItemRepository.save(menuItem);
        log.info("Menu item soft-deleted: {}", menuItemId);
    }

    private RestaurantDTO mapToDTO(Restaurant r) {
        RestaurantDTO dto = new RestaurantDTO();
        dto.setId(r.getId());
        dto.setName(r.getName());
        dto.setDescription(r.getDescription());
        dto.setCuisineType(r.getCuisineType());
        dto.setAddress(r.getAddress());
        dto.setRating(r.getRating());
        dto.setOpen(r.isOpen());
        dto.setAvgDeliveryTime(r.getAvgDeliveryTime());
        dto.setMinOrderAmount(r.getMinOrderAmount());
        dto.setImageUrl(r.getImageUrl());
        dto.setLatitude(r.getLatitude());
        dto.setLongitude(r.getLongitude());
        return dto;
    }

    private MenuItemDTO mapMenuItemToDTO(MenuItem m) {
        MenuItemDTO dto = new MenuItemDTO();
        dto.setId(m.getId());
        dto.setName(m.getName());
        dto.setDescription(m.getDescription());
        dto.setPrice(m.getPrice());
        dto.setCategory(m.getCategory());
        dto.setVeg(m.isVeg());
        dto.setAvailable(m.isAvailable());
        dto.setImageUrl(m.getImageUrl());
        return dto;
    }
}
