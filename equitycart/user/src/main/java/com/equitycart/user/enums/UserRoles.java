package com.equitycart.user.enums;

import lombok.Getter;

@Getter
public enum UserRoles {
    ADMIN("admin"),
    SELLER("seller"),
    CUSTOMER("customer");

    private final String description;

    UserRoles(String description){
        this.description = description;
    }

}
