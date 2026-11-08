package com.hospio.folio.dto;

import java.math.BigDecimal;

/** The admin portal's hotel card: open folios and what they owe in total. */
public record HotelOverviewResponse(long openFolios, BigDecimal openBalance) {
}
