package com.hospio.common.exception;

/**
 * The business's Hospio360 invoice is unpaid past its grace period (docs/plan-monthly-invoices.md): new sales, new bookings and card or
 * transfer payments are paused until it is paid. Answered 423 (Locked) with a message the screens show as is.
 */
public class BillingHoldException extends HospioException {

    public BillingHoldException(String message) {
        super(message);
    }
}
