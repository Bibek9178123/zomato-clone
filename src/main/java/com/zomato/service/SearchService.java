package com.zomato.service;

import com.zomato.elasticsearch.document.MenuItemDocument;
import com.zomato.elasticsearch.document.RestaurantDocument;
import com.zomato.elasticsearch.repository.MenuItemSearchRepository;
import com.zomato.elasticsearch.repository.RestaurantSearchRepository;
import com.zomato.model.MenuItem;
import com.zomato.model.Restaurant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class SearchService {

    @Autowired(required = false)
    private RestaurantSearchRepository restaurantSearchRepository;

    @Autowired(required = false)
    private MenuItemSearchRepository menuItemSearchRepository;

    @Autowired(required = false)
    private ElasticsearchOperations elasticsearchOperations;

    public List<RestaurantDocument> searchRestaurants(String query) {
        log.debug("Searching restaurants with query: {}", query);
        if (elasticsearchOperations == null || restaurantSearchRepository == null) {
            log.warn("Elasticsearch is disabled. Skipping restaurant search.");
            return List.of();
        }
        try {
            Criteria criteria = new Criteria("name").contains(query)
                    .or(new Criteria("description").contains(query))
                    .or(new Criteria("cuisineType").contains(query));
            CriteriaQuery criteriaQuery = new CriteriaQuery(criteria);
            SearchHits<RestaurantDocument> hits =
                    elasticsearchOperations.search(criteriaQuery, RestaurantDocument.class);
            return hits.getSearchHits().stream()
                    .map(hit -> hit.getContent())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Elasticsearch search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<MenuItemDocument> searchMenuItems(String query, String restaurantId) {
        log.debug("Searching menu items with query: {} for restaurant: {}", query, restaurantId);
        if (menuItemSearchRepository == null) {
            log.warn("Elasticsearch is disabled. Skipping menu search.");
            return List.of();
        }
        if (restaurantId != null) {
            return menuItemSearchRepository.findByRestaurantId(restaurantId)
                    .stream()
                    .filter(item -> item.getName().toLowerCase().contains(query.toLowerCase())
                            || (item.getDescription() != null &&
                                item.getDescription().toLowerCase().contains(query.toLowerCase())))
                    .collect(Collectors.toList());
        }
        return menuItemSearchRepository.findByNameContainingOrDescriptionContaining(query, query);
    }

    public void indexRestaurant(Restaurant restaurant) {
        if (restaurantSearchRepository == null) return;
        try {
            RestaurantDocument doc = RestaurantDocument.builder()
                    .id(restaurant.getId().toString())
                    .name(restaurant.getName())
                    .description(restaurant.getDescription())
                    .cuisineType(restaurant.getCuisineType())
                    .address(restaurant.getAddress())
                    .isOpen(restaurant.isOpen())
                    .rating(restaurant.getRating())
                    .latitude(restaurant.getLatitude())
                    .longitude(restaurant.getLongitude())
                    .imageUrl(restaurant.getImageUrl())
                    .build();
            restaurantSearchRepository.save(doc);
            log.debug("Indexed restaurant: {}", restaurant.getId());
        } catch (Exception e) {
            log.error("Failed to index restaurant {}: {}", restaurant.getId(), e.getMessage());
        }
    }

    public void indexMenuItem(MenuItem menuItem) {
        if (menuItemSearchRepository == null) return;
        try {
            MenuItemDocument doc = MenuItemDocument.builder()
                    .id(menuItem.getId().toString())
                    .name(menuItem.getName())
                    .description(menuItem.getDescription())
                    .category(menuItem.getCategory())
                    .price(menuItem.getPrice().doubleValue())
                    .veg(menuItem.isVeg())
                    .restaurantId(menuItem.getRestaurant().getId().toString())
                    .restaurantName(menuItem.getRestaurant().getName())
                    .build();
            menuItemSearchRepository.save(doc);
            log.debug("Indexed menu item: {}", menuItem.getId());
        } catch (Exception e) {
            log.error("Failed to index menu item {}: {}", menuItem.getId(), e.getMessage());
        }
    }

    public void removeRestaurantFromIndex(Long restaurantId) {
        if (restaurantSearchRepository == null) return;
        try {
            restaurantSearchRepository.deleteById(restaurantId.toString());
        } catch (Exception e) {
            log.error("Failed to remove restaurant {} from index: {}", restaurantId, e.getMessage());
        }
    }

    public void removeMenuItemFromIndex(Long menuItemId) {
        if (menuItemSearchRepository == null) return;
        try {
            menuItemSearchRepository.deleteById(menuItemId.toString());
        } catch (Exception e) {
            log.error("Failed to remove menu item {} from index: {}", menuItemId, e.getMessage());
        }
    }
}
