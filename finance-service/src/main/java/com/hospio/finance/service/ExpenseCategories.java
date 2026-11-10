package com.hospio.finance.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.entity.ExpenseCategory;
import com.hospio.finance.repository.ExpenseCategoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each hotel's expense categories (Manager · Financials). A category's code is its label normalised ({@link #codeOf}), so the code
 * stored on an expense, a budget or a bill matches older expenses that carry the label ("Fuel & power") or a staff screen's code
 * (REPAIRS). A hotel with none gets the standard list on its first read.
 */
@Service
@RequiredArgsConstructor
public class ExpenseCategories {

    /** The list every hotel starts with, in this order. */
    public static final List<String> DEFAULTS = List.of("Fuel & power", "Kitchen supplies", "Bar supplies", "Housekeeping", "Laundry", "Repairs",
            "Utilities", "Staff welfare", "Marketing", "Transport", "Office", "Other");
    static final String OTHER = "OTHER";

    private final ExpenseCategoryRepository categories;

    public record CategoryView(String id, String code, String label, int sortOrder, boolean active) {
        static CategoryView of(ExpenseCategory c) {
            return new CategoryView(c.getId(), c.getCode(), c.getLabel(), c.getSortOrder(), c.isActive());
        }
    }

    public record CategoryRequest(String label, Boolean active) {
    }

    /**
     * The key a category is known by: upper case, "&amp;" read as AND, every run of other characters one underscore, none at the ends.
     * "Fuel &amp; power", "FUEL_AND_POWER" and "fuel and power" are all FUEL_AND_POWER; null or blank is "".
     */
    public static String codeOf(String s) {
        if (s == null) {
            return "";
        }
        String c = s.trim().toUpperCase(Locale.ROOT).replace("&", " AND ").replaceAll("[^A-Z0-9]+", "_");
        return c.replaceAll("^_+|_+$", "");
    }

    /** A code with no category behind it, read as words: REPAIRS → Repairs, SUPPLIES → Supplies; typed text stays as typed. */
    public static String humanise(String category) {
        if (category == null || category.isBlank()) {
            return "Expense";
        }
        if (!category.matches("[A-Z0-9_]+")) {
            return category.trim();
        }
        String w = category.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** The hotel's categories in list order (seeding the standard list the first time); archived ones only with {@code all}. */
    @Transactional
    public List<CategoryView> list(boolean all) {
        return all(TenantContext.requireHotel()).stream().filter(c -> all || c.isActive()).map(CategoryView::of).toList();
    }

    /** Every category of the hotel, archived included, seeded on the first call. */
    @Transactional
    public List<ExpenseCategory> all(String hotelId) {
        List<ExpenseCategory> found = categories.findByHotelIdOrderBySortOrderAscLabelAsc(hotelId);
        if (!found.isEmpty()) {
            return found;
        }
        List<ExpenseCategory> seeded = new ArrayList<>();
        for (int i = 0; i < DEFAULTS.size(); i++) {
            seeded.add(ExpenseCategory.builder().code(codeOf(DEFAULTS.get(i))).label(DEFAULTS.get(i)).sortOrder(i + 1).build());
        }
        return categories.saveAll(seeded);
    }

    /** The hotel's category a code or a label names, archived ones included. */
    @Transactional
    public Optional<ExpenseCategory> find(String hotelId, String codeOrLabel) {
        String code = codeOf(codeOrLabel);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        String label = codeOrLabel.trim();
        List<ExpenseCategory> list = all(hotelId);
        // The code first; then the label as it reads now (a renamed category keeps its old code).
        return list.stream().filter(c -> c.getCode().equals(code)).findFirst()
                .or(() -> list.stream().filter(c -> c.getLabel().equalsIgnoreCase(label) || codeOf(c.getLabel()).equals(code)).findFirst());
    }

    /** What an expense stores: the hotel's code when the value names one of its categories, otherwise the value as given. */
    @Transactional
    public String normalise(String hotelId, String category) {
        return find(hotelId, category).map(ExpenseCategory::getCode).orElse(category == null ? null : category.trim());
    }

    /** code → label for every category of the hotel, archived included. */
    @Transactional
    public Map<String, String> labels(String hotelId) {
        Map<String, String> out = new LinkedHashMap<>();
        // Read only (no seeding here): callers in read-only transactions use it; an unseeded hotel's codes read as words.
        categories.findByHotelIdOrderBySortOrderAscLabelAsc(hotelId).forEach(c -> out.put(c.getCode(), c.getLabel()));
        return out;
    }

    /** The label of a stored category: the hotel's label for its code, else the value read as words. */
    public static String labelOf(Map<String, String> labels, String stored) {
        String l = labels.get(codeOf(stored));
        return l != null ? l : humanise(stored);
    }

    @Transactional
    public CategoryView add(CategoryRequest request) {
        requireManager();
        String label = cleanLabel(request == null ? null : request.label());
        String hotel = TenantContext.requireHotel();
        List<ExpenseCategory> existing = all(hotel);
        String code = codeOf(label);
        Optional<ExpenseCategory> same = existing.stream().filter(c -> c.getCode().equals(code)).findFirst();
        if (same.isPresent()) {
            ExpenseCategory c = same.get();
            if (c.isActive()) {
                throw new ConflictException(c.getLabel() + " is already a category");
            }
            c.setActive(true);
            c.setLabel(label);
            return CategoryView.of(categories.save(c));
        }
        int next = existing.stream().mapToInt(ExpenseCategory::getSortOrder).max().orElse(0) + 1;
        return CategoryView.of(categories.save(ExpenseCategory.builder().code(code).label(label).sortOrder(next).build()));
    }

    /** Rename (the code stays, so past expenses keep their category) or archive / bring back. */
    @Transactional
    public CategoryView update(String id, CategoryRequest request) {
        requireManager();
        String hotel = TenantContext.requireHotel();
        ExpenseCategory c = categories.findById(id).filter(x -> hotel.equals(x.getHotelId()))
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (request == null || (request.label() == null && request.active() == null)) {
            throw new BadRequestException("Nothing to change");
        }
        if (request.label() != null) {
            String label = cleanLabel(request.label());
            String code = codeOf(label);
            boolean clash = all(hotel).stream().anyMatch(x -> !x.getId().equals(c.getId()) && x.getCode().equals(code));
            if (clash) {
                throw new ConflictException(label + " is already a category");
            }
            c.setLabel(label);
        }
        if (request.active() != null) {
            if (!request.active() && OTHER.equals(c.getCode())) {
                throw new BadRequestException("Other is always available");
            }
            c.setActive(request.active());
        }
        return CategoryView.of(categories.save(c));
    }

    static String cleanLabel(String label) {
        String l = label == null ? "" : label.trim().replaceAll("\\s+", " ");
        if (codeOf(l).isEmpty()) {
            throw new BadRequestException("Give the category a name");
        }
        if (l.length() > 80) {
            throw new BadRequestException("Keep the name under 80 characters");
        }
        return l;
    }

    private static void requireManager() {
        HospioUserDetails u = CurrentUser.require();
        if (u.isReadOnly() || !(u.isManagerOrOwner() || u.isPlatform())) {
            throw new ForbiddenException("Only a manager can change expense categories");
        }
    }
}
