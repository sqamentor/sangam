// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.io.IOException;
import java.util.Collection;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.SangamPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the authenticated {@link SangamPrincipal} to the request attribute that sangam-rest reads, and enforces the
 * seat context: when the token carries a seats list, {@code X-Sangam-Seat} must name one of them, otherwise
 * 403 {@code SEAT_NOT_HELD}. Whether the seat is held on the action date is checked server-side (IX.5 rule 1, K7).
 */
public final class PrincipalAttributeFilter extends OncePerRequestFilter {

    public static final String SEAT_HEADER = "X-Sangam-Seat";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SangamPrincipal p = request.getAttribute(SangamPrincipal.ATTRIBUTE) instanceof SangamPrincipal existing
                ? existing : auth instanceof SangamAuthenticationToken t ? t.sangamPrincipal() : null;
        if (p != null) {
            String seat = request.getHeader(SEAT_HEADER);
            if (seat != null && !seat.isBlank() && p.claims().get(JwtPrincipalConverter.SEATS) instanceof Collection<?> seats
                    && !seats.contains(seat.trim())) {
                ProblemResponses.write(request, response, 403, "SEAT_NOT_HELD", "Seat not held",
                        "The seat '" + seat.trim() + "' is not among the seats of your identity");
                return;
            }
            request.setAttribute(SangamPrincipal.ATTRIBUTE, p);
        }
        chain.doFilter(request, response);
    }
}
