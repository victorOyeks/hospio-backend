package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.ActivityEntry;
import com.hospio.platform.repository.ActivityEntryRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The activity log's paging rules: size 1 to 200, newest first, department names checked, dates in order. */
class ActivityLogFilterTest {

    private final ActivityEntryRepository repo = mock(ActivityEntryRepository.class);
    private final ActivityLogService service = new ActivityLogService(repo, new ObjectMapper());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        HospioUserDetails u = new HospioUserDetails("u1", "tunde@x.ng", "Tunde", "b1", List.of("h1"), Map.of(Role.MANAGER, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
        when(repo.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private ActivityLogService.Filter f(String department, LocalDate from, LocalDate to) {
        return new ActivityLogService.Filter(null, null, department, from, to, false);
    }

    @Test
    void sizeIsOneTo200() {
        assertThatThrownBy(() -> service.list(f(null, null, null), 0, 201)).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 200");
        assertThatThrownBy(() -> service.list(f(null, null, null), 0, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.list(f(null, null, null), -1, 20)).isInstanceOf(BadRequestException.class).hasMessage("page counts from 0");
        service.list(f(null, null, null), 0, 200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void newestFirstWithATieBreaker() {
        service.list(f(null, null, null), 2, 50);
        ArgumentCaptor<PageRequest> pr = ArgumentCaptor.forClass(PageRequest.class);
        verify(repo).findAll(any(Specification.class), pr.capture());
        assertThat(pr.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pr.getValue().getSort().getOrderFor("occurredAt").isDescending()).isTrue();
        assertThat(pr.getValue().getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void unknownDepartmentAndBackwardDatesAreRefused() {
        assertThatThrownBy(() -> service.list(f("Spa", null, null), 0, 20)).isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Unknown department Spa");
        assertThatThrownBy(() -> service.list(f(null, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 1)), 0, 20))
                .isInstanceOf(BadRequestException.class).hasMessage("The start date is after the end date");
        Page<ActivityEntry> ok = service.list(f("front office", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)), 0, 20);
        assertThat(ok).isNotNull();
        service.list(f("H.M.S", null, null), 0, 20);
    }

    @Test
    void everyDepartmentHasTopics() {
        assertThat(ActivityLogService.DEPARTMENT_TOPICS).containsKeys("Front office", "Housekeeping", "Finance", "Management");
        ActivityLogService.DEPARTMENT_TOPICS.values().forEach(t -> assertThat(t).isNotEmpty());
    }
}
