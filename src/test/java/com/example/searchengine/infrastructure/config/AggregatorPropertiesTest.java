package com.example.searchengine.infrastructure.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("default values satisfy the validation contract")
    void defaultsAreValid() {
        AggregatorProperties properties = new AggregatorProperties();

        Set<ConstraintViolation<AggregatorProperties>> violations = validator.validate(properties);

        assertThat(violations).isEmpty();
        assertThat(properties.getSync().isEnabled()).isTrue();
        assertThat(properties.getSync().getFixedDelayMs()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("fixedDelayMs below 1000 violates @Min")
    void fixedDelayMsBelowMinimumIsRejected() {
        AggregatorProperties properties = new AggregatorProperties();
        properties.getSync().setFixedDelayMs(999L);

        Set<ConstraintViolation<AggregatorProperties>> violations = validator.validate(properties);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("sync.fixedDelayMs"));
    }
}
