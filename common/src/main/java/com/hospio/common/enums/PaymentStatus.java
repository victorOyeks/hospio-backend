package com.hospio.common.enums;

/** Pending → sent to the POS → the POS answers; cash is approved on the spot; Verify settles a pending one. */
public enum PaymentStatus { PENDING, PROCESSING, APPROVED, DECLINED, NO_RESPONSE, FAILED, REFUNDED }
