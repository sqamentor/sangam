// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * RFC 9457 {@code application/problem+json} responses for the security layer (401 {@code UNAUTHENTICATED},
 * 403 {@code ACTION_NOT_PERMITTED}), with the same shape as the REST layer's problems.
 */
public final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(request, response, 401, "UNAUTHENTICATED", "Unauthenticated",
                e == null || e.getMessage() == null ? "A valid bearer token is required" : e.getMessage());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(request, response, 403, "ACTION_NOT_PERMITTED", "Forbidden",
                "You do not hold the permission for this resource");
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
                             String title, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", ApiProblem.typeOf(code));
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), body);
    }
}
