package com.hospio.fnb.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.fnb.dto.VenueReport;
import com.hospio.fnb.rules.Recipes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The shaping of the venue reports (docs/plan-venue-type.md phase 2): categories, hours, staff with voids, recipe use. */
class VenueReportServiceTest {

    static VenueReport.ItemRow item(String id, String name, String cat, long qty, String revenue) {
        return new VenueReport.ItemRow(id, name, cat, "o1", "Main Bar", qty, new BigDecimal(revenue));
    }

    @Test
    void categoriesAddUpTheItemsBiggestFirst() {
        List<VenueReport.CategoryRow> rows = VenueReportService.categories(List.of(item("a", "Star", "Beer", 3, "4500"),
                item("b", "Gulder", "Beer", 2, "3000"), item("c", "Suya", "Grill", 1, "9000"), item("d", "Water", "Other", 4, "1000")));
        assertThat(rows).extracting(VenueReport.CategoryRow::category).containsExactly("Grill", "Beer", "Other");
        VenueReport.CategoryRow beer = rows.get(1);
        assertThat(beer.quantity()).isEqualTo(5);
        assertThat(beer.items()).isEqualTo(2);
        assertThat(beer.revenue()).isEqualByComparingTo("7500");
    }

    @Test
    void hoursFillTheDayWithZeros() {
        List<VenueReport.HourRow> out = VenueReportService.hours(List.of(new VenueReport.HourRow(21, 3, new BigDecimal("12000"))));
        assertThat(out).hasSize(24);
        assertThat(out.get(21).sales()).isEqualTo(3);
        assertThat(out.get(20).sales()).isZero();
        assertThat(out.get(20).sold()).isEqualByComparingTo("0");
    }

    @Test
    void staffJoinsSellersWithTheirVoidsAndKeepsVoidersWhoSoldNothing() {
        SalesHistoryQuery.Money3 ada = new SalesHistoryQuery.Money3(4, new BigDecimal("20000"), new BigDecimal("15000"), new BigDecimal("5000"),
                BigDecimal.ZERO, 1, new BigDecimal("3000"));
        List<VenueReport.StaffRow> rows = VenueReportService.staff(List.of(new SalesHistoryQuery.Group("u1", "Ada Bar", ada)),
                List.of(new VenueReportQuery.VoidTotal("u1", "Ada Bar", 2, new BigDecimal("4500")),
                        new VenueReportQuery.VoidTotal("m1", "Musa Manager", 1, new BigDecimal("3000"))));
        assertThat(rows).extracting(VenueReport.StaffRow::name).containsExactly("Ada Bar", "Musa Manager");
        assertThat(rows.get(0).voids()).isEqualTo(2);
        assertThat(rows.get(0).voidAmount()).isEqualByComparingTo("4500");
        assertThat(rows.get(1).sales()).isZero();
        assertThat(rows.get(1).voids()).isEqualTo(1);
    }

    @Test
    void recipesGiveTheStockTheSalesCallFor() {
        Map<String, List<Recipes.Line>> recipes = Map.of("chapman", List.of(new Recipes.Line("fanta", new BigDecimal("0.5")),
                new Recipes.Line("grenadine", new BigDecimal("0.05"))), "fries", List.of(new Recipes.Line("potato", new BigDecimal("0.3"))));
        VenueReportService.Expected e = VenueReportService.expected(List.of(item("chapman", "Chapman", "Cocktails", 4, "12000"),
                item("fries", "Fries", "Sides", 2, "4000"), item("water", "Water", "Other", 6, "3000")),
                id -> recipes.getOrDefault(id, List.of()));
        assertThat(e.withRecipe()).isEqualTo(2);
        assertThat(e.withoutRecipe()).isEqualTo(1);
        assertThat(e.use()).extracting(VenueReport.StockUse::stockItemId).containsExactly("fanta", "grenadine", "potato");
        assertThat(e.use().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(e.use().get(1).quantity()).isEqualByComparingTo("0.2");
        assertThat(e.use().get(2).quantity()).isEqualByComparingTo("0.6");
    }

    @Test
    void topItemsCountTheSameNameOnceAcrossOutlets() {
        List<VenueReport.ItemRow> top = VenueReportService.topByName(List.of(item("a", "Star Lager", "Beer", 15, "22500"),
                item("b", "Coke", "Soft drinks", 8, "6400"), item("c", "Chapman", "Cocktails", 4, "10000"), item("d", "coke ", "Soft drinks", 3, "2400")), 2);
        assertThat(top).extracting(VenueReport.ItemRow::name).containsExactly("Star Lager", "Coke");
        assertThat(top.get(1).quantity()).isEqualTo(11);
        assertThat(top.get(1).revenue()).isEqualByComparingTo("8800");
    }
}
