package com.zomato.elasticsearch.repository;

import com.zomato.elasticsearch.document.MenuItemDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MenuItemSearchRepository extends ElasticsearchRepository<MenuItemDocument, String> {
    List<MenuItemDocument> findByNameContainingOrDescriptionContaining(String name, String description);
    List<MenuItemDocument> findByRestaurantId(String restaurantId);
    List<MenuItemDocument> findByVegTrue();
}
