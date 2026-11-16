package com.hospio.payment.pos;

import com.hospio.common.billing.FeeDebitDtos.DebitRequest;
import com.hospio.common.billing.FeeDebitDtos.DebitResult;
import com.hospio.common.billing.FeeDebitDtos.ProviderState;
import com.hospio.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs Hospio's daily fee debit for platform-service (docs/plan-daily-fees.md), from the hotel's own provider account.
 *
 * <ul>
 *   <li><b>OPay</b>: OPay's payout API pays out of the hotel's merchant account (with the hotel's keys) into Hospio360's account.
 *   Configured when FEE_DEBIT_OPAY_ENABLED=true and Hospio's receiving account is set.</li>
 *   <li><b>Moniepoint</b>: no debit API yet. FEE_DEBIT_MONIEPOINT_URL marks it configured once Moniepoint provides one; until the
 *   call is built a debit fails with that message (nothing is taken).</li>
 * </ul>
 * One row per reference (FeeDebit): a repeat with the same reference answers the first one's result, never debits twice.
 */
@Slf4j
@Service
public class FeeDebitService {

    private final FeeDebitRepository debits;
    private final HotelPosService hotels;
    private final OpayApi opay;
    private final TransactionTemplate tx;
    private final boolean opayEnabled;
    private final String opayAccount;
    private final String opayAccountName;
    private final String opayBankCode;
    private final String opayPayoutType;
    private final String moniepointUrl;

    public FeeDebitService(FeeDebitRepository debits, HotelPosService hotels, OpayApi opay, TransactionTemplate tx,
                           @Value("${hospio.fee-debit.opay.enabled:false}") boolean opayEnabled,
                           @Value("${hospio.fee-debit.opay.recipient-account:}") String opayAccount,
                           @Value("${hospio.fee-debit.opay.recipient-name:}") String opayAccountName,
                           @Value("${hospio.fee-debit.opay.recipient-bank-code:}") String opayBankCode,
                           @Value("${hospio.fee-debit.opay.payout-type:OPAY_WALLET}") String opayPayoutType,
                           @Value("${hospio.fee-debit.moniepoint.url:}") String moniepointUrl) {
        this.debits = debits;
        this.hotels = hotels;
        this.opay = opay;
        this.tx = tx;
        this.opayEnabled = opayEnabled;
        this.opayAccount = opayAccount == null ? "" : opayAccount.trim();
        this.opayAccountName = opayAccountName == null ? "" : opayAccountName.trim();
        this.opayBankCode = opayBankCode == null ? "" : opayBankCode.trim();
        this.opayPayoutType = opayPayoutType == null ? "OPAY_WALLET" : opayPayoutType.trim().toUpperCase(Locale.ROOT);
        this.moniepointUrl = moniepointUrl == null ? "" : moniepointUrl.trim();
    }

    public List<ProviderState> providers(String hotelId) {
        String keysFor = hotels.credentials(hotelId).map(HotelPosService.Credentials::provider).orElse(null);
        String opayWhy = !opayEnabled ? "FEE_DEBIT_OPAY_ENABLED isn't on" : opayAccount.isEmpty() ? "Hospio's receiving account (FEE_DEBIT_OPAY_RECIPIENT_ACCOUNT) isn't set"
                : "BANK_TRANSFER".equals(opayPayoutType) && opayBankCode.isEmpty() ? "FEE_DEBIT_OPAY_RECIPIENT_BANK_CODE isn't set" : null;
        String moniepointWhy = moniepointUrl.isEmpty() ? "Moniepoint hasn't given a debit API yet (FEE_DEBIT_MONIEPOINT_URL isn't set)" : null;
        return List.of(
                new ProviderState(HotelPosService.MONIEPOINT, moniepointWhy == null, HotelPosService.MONIEPOINT.equals(keysFor), moniepointWhy),
                new ProviderState(HotelPosService.OPAY, opayWhy == null, HotelPosService.OPAY.equals(keysFor), opayWhy));
    }

    public DebitResult debit(DebitRequest r) {
        if (r.reference() == null || r.reference().isBlank() || r.amount() == null || r.amount().signum() <= 0) {
            throw new BadRequestException("A debit needs a reference and an amount over zero");
        }
        FeeDebit seen = debits.findByReference(r.reference()).orElse(null);
        if (seen != null) {
            return "PENDING".equals(seen.getStatus()) ? status(r.hotelId(), r.reference()) : result(seen);
        }
        String provider = r.provider() == null ? "" : r.provider().toUpperCase(Locale.ROOT);
        HotelPosService.Credentials c = hotels.credentials(r.hotelId()).filter(x -> x.provider().equals(provider)).orElse(null);
        FeeDebit d = tx.execute(st -> debits.save(FeeDebit.builder().businessId(c == null ? "unknown" : c.businessId()).hotelId(r.hotelId())
                .reference(r.reference()).provider(provider).amount(r.amount()).status("PENDING").build()));
        if (c == null) {
            return finish(d, DebitResult.failed("The hotel has no " + provider.toLowerCase(Locale.ROOT) + " keys"));
        }
        if (HotelPosService.MONIEPOINT.equals(provider)) {
            return finish(d, DebitResult.failed(moniepointUrl.isEmpty() ? "Moniepoint debit isn't configured"
                    : "Moniepoint's debit call isn't built yet: their API spec is needed"));
        }
        if (!opayEnabled || opayAccount.isEmpty()) {
            return finish(d, DebitResult.failed("OPay debit isn't configured on the server"));
        }
        try {
            long kobo = r.amount().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
            OpayApi.Answer<ProviderDtos.OpayPayoutData> a = opay.payout(c.opay(), r.reference(), kobo, opayPayoutType, opayAccount, opayAccountName,
                    opayBankCode, r.narration() == null ? "Hospio360 fee" : r.narration());
            return finish(d, payout(a, r.amount()));
        } catch (Exception e) {
            // Not knowing is pending: the reference is OPay's idempotency key, so asking again later is safe.
            log.warn("OPay fee debit {} not answered: {}", r.reference(), e.getMessage());
            return finish(d, DebitResult.pending("OPay didn't answer: " + e.getMessage()));
        }
    }

    public DebitResult status(String hotelId, String reference) {
        FeeDebit d = debits.findByReference(reference).orElse(null);
        if (d == null) {
            return DebitResult.failed("No debit with that reference");
        }
        if (!"PENDING".equals(d.getStatus()) || !HotelPosService.OPAY.equals(d.getProvider())) {
            return result(d);
        }
        HotelPosService.Credentials c = hotels.credentials(d.getHotelId()).orElse(null);
        if (c == null) {
            return result(d);
        }
        try {
            return finish(d, payout(opay.queryPayout(c.opay(), reference), d.getAmount()));
        } catch (Exception e) {
            return DebitResult.pending("OPay didn't answer: " + e.getMessage());
        }
    }

    /** OPay's payout answer: SUCCESS-like is taken, FAIL-like or a refusal is not, code 11099 (already asked) or anything else waits. */
    static DebitResult payout(OpayApi.Answer<ProviderDtos.OpayPayoutData> a, BigDecimal amount) {
        if ("11099".equals(a.code())) {
            return DebitResult.pending("OPay already has this debit; waiting for its result");
        }
        if (!a.ok()) {
            return DebitResult.failed("OPay: " + a.code() + " " + a.message());
        }
        ProviderDtos.OpayPayoutData d = a.data();
        String s = d == null || d.status() == null ? "" : d.status().toUpperCase(Locale.ROOT);
        String ref = d == null ? null : d.opayOrderNumber();
        return switch (s) {
            case "SUCCESS", "SUCCESSFUL", "COMPLETED" -> new DebitResult("APPROVED", amount, ref, null);
            case "FAIL", "FAILED", "CLOSED" -> new DebitResult("FAILED", BigDecimal.ZERO, ref, d.failureReason() == null ? "OPay: " + s : "OPay: " + d.failureReason());
            default -> new DebitResult("PENDING", BigDecimal.ZERO, ref, "OPay: " + (s.isEmpty() ? "processing" : s));
        };
    }

    private DebitResult finish(FeeDebit d, DebitResult r) {
        tx.executeWithoutResult(st -> {
            FeeDebit fresh = debits.findById(d.getId()).orElse(d);
            fresh.setStatus(r.status());
            fresh.setProviderRef(r.providerRef() == null ? fresh.getProviderRef() : r.providerRef());
            fresh.setMessage(r.message() == null ? null : r.message().length() > 500 ? r.message().substring(0, 500) : r.message());
            debits.save(fresh);
        });
        return r;
    }

    private static DebitResult result(FeeDebit d) {
        return new DebitResult(d.getStatus(), "APPROVED".equals(d.getStatus()) ? d.getAmount() : BigDecimal.ZERO, d.getProviderRef(), d.getMessage());
    }
}
