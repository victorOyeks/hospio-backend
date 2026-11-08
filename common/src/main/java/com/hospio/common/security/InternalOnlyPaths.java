package com.hospio.common.security;

/**
 * Every service keeps its service-to-service endpoints under {@code /internal/**} in a dedicated
 * controller, gated by {@link com.hospio.common.security.filter.InternalApiKeyFilter} on top of the JWT check,
 * and hidden from Swagger. One fixed prefix instead of per-resource globs that could overlap a public path.
 */
public final class InternalOnlyPaths {

    private InternalOnlyPaths() {
    }

    public static final String[] ALL_SERVICES = {"/internal/**"};
}
