package com.hospio.finance.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.hospio.common.delivery.Delivery;
import com.hospio.finance.service.NightAuditChecklist;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The night audit for a day: the stored audit when there is one (id is null before the day is closed), the computed
 * {@code checklist} and the day's counts. {@code savedChecklist} is the free text saved with PUT /night-audit/checklist
 * (it used to be called {@code checklist}). {@code report} is the closing report as an object, present once closed.
 */
public record NightAuditView(String id, LocalDate businessDate, String status, BigDecimal revenue, BigDecimal cashCounted, BigDecimal variance,
                             String closedBy, Instant closedAt, String note, @JsonRawValue String report, String savedChecklist,
                             List<NightAuditChecklist.Item> checklist, int arrivals, int departures, int inHouse, int noShows,
                             int roomsOccupied, int rooms, List<Delivery> deliveries) {
}
