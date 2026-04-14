package com.equitycart.user.enums;

public enum UserRoles {
    ADMIN("admin"),
    SELLER("seller"),
    CUSTOMER("customer");

    private String description;
    UserRoles(String description){
        this.description = description;
    }
    public String getDescription(UserRoles userRoles){
        this.description = userRoles.description;
        return description;
    }
}
