package com.hospio.common.integrations;

/** The chosen POS provider is not integrated yet (a placeholder): the payment fails with this message, the terminal stays online. */
public class PosNotConnectedException extends RuntimeException {

    public PosNotConnectedException(String provider) {
        super(provider + " POS is not connected yet. Take cash or a bank transfer for now.");
    }
}
