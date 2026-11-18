package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A dish sold at one outlet and made at another draws the preparing outlet's recipe from the preparing outlet's store. */
class StockSourceTest {

    private static final String CHICKEN = "[{\"stockItemId\":\"chicken\",\"quantity\":1}]";
    private static final String DOUBLE = "[{\"stockItemId\":\"chicken\",\"quantity\":2}]";

    private final Outlet bar = outlet("bar", "bar-store");
    private final Outlet kitchen = outlet("kitchen", "kitchen-store");
    private final MenuItem kitchenChicken = item("k1", "kitchen", "Peppered chicken", CHICKEN, null);
    private final Map<String, Outlet> outlets = Map.of("bar", bar, "kitchen", kitchen);
    private final Map<String, List<MenuItem>> menus = Map.of("kitchen", List.of(item("k0", "kitchen", "Beef suya", null, null), kitchenChicken));

    private StockSource.Source of(MenuItem sold, String linePreparedAt) {
        return StockSource.of(sold, linePreparedAt, bar, id -> Optional.ofNullable(outlets.get(id)), id -> menus.getOrDefault(id, List.of()));
    }

    @Test
    void madeWhereItIsSoldUsesItsOwnRecipeFromTheSellingStore() {
        StockSource.Source s = of(item("b1", "bar", "Coke", "[{\"stockItemId\":\"coke\",\"quantity\":1}]", null), null);
        assertThat(s.storeId()).isEqualTo("bar-store");
        assertThat(s.recipe()).extracting(Recipes.Line::stockItemId).containsExactly("coke");
    }

    @Test
    void madeElsewhereWithoutARecipeUsesTheSameNamedDishThereFromItsStore() {
        StockSource.Source s = of(item("b2", "bar", " peppered CHICKEN ", null, "kitchen"), "kitchen");
        assertThat(s.storeId()).isEqualTo("kitchen-store");
        assertThat(s.recipe()).containsExactly(new Recipes.Line("chicken", new BigDecimal("1")));
    }

    @Test
    void madeElsewhereWithItsOwnRecipeUsesThatRecipeFromThePreparingStore() {
        StockSource.Source s = of(item("b2", "bar", "Peppered chicken", DOUBLE, "kitchen"), null);
        assertThat(s.storeId()).isEqualTo("kitchen-store");
        assertThat(s.recipe()).containsExactly(new Recipes.Line("chicken", new BigDecimal("2")));
    }

    @Test
    void theLinesSnapshotWinsOverTheItemAndNoTwinMeansNothingDrawn() {
        // The item is no longer made in the kitchen, but this line was.
        StockSource.Source s = of(item("b2", "bar", "Peppered chicken", null, null), "kitchen");
        assertThat(s.storeId()).isEqualTo("kitchen-store");
        assertThat(s.recipe()).hasSize(1);

        StockSource.Source none = of(item("b3", "bar", "Asun", null, "kitchen"), null);
        assertThat(none.recipe()).isEmpty();
    }

    @Test
    void aPreparingOutletWithNoStoreOrGoneFallsBackToTheSellingStore() {
        Outlet noStore = outlet("grill", null);
        StockSource.Source s = StockSource.of(item("b4", "bar", "Suya", DOUBLE, "grill"), null, bar,
                id -> Optional.ofNullable("grill".equals(id) ? noStore : null), id -> List.of());
        assertThat(s.storeId()).isEqualTo("bar-store");
        StockSource.Source gone = of(item("b5", "bar", "Suya", DOUBLE, "removed"), null);
        assertThat(gone.storeId()).isEqualTo("bar-store");
        assertThat(gone.recipe()).hasSize(1);
        assertThat(of(null, "kitchen").recipe()).isEmpty();
    }

    private static Outlet outlet(String id, String store) {
        Outlet o = Outlet.builder().name(id).storeId(store).build();
        o.setId(id);
        return o;
    }

    private static MenuItem item(String id, String outletId, String name, String recipe, String preparedAt) {
        MenuItem m = MenuItem.builder().outletId(outletId).name(name).category("Food").price(new BigDecimal("5000")).recipeJson(recipe)
                .preparedAt(preparedAt).build();
        m.setId(id);
        return m;
    }
}
