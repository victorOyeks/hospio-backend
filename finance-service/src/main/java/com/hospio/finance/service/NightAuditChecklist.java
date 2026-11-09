package com.hospio.finance.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The night audit's computed checklist: arrivals all resolved (nobody left BOOKED), departures all out, every till
 * counted and closed, and no departure leaving with money still owing.
 */
public final class NightAuditChecklist {

    private NightAuditChecklist() {
    }

    public record Item(String key, String label, boolean done, String detail) {
    }

    public static List<Item> build(int arrivalsPending, int departuresPending, List<String> openTillRoles, int balancesOwing, BigDecimal balanceTotal) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("arrivals", "Every arrival checked in or resolved", arrivalsPending == 0,
                arrivalsPending == 0 ? "No expected guests are still waiting" : plural(arrivalsPending, "guest") + " expected today " + (arrivalsPending == 1 ? "hasn't" : "haven't") + " checked in"));
        items.add(new Item("departures", "Every departure checked out", departuresPending == 0,
                departuresPending == 0 ? "Nobody is due out and still in the room" : plural(departuresPending, "guest") + " due out " + (departuresPending == 1 ? "is" : "are") + " still checked in"));
        items.add(new Item("tills", "Every till counted and closed", openTillRoles.isEmpty(),
                openTillRoles.isEmpty() ? "No tills are open" : plural(openTillRoles.size(), "till") + " still open: " + String.join(", ", openTillRoles)));
        items.add(new Item("balances", "No departure leaving with a balance", balancesOwing == 0,
                balancesOwing == 0 ? "Every departing folio is settled" : plural(balancesOwing, "departing folio") + " still owe" + (balancesOwing == 1 ? "s " : " ") + balanceTotal.toPlainString()));
        return items;
    }

    public static boolean done(List<Item> items, String key) {
        return items.stream().filter(i -> i.key().equals(key)).allMatch(Item::done);
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
