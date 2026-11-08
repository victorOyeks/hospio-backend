package com.hospio.finance.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Finance 1.1 · Today's money and Manager · Financials overview. */
public record MoneyToday(BigDecimal revenue, Map<String, BigDecimal> byMethod, Map<String, BigDecimal> byRole, BigDecimal expenses,
                         BigDecimal cashInTills, int openTills, int paymentsCount) {
}
