package com.taller.charges;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ChargeRequest(
        @NotBlank(message = "Idempotency key is required")
        String idempotencyKey,

        @Positive(message = "Amount must be greater than zero")
        double amount,

        @NotBlank(message = "Currency is required")
        String currency,

        @NotBlank(message = "Customer email is required")
        @Email(message = "Invalid email format")
        String customerEmail,

        @NotBlank(message = "Card token is required")
        String cardToken
) {}
