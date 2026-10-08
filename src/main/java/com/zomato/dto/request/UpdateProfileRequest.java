package com.zomato.dto.request;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private String name;
    private String phone;
    private String address;
    private Double latitude;
    private Double longitude;
}
