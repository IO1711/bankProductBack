package com.bilolbek.bankProductCreation;

import jakarta.validation.constraints.NotBlank;

public record CreateProductRequest(@NotBlank String type) { }
