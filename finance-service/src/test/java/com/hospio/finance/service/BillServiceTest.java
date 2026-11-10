package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.entity.Bill;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.ExpenseCategory;
import com.hospio.finance.repository.BillRepository;
import com.hospio.finance.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Bills: paying one records a paid expense of the same amount and category and adds the next one; the refusals say why. */
class BillServiceTest {

    private final BillRepository bills = mock(BillRepository.class);
    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final ExpenseCategories categories = mock(ExpenseCategories.class);
    private final MoneyService money = mock(MoneyService.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final BillService service = new BillService(bills, expenses, categories, money, events, null);

    private void signIn(Map<Role, AccessLevel> roles) {
        HospioUserDetails u = new HospioUserDetails("u1", "u@x.ng", "Femi Ojo", "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    @org.junit.jupiter.api.BeforeEach
    void stubs() {
        when(categories.labels(any())).thenReturn(Map.of("UTILITIES", "Utilities"));
        when(categories.find(eq("h1"), anyString())).thenAnswer(i -> ExpenseCategories.codeOf(i.getArgument(1)).equals("UTILITIES")
                ? Optional.of(ExpenseCategory.builder().code("UTILITIES").label("Utilities").active(true).build()) : Optional.empty());
        when(bills.save(any())).thenAnswer(i -> {
            Bill b = i.getArgument(0);
            if (b.getId() == null) {
                b.setId(UUID.randomUUID().toString());
            }
            if (b.getHotelId() == null) {
                b.setHotelId("h1");
            }
            return b;
        });
        when(expenses.save(any())).thenAnswer(i -> {
            Expense e = i.getArgument(0);
            e.setId("e1");
            return e;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private Bill unpaid(LocalDate due, Bill.Repeats repeats) {
        Bill b = Bill.builder().payee("Ikeja Electric").category("UTILITIES").amount(new BigDecimal("480000.00")).dueDate(due).repeats(repeats)
                .note("Meter 0042").build();
        b.setId("b1");
        b.setHotelId("h1");
        when(bills.findById("b1")).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void payingRecordsAPaidExpenseOfTheSameAmountAndCategory() {
        signIn(Map.of(Role.FINANCE, AccessLevel.EDIT));
        unpaid(LocalDate.of(2026, 11, 20), Bill.Repeats.NONE);
        BillService.Paid p = service.pay("b1", null);
        assertThat(p.expense().getAmount()).isEqualByComparingTo("480000.00");
        assertThat(p.expense().getCategory()).isEqualTo("UTILITIES");
        assertThat(p.expense().getStatus()).isEqualTo(Expense.Status.PAID);
        assertThat(p.expense().getPaidFrom()).isEqualTo("BANK");
        assertThat(p.expense().getDescription()).isEqualTo("Bill · Ikeja Electric · Meter 0042");
        assertThat(p.bill().status()).isEqualTo("PAID");
        assertThat(p.bill().expenseId()).isEqualTo("e1");
        assertThat(p.next()).isNull();
        verify(events).publish(eq(KafkaTopics.EXPENSE_RECORDED), any());
        verify(money, never()).payOutFromTill(any(), any());
    }

    @Test
    void aMonthlyBillPaidAddsTheNextOneAtTheMonthsEnd() {
        signIn(Map.of(Role.FINANCE, AccessLevel.EDIT));
        unpaid(LocalDate.of(2027, 1, 31), Bill.Repeats.MONTHLY);
        BillService.Paid p = service.pay("b1", new BillService.PayRequest("till"));
        assertThat(p.next().dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(p.next().status()).isEqualTo("UNPAID");
        assertThat(p.next().amount()).isEqualByComparingTo("480000.00");
        assertThat(p.bill().nextBillId()).isEqualTo(p.next().id());
        assertThat(p.expense().getPaidFrom()).isEqualTo("TILL");
        verify(money).payOutFromTill(any(), any());
    }

    @Test
    void nextDueDates() {
        assertThat(BillRules.nextDue(LocalDate.of(2026, 12, 29), Bill.Repeats.WEEKLY)).isEqualTo(LocalDate.of(2027, 1, 5));
        assertThat(BillRules.nextDue(LocalDate.of(2028, 1, 31), Bill.Repeats.MONTHLY)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(BillRules.nextDue(LocalDate.of(2026, 11, 15), Bill.Repeats.MONTHLY)).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(BillRules.nextDue(LocalDate.of(2026, 11, 15), Bill.Repeats.NONE)).isNull();
    }

    @Test
    void aPaidBillIsNotPaidChangedOrCancelledAgain() {
        signIn(Map.of(Role.FINANCE, AccessLevel.EDIT));
        Bill b = unpaid(LocalDate.of(2026, 11, 20), Bill.Repeats.NONE);
        b.setStatus(Bill.Status.PAID);
        assertThatThrownBy(() -> service.pay("b1", null)).isInstanceOf(BadRequestException.class).hasMessage("This bill is already paid");
        assertThatThrownBy(() -> service.cancel("b1")).isInstanceOf(BadRequestException.class).hasMessage("A paid bill can't be cancelled");
        assertThatThrownBy(() -> service.update("b1", new BillService.BillRequest("X", "Utilities", BigDecimal.TEN, LocalDate.now(), null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("A paid bill can't be changed");
    }

    @Test
    void badInputIsRefusedInWords() {
        signIn(Map.of(Role.FINANCE, AccessLevel.EDIT));
        LocalDate d = LocalDate.of(2026, 12, 1);
        assertThatThrownBy(() -> service.create(new BillService.BillRequest(" ", "Utilities", BigDecimal.TEN, d, null, null)))
                .hasMessage("Say who the bill is from");
        assertThatThrownBy(() -> service.create(new BillService.BillRequest("Spectranet", "Utilities", BigDecimal.ZERO, d, null, null)))
                .hasMessage("Enter an amount above zero");
        assertThatThrownBy(() -> service.create(new BillService.BillRequest("Spectranet", "Utilities", BigDecimal.TEN, null, null, null)))
                .hasMessage("Give the due date");
        assertThatThrownBy(() -> service.create(new BillService.BillRequest("Spectranet", "Utilities", BigDecimal.TEN, d, "YEARLY", null)))
                .hasMessage("A bill repeats NONE, WEEKLY or MONTHLY");
        assertThatThrownBy(() -> service.create(new BillService.BillRequest("Spectranet", "Pool toys", BigDecimal.TEN, d, null, null)))
                .hasMessage("That category isn't in this hotel's list");
        unpaid(d, Bill.Repeats.NONE);
        assertThatThrownBy(() -> service.pay("b1", new BillService.PayRequest("CHEQUE"))).hasMessage("Pay a bill from BANK, TILL or CASH");
        BillService.BillView ok = service.create(new BillService.BillRequest(" Spectranet ", "utilities", new BigDecimal("42000"), d, "monthly", " "));
        assertThat(ok.payee()).isEqualTo("Spectranet");
        assertThat(ok.category()).isEqualTo("UTILITIES");
        assertThat(ok.categoryLabel()).isEqualTo("Utilities");
        assertThat(ok.repeats()).isEqualTo("MONTHLY");
        assertThat(ok.note()).isNull();
    }

    @Test
    void onlyManagersTheOwnerOrFinanceHandleBills() {
        signIn(Map.of(Role.FRONT_OFFICE, AccessLevel.FULL));
        assertThatThrownBy(() -> service.pay("b1", null)).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only a manager, the owner or Finance can add, change or pay bills");
        assertThatThrownBy(() -> service.get("b1")).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only managers, the owner and Finance can see the hotel's bills");
        signOut();
        signIn(Map.of(Role.FINANCE, AccessLevel.VIEW));
        assertThatThrownBy(() -> service.cancel("b1")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anOverdueBillIsUnpaidAndPastItsDueDate() {
        Bill b = Bill.builder().payee("P").category("UTILITIES").amount(BigDecimal.ONE).dueDate(LocalDate.of(2026, 11, 9)).build();
        b.setHotelId("h1");
        assertThat(BillService.view(b, Map.of(), LocalDate.of(2026, 11, 10)).overdue()).isTrue();
        assertThat(BillService.view(b, Map.of(), LocalDate.of(2026, 11, 9)).overdue()).isFalse();
        b.setStatus(Bill.Status.PAID);
        assertThat(BillService.view(b, Map.of(), LocalDate.of(2026, 11, 10)).overdue()).isFalse();
    }
}
