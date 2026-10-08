package com.zomato.elasticsearch.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "menu_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItemDocument {
    @Id
    private String id;
    @Field(type = FieldType.Text, analyzer = "standard")
    private String name;
    @Field(type = FieldType.Text, analyzer = "standard")
    private String description;
    @Field(type = FieldType.Keyword)
    private String category;
    @Field(type = FieldType.Double)
    private Double price;
    @Field(type = FieldType.Boolean)
    private boolean veg;
    @Field(type = FieldType.Keyword)
    private String restaurantId;
    @Field(type = FieldType.Keyword)
    private String restaurantName;
}
