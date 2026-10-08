package com.zomato.elasticsearch.repository;

import com.zomato.elasticsearch.document.RestaurantDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RestaurantSearchRepository extends ElasticsearchRepository<RestaurantDocument, String> {
    List<RestaurantDocument> findByNameContainingOrDescriptionContainingOrCuisineTypeContaining(
            String name, String description, String cuisineType);
    List<RestaurantDocument> findByIsOpenTrue();
    List<RestaurantDocument> findByCuisineType(String cuisineType);
}
