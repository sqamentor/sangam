// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.io.IOException;
import java.util.Collection;
import java.util.function.Function;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.port.SeatHoldings;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the authenticated {@link SangamPrincipal} to the request attribute that sangam-rest reads, and limits the
 * seat context by the token: when the token carries a seats list, {@code X-Sangam-Seat} must name one of them,
 * otherwise 403 {@code SEAT_NOT_HELD}. The token list only limits; it never gives a seat. Whether the person holds the
 * named seat now (IX.5 rules 1 and 4, K7) is decided where the request context is built
 * ({@code RequestContexts.from}) against the {@link SeatHoldings} of the request: resolved by
 * {@code SecurityIdentityFilter} from sangam-security's postings, or, when the security module is off (development
 * only), by the {@code seatDirectory} given here.
 */
public final class PrincipalAttributeFilter extends OncePerRequestFilter {

    public static final String SEAT_HEADER = "X-Sangam-Seat";
    public static final String ACTING_FOR_HEADER = "X-Sangam-Acting-For-Seat";

    private final Function<SangamPrincipal, SeatHoldings> seatDirectory;

    /** With the security module: the holdings come from {@code SecurityIdentityFilter}, which runs after this filter. */
    public PrincipalAttributeFilter() {
        this(null);
    }

    /**
     * @param seatDirectory the caller's holdings when no later filter resolves them (security module off), or null;
     *                      asked only for a request that names a seat
     */
    public PrincipalAttributeFilter(Function<SangamPrincipal, SeatHoldings> seatDirectory) {
        this.seatDirectory = seatDirectory;
    }

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
                ProblemResponses.write(request, response, 403, SeatHoldings.NOT_HELD, "Seat not held",
                        "The seat '" + seat.trim() + "' is not among the seats of your identity");
                return;
            }
            request.setAttribute(SangamPrincipal.ATTRIBUTE, p);
            if (seatDirectory != null && (named(seat) || named(request.getHeader(ACTING_FOR_HEADER)))) {
                request.setAttribute(SeatHoldings.ATTRIBUTE, seatDirectory.apply(p));
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean named(String header) {
        return header != null && !header.isBlank();
    }
}
