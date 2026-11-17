package com.hospio.fnb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.dto.MenuCopyRequest;
import com.hospio.fnb.dto.MenuItemRequest;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuCategoryRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.repository.PriceChangeRequestRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** A patio starts from the kitchen's and the bar's menus: copied once by name, kept made at the kitchen when asked; "made at" is checked. */
class MenuCopyTest {

    private final OutletRepository outlets = mock(OutletRepository.class);
    private final MenuItemRepository items = mock(MenuItemRepository.class);
    private final MenuCategoryRepository categories = mock(MenuCategoryRepository.class);
    private final MenuService service = new MenuService(outlets, mock(DiningTableRepository.class), items, categories, mock(PriceChangeRequestRepository.class),
            mock(HappyHourRepository.class), mock(EventPublisher.class));
    private final Outlet kitchen = Outlet.builder().name("Kitchen").kind(Outlet.Kind.KITCHEN).build();
    private final Outlet patio = Outlet.builder().name("Patio Lounge").kind(Outlet.Kind.BAR).build();
    private final Outlet elsewhere = Outlet.builder().name("Other hotel's kitchen").kind(Outlet.Kind.KITCHEN).build();
    private final List<MenuItem> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kitchen.setId("kitchen");
        kitchen.setHotelId("h1");
        patio.setId("patio");
        patio.setHotelId("h1");
        elsewhere.setId("far");
        elsewhere.setHotelId("h2");
        when(outlets.findById("kitchen")).thenReturn(Optional.of(kitchen));
        when(outlets.findById("patio")).thenReturn(Optional.of(patio));
        when(outlets.findById("far")).thenReturn(Optional.of(elsewhere));
        MenuItem soup = MenuItem.builder().outletId("kitchen").category("Starters").name("Pepper soup").price(new BigDecimal("4000")).build();
        MenuItem rice = MenuItem.builder().outletId("kitchen").category("Mains").name("Jollof rice").price(new BigDecimal("6000")).build();
        MenuItem old = MenuItem.builder().outletId("kitchen").category("Mains").name("Old dish").price(new BigDecimal("1")).active(false).build();
        when(items.findByOutletIdOrderByCategoryAscSortOrderAscNameAsc("kitchen")).thenReturn(List.of(soup, rice, old));
        when(items.findByOutletIdOrderByCategoryAscSortOrderAscNameAsc("patio")).thenReturn(List.of(
                MenuItem.builder().outletId("patio").category("Mains").name("jollof RICE").price(new BigDecimal("6500")).build()));
        when(items.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(categories.findByOutletIdAndName(anyString(), anyString())).thenReturn(Optional.empty());
        when(categories.save(any())).thenAnswer(inv -> inv.getArgument(0));
        signIn(Map.of(Role.MANAGER, AccessLevel.FULL));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static void signIn(Map<Role, AccessLevel> roles) {
        HospioUserDetails u = new HospioUserDetails("u1", "t@x.ng", "Tunde", "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    @Test
    void theKitchensStartersAreCopiedOnceStillMadeInTheKitchen() {
        MenuService.CopyResult r = service.copyMenu("patio", new MenuCopyRequest("kitchen", List.of(), true));
        assertThat(r.copied()).isEqualTo(1);
        assertThat(r.skipped()).as("jollof rice is already on the patio menu (any case)").isEqualTo(1);
        assertThat(saved).singleElement().satisfies(m -> {
            assertThat(m.getOutletId()).isEqualTo("patio");
            assertThat(m.getName()).isEqualTo("Pepper soup");
            assertThat(m.getPrice()).isEqualByComparingTo("4000");
            assertThat(m.getPreparedAt()).isEqualTo("kitchen");
        });
    }

    @Test
    void onlyChosenCategoriesAndMadeHereWhenNotAsked() {
        service.copyMenu("patio", new MenuCopyRequest("kitchen", List.of("starters"), false));
        assertThat(saved).extracting(MenuItem::getName).containsExactly("Pepper soup");
        assertThat(saved.get(0).getPreparedAt()).isNull();
    }

    @Test
    void copyRulesAndWhoMayCopy() {
        assertThatThrownBy(() -> service.copyMenu("patio", new MenuCopyRequest("patio", null, true))).hasMessageContaining("another outlet");
        assertThatThrownBy(() -> service.copyMenu("patio", new MenuCopyRequest("far", null, true))).isInstanceOf(ResourceNotFoundException.class);
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        assertThatThrownBy(() -> service.copyMenu("patio", new MenuCopyRequest("kitchen", null, true))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void madeAtMustBeAnotherOutletOfTheHotel() {
        MenuItemRequest r = new MenuItemRequest("Starters", "Pepper soup", null, new BigDecimal("4000"), null, null, null, null, null, "kitchen");
        assertThat(service.saveItem("patio", null, r).getPreparedAt()).isEqualTo("kitchen");
        MenuItemRequest self = new MenuItemRequest("Starters", "Suya", null, new BigDecimal("3000"), null, null, null, null, null, "patio");
        assertThat(service.saveItem("patio", null, self).getPreparedAt()).as("made where it is sold").isNull();
        MenuItemRequest far = new MenuItemRequest("Starters", "Fish", null, new BigDecimal("3000"), null, null, null, null, null, "far");
        assertThatThrownBy(() -> service.saveItem("patio", null, far)).isInstanceOf(ResourceNotFoundException.class);
    }
}
