package com.salesmanagement.identity.internal.seed;

import com.salesmanagement.identity.internal.dto.CreateUserRequest;
import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.repository.UserRepository;
import com.salesmanagement.identity.internal.service.UserService;
import com.salesmanagement.shared.security.UserRole;
import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The staff of the demo company: one admin, two sales managers, two warehouse managers and eight
 * sales representatives, all with Arabic names.
 *
 * <p><strong>Eight representatives, with deliberately unequal weight.</strong> A rep ranking chart
 * needs enough bars to be a ranking rather than a comparison, and it needs them to differ. The
 * {@code salesWeight} published on each rep is what the invoice seeder later uses to decide who
 * bills more often and larger — so the ordering of {@code topRepresentatives} emerges from actual
 * invoices, and no total is ever written down anywhere.</p>
 *
 * <p><strong>Created through {@link UserService#create}</strong>, so passwords are BCrypt-hashed by
 * the real encoder, the phone-uniqueness rule is the production one, and {@code UserCreatedEvent}
 * fires exactly as it would for a real hire. Idempotent by phone number, which is this module's
 * natural business key and its login identifier.</p>
 *
 * <p>All accounts share the repository's existing local demo password convention (documented in
 * {@code db/seeder/R__seed_demo_data.sql}). It is a development credential for a database that only
 * ever holds fabricated data; it is never logged here.</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IdentityDemoData implements DemoDataContributor {

    /** Matches the existing local demo convention so every seeded account logs in the same way. */
    private static final String DEMO_PASSWORD = "Owais@1234";

    private final UserService userService;
    private final UserRepository userRepository;

    /**
     * One staff member to guarantee. {@code salesWeight} is meaningful for reps only; it is the
     * relative commercial strength that shapes the ranking downstream.
     */
    private record StaffSpec(String name, String phone, UserRole role, int salesWeight) {}

    private static final List<StaffSpec> STAFF = List.of(
            new StaffSpec("أويس البرشة",     "+963981491713", UserRole.ADMIN, 0),

            new StaffSpec("أحمد الحسن",      "+963991000002", UserRole.SALES_MANAGER, 0),
            new StaffSpec("حسام رقية",       "+963991000003", UserRole.SALES_MANAGER, 0),

            // Eight reps, weights spread wide enough that the bar chart has a clear shape.
            new StaffSpec("شادي حمزة",       "+963991000005", UserRole.SALES_REP, 100),
            new StaffSpec("رامي صالح",       "+963991000007", UserRole.SALES_REP, 88),
            new StaffSpec("خالد حاج عثمان",  "+963991000004", UserRole.SALES_REP, 76),
            new StaffSpec("عمر بكري",        "+963991000006", UserRole.SALES_REP, 64),
            new StaffSpec("يزن الحسن",       "+963991000012", UserRole.SALES_REP, 55),
            new StaffSpec("سامر العلي",      "+963991000013", UserRole.SALES_REP, 44),
            new StaffSpec("فادي ناصر",       "+963991000008", UserRole.SALES_REP, 33),
            new StaffSpec("مازن خوري",       "+963991000009", UserRole.SALES_REP, 24),

            new StaffSpec("بلال المستودع",   "+963991000010", UserRole.WAREHOUSE_MANAGER, 0),
            new StaffSpec("نور المستودع",    "+963991000011", UserRole.WAREHOUSE_MANAGER, 0)
    );

    @Override
    public int order() {
        return SeedOrder.USERS;
    }

    @Override
    public String label() {
        return "users";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        int created = 0;

        for (StaffSpec spec : STAFF) {
            User user = userRepository.findByPhoneNumber(spec.phone()).orElse(null);
            if (user == null) {
                user = userService.create(new CreateUserRequest(
                        spec.name(), spec.phone(), DEMO_PASSWORD, spec.role()));
                created++;
            }

            if (spec.role() == UserRole.SALES_REP) {
                context.representatives().add(
                        new SeedContext.SeedRep(user.getId(), user.getName(), spec.salesWeight()));
            } else {
                // First of each non-rep role wins the slot; later ones are still real accounts,
                // they simply are not the one the demand orders are attributed to.
                context.staff().putIfAbsent(spec.role().name(), user.getId());
            }
        }

        context.count("users", created);
        context.count("sales representatives", context.representatives().size());
        log.info("Demo users ready: {} representatives, {} newly created",
                context.representatives().size(), created);
    }
}
