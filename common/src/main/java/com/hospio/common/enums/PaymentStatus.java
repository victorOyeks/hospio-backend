package com.hospio.common.enums;

/**
 * Pending → sent to the POS → the POS answers; cash is approved on the spot; Verify settles a pending one. TIMED_OUT: the POS gave
 * no final answer within the time allowed (5 minutes by default); a late answer from the provider still settles it.
 */
public enum PaymentStatus { PENDING, PROCESSING, APPROVED, DECLINED, NO_RESPONSE, FAILED, REFUNDED, TIMED_OUT }
