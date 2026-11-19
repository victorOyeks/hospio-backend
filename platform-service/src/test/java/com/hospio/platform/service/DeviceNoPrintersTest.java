package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DeviceCatalogueItem;
import com.hospio.platform.entity.DevicePackage;
import com.hospio.platform.entity.DevicePackageItem;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.DeviceCatalogueRepository;
import com.hospio.platform.repository.DeviceInvoiceRepository;
import com.hospio.platform.repository.DevicePackageItemRepository;
import com.hospio.platform.repository.DevicePackageRepository;
import com.hospio.platform.repository.DevicePaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No receipt printers (19 Nov 2026): the switched-off printer can't go on a new package or be offered again; a draft that has one keeps it. */
class DeviceNoPrintersTest {

    private final DeviceCatalogueRepository catalogue = mock(DeviceCatalogueRepository.class);
    private final DevicePackageRepository packages = mock(DevicePackageRepository.class);
    private final DevicePackageItemRepository items = mock(DevicePackageItemRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final DevicePackageService service = new DevicePackageService(catalogue, packages, items, mock(DevicePaymentRepository.class),
            mock(DailyFeeRepository.class), tenant, new TransactionTemplate(mock(PlatformTransactionManager.class)), mock(DeviceInvoiceRepository.class),
            mock(LegalService.class));

    private static final String PRINTER = "dc000000-0000-4000-8000-000000000003";

    @BeforeEach
    void setUp() {
        HospioUserDetails admin = new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria Nwosu", null, List.of(), Map.of(Role.SUPER_ADMIN,
                com.hospio.common.enums.AccessLevel.EDIT), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(tenant.business("b1")).thenReturn(Optional.of(new TenantClient.Business("b1", "Royal Suites", "Lagos", "LIVE", null, null, null, null, null,
                "PERCENT_OF_SALES", new BigDecimal("1.5"), null, null, null, null, 1, List.of(new TenantClient.Hotel("h1", "Simpas Ikeja", "Lagos", 120, "ACTIVE", null)))));
        DeviceCatalogueItem printer = DeviceCatalogueItem.builder().name("Receipt printer").kind("ONE_OFF").unitPrice(new BigDecimal("85000")).active(false).build();
        printer.setId(PRINTER);
        when(catalogue.findById(PRINTER)).thenReturn(Optional.of(printer));
        when(catalogue.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(packages.save(any())).thenAnswer(inv -> {
            DevicePackage p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId("pk1");
            }
            return p;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static DevicePackageService.ItemRequest tablet() {
        return new DevicePackageService.ItemRequest("dc1", "Tablet", "ONE_OFF", 1, new BigDecimal("180000"));
    }

    private static DevicePackageService.ItemRequest printer() {
        return new DevicePackageService.ItemRequest(PRINTER, "Receipt printer", "ONE_OFF", 1, new BigDecimal("85000"));
    }

    @Test
    void aNewPackageCantTakeTheSwitchedOffPrinterOrATypedOne() {
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("ONE_TIME", List.of(tablet(), printer()), null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage(DevicePackageService.NO_PRINTERS);
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("ONE_TIME",
                List.of(tablet(), new DevicePackageService.ItemRequest(null, "Thermal printer 80mm", "ONE_OFF", 1, new BigDecimal("50000"))), null, null, null)))
                .hasMessage(DevicePackageService.NO_PRINTERS);
    }

    @Test
    void aDraftThatAlreadyListsAPrinterKeepsIt() {
        DevicePackage draft = DevicePackage.builder().businessId("b1").hotelId("h1").status(DevicePackage.DRAFT).paymentOption("ONE_TIME")
                .total(new BigDecimal("265000")).paid(BigDecimal.ZERO).build();
        draft.setId("pk1");
        when(packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc("h1", DevicePackage.CANCELLED)).thenReturn(Optional.of(draft));
        when(items.findByPackageIdOrderByCreatedAtAsc("pk1")).thenReturn(List.of(DevicePackageItem.builder().packageId("pk1").catalogueId(PRINTER)
                .name("Receipt printer").kind("ONE_OFF").quantity(1).unitPrice(new BigDecimal("85000")).lineTotal(new BigDecimal("85000")).build()));
        assertThat(service.save("b1", "h1", new DevicePackageService.SaveRequest("ONE_TIME", List.of(tablet(), printer()), null, null, null)).total())
                .isEqualByComparingTo("265000");
    }

    @Test
    void thePrinterCantBeOfferedAgain() {
        assertThatThrownBy(() -> service.saveCatalogueItem(PRINTER, new DevicePackageService.CatalogueRequest("Receipt printer", "ONE_OFF",
                new BigDecimal("85000"), true, 3))).hasMessage(DevicePackageService.NO_PRINTERS);
        assertThatThrownBy(() -> service.saveCatalogueItem(null, new DevicePackageService.CatalogueRequest("Receipt printer", "ONE_OFF",
                new BigDecimal("85000"), null, 3))).hasMessage(DevicePackageService.NO_PRINTERS);
        assertThat(service.saveCatalogueItem(PRINTER, new DevicePackageService.CatalogueRequest("Receipt printer", "ONE_OFF",
                new BigDecimal("90000"), false, 3)).active()).isFalse();
    }
}
