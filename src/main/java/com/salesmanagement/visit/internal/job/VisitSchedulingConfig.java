package com.salesmanagement.visit.internal.job;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's task scheduler for the visit module's nightly sweep.
 * If another module already declares {@code @EnableScheduling}, remove this class.
 */
@Configuration
@EnableScheduling
public class VisitSchedulingConfig {
}
