package com.hospio.fnb.rules;

import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which recipe a sold line draws and from which store (docs/restaurant-bar.md, "Stock for a dish made at another outlet").
 * <ul>
 * <li>Made where it is sold: the item's own recipe, from the selling outlet's store.</li>
 * <li>Made at another outlet ({@code preparedAt}, e.g. peppered chicken sold at the bar and cooked in the kitchen): the ingredients
 * are in the preparing outlet's store, so the draw comes from there. The recipe is the sold item's own when it has one; when it has
 * none, the recipe of the item with the same name on the preparing outlet's menu (the dish the kitchen keeps, which a copied item
 * came from). Still nothing: nothing is drawn, as for any item without a recipe.</li>
 * <li>The preparing outlet has no store (or was removed): the selling outlet's store.</li>
 * </ul>
 * Exactly one recipe per line, so a line is never drawn twice; the order is drawn once (inventory is idempotent on the order).
 */
public final class StockSource {

    private StockSource() {
    }

    public record Source(List<Recipes.Line> recipe, String storeId) {
    }

    /**
     * @param sold        the menu item sold (null when it was removed: nothing drawn)
     * @param preparedAt  where this line was made (the line's snapshot; the item's when the line has none)
     * @param selling     the outlet that sold it
     * @param outlets     outlet by id
     * @param menuOf      the items on an outlet's menu
     */
    public static Source of(MenuItem sold, String preparedAt, Outlet selling, Function<String, Optional<Outlet>> outlets,
                            Function<String, List<MenuItem>> menuOf) {
        if (sold == null) {
            return new Source(List.of(), selling.getStoreId());
        }
        List<Recipes.Line> own = Recipes.read(sold.getRecipeJson());
        String madeAt = preparedAt != null && !preparedAt.isBlank() ? preparedAt : sold.getPreparedAt();
        if (madeAt == null || madeAt.isBlank() || madeAt.equals(selling.getId())) {
            return new Source(own, selling.getStoreId());
        }
        Optional<Outlet> prep = outlets.apply(madeAt);
        String store = prep.map(Outlet::getStoreId).filter(s -> !s.isBlank()).orElse(selling.getStoreId());
        if (!own.isEmpty()) {
            return new Source(own, store);
        }
        String name = key(sold.getName());
        List<MenuItem> there = prep.isEmpty() ? List.of() : menuOf.apply(madeAt);
        // The active one first; a match without a recipe gives nothing.
        Optional<MenuItem> twin = there.stream().filter(m -> name.equals(key(m.getName()))).filter(MenuItem::isActive).findFirst()
                .or(() -> there.stream().filter(m -> name.equals(key(m.getName()))).findFirst());
        return new Source(twin.map(m -> Recipes.read(m.getRecipeJson())).orElse(List.of()), store);
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
