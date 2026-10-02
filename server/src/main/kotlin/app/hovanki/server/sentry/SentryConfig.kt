package app.hovanki.server.sentry

import io.sentry.SentryOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Sentry on the staging server only (docs/adr/0018-field-test-build.md §7). Sentry's Spring Boot starter starts when the
 * property `sentry.dsn` is there (the environment variable SENTRY_DSN): production sets none, and then neither the SDK
 * nor these beans exist. The starter hands every [SentryOptions] callback bean to the SDK; these two are the filter
 * ([ServerEventScrubber]) that every event and breadcrumb goes through.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["sentry.dsn"])
class SentryConfig {
    @Bean
    fun sentryBeforeSend(): SentryOptions.BeforeSendCallback =
        SentryOptions.BeforeSendCallback { event, _ -> ServerEventScrubber.scrub(event) }

    @Bean
    fun sentryBeforeBreadcrumb(): SentryOptions.BeforeBreadcrumbCallback =
        SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ -> ServerEventScrubber.scrubBreadcrumb(breadcrumb) }
}
