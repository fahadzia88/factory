package com.toptier.custom.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** Turns on `@Scheduled` jobs unless `app.scheduling.enabled=false` (as in tests). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig
