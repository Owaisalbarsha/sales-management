package com.salesmanagement.territory.internal.seed;

import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.territory.internal.dto.CreateTerritoryRequest;
import com.salesmanagement.territory.internal.entity.Territory;
import com.salesmanagement.territory.internal.repository.TerritoryRepository;
import com.salesmanagement.territory.internal.service.TerritoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ten Damascus districts as sales territories.
 *
 * <p><strong>The weights are the whole point.</strong> Real distribution is never evenly spread —
 * the commercial districts carry the business and the outlying ones trail. Each territory publishes
 * a {@code salesWeight} that the customer and invoice seeders use to decide how many customers it
 * gets and how often they are billed, so {@code topTerritories} comes out ranked the intended way
 * from real invoices rather than from any figure written here.</p>
 *
 * <p>Idempotent on the territory name, which is this module's unique business key (enforced by
 * {@code TerritoryService.create}). Districts already present from the SQL demo seed are adopted
 * as they are rather than duplicated under a second id.</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TerritoryDemoData implements DemoDataContributor {

    private record TerritorySpec(String name, String description, int salesWeight) {}

    private static final List<TerritorySpec> TERRITORIES = List.of(
            new TerritorySpec("المالكي", "منطقة المالكي والروضة التجارية", 100),
            new TerritorySpec("المزة", "حي المزة والفيلات الغربية والشرقية", 88),
            new TerritorySpec("أبو رمانة", "أبو رمانة والسفارات والمحال الراقية", 74),
            new TerritorySpec("كفرسوسة", "كفرسوسة والمنطقة التجارية الحديثة", 66),
            new TerritorySpec("الميدان", "حي الميدان الجنوبي وأسواقه الشعبية", 58),
            new TerritorySpec("باب توما", "باب توما والقيمرية في دمشق القديمة", 47),
            new TerritorySpec("مشروع دمر", "مشروع دمر والمنطقة السكنية الغربية", 38),
            new TerritorySpec("ركن الدين", "ركن الدين وسفح قاسيون", 30),
            new TerritorySpec("القابون", "القابون الصناعي والسكني شمال شرق دمشق", 23),
            new TerritorySpec("ضاحية قدسيا", "ضاحية قدسيا شمال غرب دمشق", 16)
    );

    private final TerritoryService territoryService;
    private final TerritoryRepository territoryRepository;

    @Override
    public int order() {
        return SeedOrder.TERRITORIES;
    }

    @Override
    public String label() {
        return "territories";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        int created = 0;

        for (TerritorySpec spec : TERRITORIES) {
            Long id = territoryRepository.findAll().stream()
                    .filter(t -> t.getName().equals(spec.name()))
                    .map(Territory::getId)
                    .findFirst()
                    .orElse(null);

            if (id == null) {
                id = territoryService.create(
                        new CreateTerritoryRequest(spec.name(), spec.description())).id();
                created++;
            }
            context.territories().add(
                    new SeedContext.SeedTerritory(id, spec.name(), spec.salesWeight()));
        }

        context.count("territories", created);
        log.info("Demo territories ready: {} total, {} newly created",
                context.territories().size(), created);
    }
}
