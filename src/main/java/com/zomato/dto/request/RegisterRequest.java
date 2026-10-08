package com.zomato.dto.request;

import com.zomato.model.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "Name is required")
    private String name;
    @NotBlank @Email(message = "Invalid email")
    private String email;
    @NotBlank @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;
    private String phone;
    private UserRole role;
    private String address;
}
