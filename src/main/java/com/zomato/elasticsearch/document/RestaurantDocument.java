package com.zomato.elasticsearch.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "restaurants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantDocument {
    @Id
    private String id;
    @Field(type = FieldType.Text, analyzer = "standard")
    private String name;
    @Field(type = FieldType.Text, analyzer = "standard")
    private String description;
    @Field(type = FieldType.Keyword)
    private String cuisineType;
    @Field(type = FieldType.Keyword)
    private String address;
    @Field(type = FieldType.Boolean)
    private boolean isOpen;
    @Field(type = FieldType.Double)
    private Double rating;
    @Field(type = FieldType.Double)
    private Double latitude;
    @Field(type = FieldType.Double)
    private Double longitude;
    @Field(type = FieldType.Text)
    private String imageUrl;
}
