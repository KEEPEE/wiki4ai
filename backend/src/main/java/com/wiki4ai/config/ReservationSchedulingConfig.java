package com.wiki4ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduling support for the reservation TTL reaper of WIKI4AI-157.
 * <p>
 * A second switch, deliberately separate from {@link SchedulingConfig}: the two reapers do
 * different jobs on different tables, and one property must not silently switch off the
 * other. An operator who pauses the work-item lease sweep — the obvious reason being a
 * maintenance window for {@code work_items} — must not thereby stop expired reservations
 * from releasing capacity, and the reverse must hold too.
 *
 * <h2>Why it is conditional (SPEC rule 5b, the flakiness risk)</h2>
 * The whole test suite shares one named in-memory H2 database
 * ({@code jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1}) and Spring caches its contexts for the
 * life of the JVM. A scheduler started by any cached context would keep sweeping that shared
 * database on a background thread for the rest of the run and could release a reservation
 * between another test's assertions — timing-dependent, therefore flaky. So the trigger is
 * switched off in the test profile
 * ({@code wiki4ai.resources.scheduling-enabled=false} in
 * {@code src/test/resources/application-test.properties} and
 * {@code src/test/resources/application.properties}), and the tests call
 * {@code ReservationReaper.releaseExpired()} — the very method the trigger calls.
 * {@code ReservationTtlIntegrationTest} asserts that no
 * {@code internalScheduledAnnotationProcessor} bean exists in the test context, which is
 * the proof that no {@code @Scheduled} method was registered at all.
 * <p>
 * {@code matchIfMissing = true}: an instance that says nothing about the property schedules.
 * Production behaviour must not depend on remembering to set a flag.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wiki4ai.resources.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationSchedulingConfig {
}
