package com.hospio.common.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hospio.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

/**
 * Answers a request a filter refuses with the same JSON body as every other error. {@code response.sendError} would
 * hand it to the container's /error page, which drops the message, so the apps saw a bare 403 and fell back to
 * "could not be saved".
 */
final class FilterErrors {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private FilterErrors() {
    }

    static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.error(message)));
    }
}
