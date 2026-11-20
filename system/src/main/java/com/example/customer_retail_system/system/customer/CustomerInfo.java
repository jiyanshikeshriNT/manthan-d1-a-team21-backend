package com.example.customer_retail_system.system.customer;

/** Read model of a customer exposed to other modules. */
public record CustomerInfo(long id, String email, String name) {
}
