package com.example.searchengine.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support for the application.
 * The actual scheduled beans (e.g. {@code SyncScheduler}) are conditionally
 * loaded based on their own {@code @ConditionalOnProperty} annotations.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
