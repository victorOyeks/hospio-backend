package com.hospio.finance.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Finance 1.1 · Today's money and Manager · Financials overview. {@code occupancyPct}, {@code arrivals}, {@code departures} and
 * {@code inHouse} come from reservation-service and are null when it can't be reached; {@code unattachedCash} is cash taken
 * while no till was open, which the manager has to account for; {@code pendingApprovals} are the approvals waiting at the hotel.
 */
public record MoneyToday(BigDecimal revenue, Map<String, BigDecimal> byMethod, Map<String, BigDecimal> byRole, BigDecimal expenses,
                         BigDecimal cashInTills, int openTills, int paymentsCount, Double occupancyPct, Integer arrivals, Integer departures,
                         Integer inHouse, BigDecimal unattachedCash, int unattachedCashCount, Integer pendingApprovals) {
}
