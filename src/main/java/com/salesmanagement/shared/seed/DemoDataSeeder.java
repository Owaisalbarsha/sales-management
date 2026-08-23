package com.salesmanagement.shared.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Runs the demo-data contributors, once, on startup — <strong>only</strong> in a local/dev profile
 * with seeding explicitly switched on.
 *
 * <h2>Why two independent locks</h2>
 * Either lock alone is one mistake away from writing hundreds of fabricated invoices into a real
 * database. A profile alone fails the day someone runs production with {@code dev} still on the
 * command line; a property alone fails the day {@code app.seed.enabled=true} is copied into a shared
 * {@code application.properties}. Requiring both means a single slip is never sufficient:
 *
 * <ul>
 *   <li>{@code @Profile({"local","dev"})} — the bean does not exist in any other profile, so there
 *       is nothing to trigger, not even reflectively;</li>
 *   <li>{@code app.seed.enabled=true} — absent by default, including in {@code application-local}.</li>
 * </ul>
 *
 * <p>As a third line of defence the runner re-checks the active profiles at execution time and
 * refuses to proceed if it somehow finds itself outside local/dev — a belt-and-braces guard against
 * a future refactor that widens or drops the annotation. It logs loudly rather than throwing, since
 * failing an application start over demo data would itself be a production incident.</p>
 *
 * <h2>Reset</h2>
 * {@code app.seed.reset=true} additionally asks each contributor to delete the dated operational
 * documents it owns inside the seeded window before rebuilding them. It is off by default, it is
 * only reachable behind both locks above, and it never touches users, territories, customers or
 * products. There is no code path in this class that drops or truncates anything.
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    private static final List<String> ALLOWED_PROFILES = List.of("local", "dev");

    private final List<DemoDataContributor> contributors;
    private final Environment environment;
    private final boolean reset;

    public DemoDataSeeder(List<DemoDataContributor> contributors,
                          Environment environment,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${app.seed.reset:false}") boolean reset) {
        this.contributors = contributors;
        this.environment = environment;
        this.reset = reset;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!hasAllowedProfile()) {
            log.error("Demo seeding ABORTED: active profiles {} are not local/dev. "
                            + "The seeder must never populate a non-development database.",
                    List.of(environment.getActiveProfiles()));
            return;
        }

        SeedWindow window = SeedWindow.untilToday();
        SeedContext context = new SeedContext(window, reset);

        log.info("──────────────────────────────────────────────────────────────");
        log.info("Demo data seeding started");
        log.info("Historical period: {}", window);
        log.info("Contributors: {}", contributors.size());
        if (reset) {
            log.warn("RESET enabled — seeded operational documents inside the window "
                    + "will be deleted and rebuilt. Master data is untouched.");
        }

        List<DemoDataContributor> ordered = contributors.stream()
                .sorted(Comparator.comparingInt(DemoDataContributor::order))
                .toList();

        if (reset) {
            // Reverse order so children go before their parents (visits before routes, etc.).
            ordered.reversed().forEach(c -> {
                log.info("  reset  <- {}", c.label());
                c.resetSeededData(context);
            });
        }

        long startedAt = System.currentTimeMillis();
        for (DemoDataContributor contributor : ordered) {
            long t0 = System.currentTimeMillis();
            contributor.contribute(context);
            log.info("  seeded -> {} ({} ms)", contributor.label(), System.currentTimeMillis() - t0);
        }

        logSummary(context, System.currentTimeMillis() - startedAt);
    }

    private boolean hasAllowedProfile() {
        for (String active : environment.getActiveProfiles()) {
            if (ALLOWED_PROFILES.contains(active)) {
                return true;
            }
        }
        return false;
    }

    /** Closing summary. Counts only — never credentials, tokens or personal data. */
    private void logSummary(SeedContext context, long elapsedMs) {
        log.info("──────────────────────────────────────────────────────────────");
        log.info("Demo data seeding completed in {} ms", elapsedMs);
        log.info("Historical period: {}", context.window());
        context.counts().forEach((label, created) ->
                log.info(String.format("  %-26s %d", label, created)));
        log.info("──────────────────────────────────────────────────────────────");
    }
}
