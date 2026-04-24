package com.equitycart.commons.exception;

public class AccountDisabledException extends RuntimeException{

    public AccountDisabledException(String message) {
        super(message);
    }
}
