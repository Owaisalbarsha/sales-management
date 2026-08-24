package com.salesmanagement.shared.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Runs the demo-data contributors, once, on startup.
 *
 * <h2>Where it runs</h2>
 * Everywhere except the test profile. This application is a demo system: the hosted instance is
 * meant to come up with a populated dataset, and requiring an environment variable to make that
 * happen was one more thing to forget on every new deployment. So seeding is the default, and
 * {@code @Profile("!test")} exists only to keep the ~700 seeded rows out of {@code sm_test}, where
 * every {@code @SpringBootTest} would otherwise pay for them on each context load.
 *
 * <h2>Why that is safe here</h2>
 * Every contributor is idempotent: it looks up its own business key — phone number, SKU, territory
 * name, invoice {@code client_uuid}, rep + date — and creates only what is missing. A second run
 * over an already-seeded database writes nothing. Nothing in this class, or in any contributor,
 * truncates or drops. Re-deploying is therefore a no-op rather than a rebuild.
 *
 * <p>{@code app.seed.enabled} remains the kill switch. It is committed as {@code true}, so setting
 * it to {@code false} on one deployment is all it takes to stop seeding there.</p>
 *
 * <h2>Reset</h2>
 * {@code app.seed.reset=true} additionally asks each contributor to delete the dated operational
 * documents it owns inside the seeded window before rebuilding them. It is off by default and it
 * never touches users, territories, customers or products.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    private final List<DemoDataContributor> contributors;
    private final boolean reset;

    public DemoDataSeeder(List<DemoDataContributor> contributors,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${app.seed.reset:false}") boolean reset) {
        this.contributors = contributors;
        this.reset = reset;
    }

    @Override
    public void run(ApplicationArguments args) {
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

    /** Closing summary. Counts only — never credentials, tokens or personal data. */
    private void logSummary(SeedContext context, long elapsedMs) {
        log.info("──────────────────────────────────────────────────────────────");
        log.info("Demo data seeding completed in {} ms", elapsedMs);
        log.info("Historical period: {}", context.window());
        context.counts().forEach((label, created) ->
                log.info(String.format(Locale.ROOT, "  %-26s %d", label, created)));
        log.info("──────────────────────────────────────────────────────────────");
    }
}
