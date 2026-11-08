package com.hospio.common.exception;

/** 410: the thing existed but is no longer usable, e.g. an invite that expired or was already accepted. */
public class GoneException extends HospioException {

    public GoneException(String message) {
        super(message);
    }
}
