package com.hospio.fnb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.dto.PriceChangeRequestDto;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.PriceChangeRequest;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuCategoryRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.repository.PriceChangeRequestRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** DEF-086: a cook's price change shows as waiting on the menu, a second one is refused until the manager decides, a question keeps it waiting. */
class PriceChangeTest {

    private final OutletRepository outlets = mock(OutletRepository.class);
    private final MenuItemRepository items = mock(MenuItemRepository.class);
    private final PriceChangeRequestRepository priceChanges = mock(PriceChangeRequestRepository.class);
    private final MenuService service = new MenuService(outlets, mock(DiningTableRepository.class), items, mock(MenuCategoryRepository.class), priceChanges,
            mock(HappyHourRepository.class), mock(EventPublisher.class));
    private final Outlet kitchen = Outlet.builder().name("Restaurant").kind(Outlet.Kind.KITCHEN).build();
    private final MenuItem pancakes = MenuItem.builder().outletId("kitchen").category("Light bites").name("Pancakes").price(new BigDecimal("3500.00")).build();
    private final List<PriceChangeRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kitchen.setId("kitchen");
        pancakes.setId("pancakes");
        when(outlets.findById("kitchen")).thenReturn(Optional.of(kitchen));
        when(items.findById("pancakes")).thenReturn(Optional.of(pancakes));
        when(items.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(items.findByOutletIdAndActiveTrueOrderByCategoryAscSortOrderAscNameAsc("kitchen")).thenReturn(List.of(pancakes));
        when(priceChanges.save(any())).thenAnswer(inv -> {
            PriceChangeRequest pc = inv.getArgument(0);
            pc.setId("pc-" + (requests.size() + 1));
            requests.add(0, pc);
            return pc;
        });
        when(priceChanges.findByMenuItemIdInAndStatusOrderByCreatedAtDesc(anyCollection(), eq("PENDING"))).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return requests.stream().filter(r -> ids.contains(r.getMenuItemId()) && "PENDING".equals(r.getStatus())).toList();
        });
        when(priceChanges.findByApprovalId(any())).thenAnswer(inv -> requests.stream().filter(r -> r.getApprovalId().equals(inv.getArgument(0))).findFirst());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static void signIn(Map<Role, AccessLevel> roles) {
        HospioUserDetails u = new HospioUserDetails("u1", "c@x.ng", "QA Cook", "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    @Test
    void aCooksChangeWaitsShowsOnTheMenuAndASecondOneIsRefused() {
        signIn(Map.of(Role.KITCHEN, AccessLevel.EDIT));
        MenuService.PriceChangeResult r = service.changePrice("pancakes", new PriceChangeRequestDto(new BigDecimal("3800"), "flour went up"));

        assertThat(r.pending()).isNotNull();
        assertThat(pancakes.getPrice()).isEqualByComparingTo("3500");
        assertThat(service.menu("kitchen", false).get(0).getPendingPrice()).isEqualByComparingTo("3800");
        assertThatThrownBy(() -> service.changePrice("pancakes", new PriceChangeRequestDto(new BigDecimal("4000"), "again")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("₦3,800 for Pancakes is already waiting for the manager's approval");
    }

    @Test
    void aQuestionKeepsItWaitingAndTheDecisionClearsItSoTheCookMayAskAgain() {
        signIn(Map.of(Role.KITCHEN, AccessLevel.EDIT));
        service.changePrice("pancakes", new PriceChangeRequestDto(new BigDecimal("3800"), null));
        String approval = requests.get(0).getApprovalId();

        service.applyApprovedPriceChange(approval, "QUESTION");
        assertThat(service.menu("kitchen", false).get(0).getPendingPrice()).isEqualByComparingTo("3800");

        service.applyApprovedPriceChange(approval, "DECLINED");
        assertThat(service.menu("kitchen", false).get(0).getPendingPrice()).isNull();
        assertThat(pancakes.getPrice()).isEqualByComparingTo("3500");
        assertThat(service.changePrice("pancakes", new PriceChangeRequestDto(new BigDecimal("3700"), null)).pending()).isNotNull();
    }

    @Test
    void fullAccessChangesThePriceAtOnce() {
        signIn(Map.of(Role.KITCHEN, AccessLevel.FULL));
        MenuService.PriceChangeResult r = service.changePrice("pancakes", new PriceChangeRequestDto(new BigDecimal("3800"), null));

        assertThat(r.pending()).isNull();
        assertThat(pancakes.getPrice()).isEqualByComparingTo("3800");
        assertThat(pancakes.getPendingPrice()).isNull();
    }
}
