package com.multiship.backend.model;

import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 48 N+1 fix — regression guard on the fetch strategies we
 * intentionally chose. Reflection-based (no Spring/DB dependency)
 * because the project has no test-DB infrastructure (no H2, no
 * Testcontainers). The classic "assert exactly 1 SELECT per call" test
 * would require spinning up a real DB; this test instead guards the
 * annotations that DETERMINE the query count.
 *
 * <p>Runtime SQL count verification: set
 * {@code spring.jpa.show-sql=true} in application.properties and hit
 * {@code /api/v1/orders/{no}/label} for an intl order — you should see
 * exactly 1 SELECT for OrderCustoms + its items (EAGER JOIN), and 1
 * SELECT with LEFT JOIN FETCH for ClientCustomsProfile + countryLinks.
 */
class FetchStrategyGuardTest {

    @Test
    void orderCustomsItemsMustBeEager() throws NoSuchFieldException {
        // Was default LAZY before Sprint 48; caused ~15K wasted "on-demand items"
        // SELECTs per day at 50K orders. EAGER ships items in the same SELECT.
        Field items = OrderCustoms.class.getDeclaredField("items");
        OneToMany annotation = items.getAnnotation(OneToMany.class);
        assertNotNull(annotation, "@OneToMany missing on OrderCustoms.items");
        assertEquals(FetchType.EAGER, annotation.fetch(),
                "OrderCustoms.items MUST be EAGER — every code path that loads " +
                "an OrderCustoms row also reads its items (label gen, CI render, " +
                "per-package declared-value derivation). LAZY here is a scale bug.");
    }

    @Test
    void clientCustomsProfileCountryLinksMustBeLazy() throws NoSuchFieldException {
        // Was EAGER before Sprint 48; every profile load JOINed all country
        // links via cartesian expansion. LAZY + JOIN FETCH variants on the
        // hot-path queries is the correct shape.
        Field links = ClientCustomsProfile.class.getDeclaredField("countryLinks");
        OneToMany annotation = links.getAnnotation(OneToMany.class);
        assertNotNull(annotation, "@OneToMany missing on ClientCustomsProfile.countryLinks");
        assertEquals(FetchType.LAZY, annotation.fetch(),
                "ClientCustomsProfile.countryLinks MUST be LAZY — the hot-path " +
                "queries (findByClientAndCountry, findByClientCodeIgnoreCase) " +
                "supply JOIN FETCH explicitly; EAGER causes unnecessary loads " +
                "on any admin bulk-listing path.");
    }

    @Test
    void labelPackageMustHaveBatchSize() {
        // Sprint 48 N+1 fix — BatchSize enables Hibernate to fetch LazyProxies
        // of this entity in batches of 100 when a list of Orders is loaded
        // and touches label_package on each. Guards against future N+1 in any
        // list-processing code path.
        BatchSize annotation = LabelPackage.class.getAnnotation(BatchSize.class);
        assertNotNull(annotation, "@BatchSize missing on LabelPackage — future " +
                "list-loop code paths will fire one SELECT per Order for its " +
                "label_package rows. Keep @BatchSize > 1 on the entity.");
        assertTrue(annotation.size() >= 50,
                "@BatchSize on LabelPackage should be >= 50 to amortise " +
                "the roundtrip cost across a typical order-list page.");
    }
}
