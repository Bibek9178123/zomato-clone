package com.zomato.dto.response;

import com.zomato.model.enums.UserRole;
import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String name;
    private String email;
    private String phone;
    private UserRole role;
    private String address;
}
