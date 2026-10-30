package io.sharellm.server.adapter.inbound.web.support;

/** Extracts the credential from an {@code Authorization: Bearer ...} header. */
public final class BearerToken {
    private static final String PREFIX = "Bearer ";

    private BearerToken() {}

    /** @return the token, or an empty string when the header is absent or uses another scheme */
    public static String from(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) return "";
        return authorizationHeader.substring(PREFIX.length()).trim();
    }
}
