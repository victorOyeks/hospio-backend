package com.hospio.folio.service;

import com.hospio.common.billing.DailySales;
import com.hospio.folio.entity.FolioLine;
import com.hospio.folio.repository.FolioLineRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The folio's share of a hotel's daily sales (docs/plan-daily-fees.md), VAT excluded (a line's amount is without its tax).
 *
 * <p>Every charge is cut into pieces, each with the day it counts on: a room line one piece per night it covers, counted on that
 * night (or on the day it was posted, when posted after the night); any other charge one piece on the day it was posted. A piece
 * counts on its day unless the line was voided by then; a line voided after a piece counted takes that piece back on the void's day.
 * So every day's figure is final once the day is over, and running a day again gives the same answer. Discounts count on the day
 * they were given. A line moved to another folio is the same line: it never counts twice.
 */
@Service
@RequiredArgsConstructor
public class SalesReport {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final FolioLineRepository lines;

    @Transactional(readOnly = true)
    public DailySales daily(String hotelId, LocalDate day) {
        Instant from = day.atStartOfDay(LAGOS).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(LAGOS).toInstant();
        return tally(lines.salesCandidates(hotelId, from, to, day), day);
    }

    /** The day's lines from the candidate folio lines (package-visible for tests). */
    static DailySales tally(List<FolioLine> candidates, LocalDate day) {
        Sum room = new Sum();
        Sum extras = new Sum();
        Sum discounts = new Sum();
        Sum voids = new Sum();
        for (FolioLine l : candidates) {
            LocalDate posted = date(l.getPostedAt());
            if (l.getKind() == FolioLine.Kind.DISCOUNT) {
                if (day.equals(posted)) {
                    discounts.add(l.getAmount().negate());
                }
                continue;
            }
            // A void with no time (before it was recorded) is taken as made when the line was posted: it never counted.
            LocalDate voided = !l.isVoided() ? null : l.getVoidedAt() == null ? posted : date(l.getVoidedAt());
            boolean isRoom = l.getServiceFrom() != null && l.getServiceTo() != null;
            Sum bucket = isRoom ? room : extras;
            for (Piece p : pieces(l, posted)) {
                boolean countsThen = voided == null || voided.isAfter(p.day());
                if (p.day().equals(day) && countsThen) {
                    bucket.add(p.amount());
                }
                if (voided != null && voided.equals(day) && voided.isAfter(p.day())) {
                    voids.add(p.amount().negate());
                }
            }
        }
        return DailySales.of(
                new DailySales.Line("FOLIO_ROOM", "Room nights", room.total, room.count),
                new DailySales.Line("FOLIO_EXTRAS", "Other charges on folios", extras.total, extras.count),
                new DailySales.Line("FOLIO_DISCOUNTS", "Discounts on folios", discounts.total, discounts.count),
                new DailySales.Line("FOLIO_VOIDS", "Charges voided", voids.total, voids.count));
    }

    record Piece(LocalDate day, BigDecimal amount) {
    }

    /** A room line's nights (the last night takes the rounding), each counted no earlier than the day it was posted; else one piece. */
    static List<Piece> pieces(FolioLine l, LocalDate posted) {
        BigDecimal amount = l.getAmount() == null ? BigDecimal.ZERO : l.getAmount();
        if (l.getServiceFrom() == null || l.getServiceTo() == null) {
            return List.of(new Piece(posted, amount));
        }
        long nights = ChronoUnit.DAYS.between(l.getServiceFrom(), l.getServiceTo());
        if (nights <= 0) {
            return List.of(new Piece(posted, amount));
        }
        BigDecimal each = amount.divide(BigDecimal.valueOf(nights), 2, RoundingMode.DOWN);
        java.util.ArrayList<Piece> out = new java.util.ArrayList<>();
        for (int i = 0; i < nights; i++) {
            LocalDate night = l.getServiceFrom().plusDays(i);
            BigDecimal share = i == nights - 1 ? amount.subtract(each.multiply(BigDecimal.valueOf(nights - 1))) : each;
            out.add(new Piece(night.isBefore(posted) ? posted : night, share));
        }
        return out;
    }

    static LocalDate date(Instant at) {
        return at == null ? null : at.atZone(LAGOS).toLocalDate();
    }

    static final class Sum {
        BigDecimal total = BigDecimal.ZERO;
        long count;

        void add(BigDecimal amount) {
            total = total.add(amount);
            count++;
        }
    }
}
