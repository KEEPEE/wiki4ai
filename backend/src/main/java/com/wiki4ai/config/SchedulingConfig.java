package com.wiki4ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduling support so that the lease reaper of WIKI4AI-142
 * actually runs. This is the first scheduled task in this project — until now the
 * codebase had no {@code @Scheduled} and no {@code @EnableScheduling} at all, and
 * every periodic job (rate-limit windows, token cleanup) was maintained lazily on
 * write.
 *
 * <h2>Why it is a separate class</h2>
 * {@code @EnableScheduling} is not a property of the reaper, it is a property of
 * the application. Keeping it here means the switch that starts the scheduler is
 * one annotation in one file, and the reaper stays a plain bean that can be
 * constructed and called by a test without any scheduling machinery at all.
 *
 * <h2>Why it is conditional (WIKI4AI-142, the flakiness risk)</h2>
 * The whole test suite shares one named in-memory H2 database
 * ({@code jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1}) and Spring caches its contexts
 * for the life of the JVM. A scheduler started by any cached context would keep a
 * thread sweeping that shared database for the rest of the run, moving rows between
 * other tests' assertions — a race that depends on how long each test takes, which
 * is exactly the class of nondeterminism SPEC rule 5b exists to prevent.
 * <p>
 * So the trigger is switched off in the test profile
 * ({@code wiki4ai.work-items.scheduling-enabled=false} in
 * {@code src/test/resources/application-test.properties} and
 * {@code src/test/resources/application.properties}), and the reaper's operation is
 * called directly by the tests that need it. This is not the reaper being disabled
 * for the tests: {@code WorkItemLeaseReaper} and {@code WorkItemService.releaseExpiredLeases()}
 * are present and exercised in every test that needs them — only the timer is off.
 * {@code WorkItemLeaseReaperTest} asserts that the timer is wired to that same
 * method, so the wiring cannot rot silently.
 * <p>
 * {@code matchIfMissing = true}: an instance that says nothing about the property
 * schedules. Production behaviour must not depend on remembering to set a flag.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wiki4ai.work-items.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
