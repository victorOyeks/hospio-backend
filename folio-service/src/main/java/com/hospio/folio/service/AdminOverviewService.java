package com.hospio.folio.service;

import com.hospio.folio.dto.HotelOverviewResponse;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.repository.FolioRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only figures for the platform admin portal; the caller is the platform, so every hotel is visible. */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final FolioRepository folios;

    @Transactional(readOnly = true)
    public HotelOverviewResponse hotelOverview(String hotelId) {
        BigDecimal balance = folios.openBalance(hotelId);
        return new HotelOverviewResponse(folios.countByHotelIdAndStatus(hotelId, Folio.Status.OPEN), balance == null ? BigDecimal.ZERO : balance);
    }
}
