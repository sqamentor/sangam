// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.billing.UnverifiedPayment;
import org.sangam.billing.UnverifiedPaymentAuthority;
import org.sangam.billing.UnverifiedPaymentException;
import org.sangam.billing.UnverifiedPaymentStore;
import org.sangam.billing.UnverifiedPayments;
import org.sangam.core.money.Money;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/billing/by-ledger/unverified-payments?ledger=} (RM-03, owner decisions 141 and 193-201; contract
 * {@code sangam-rest/api/routes/billing.py}). A thin adapter over {@link UnverifiedPayments}, which enforces every rule
 * itself; the endpoint permissions here are the extra REST layer. The caller is built only from the authenticated
 * request context: no request field names an actor, and its kind of sign-in decides its actor type (decision 201:
 * OIDC and SAML are staff; API keys, system and every other method are refused by the service). The list filters in
 * the store before paging ({@code page}, {@code size}, {@code X-Total-Count}).
 */
@RestController
@RequestMapping("/api/v1/billing/by-ledger/unverified-payments")
public class UnverifiedPaymentController {

    public static final String ACCEPT = "billing.accept-unverified";
    public static final String RESOLVE = "billing.resolve-unverified";

    private final UnverifiedPayments payments;
    private final PermissionPort permissions;
    private final LedgerTenantDefinitions definitions;

    public UnverifiedPaymentController(UnverifiedPayments payments, PermissionPort permissions,
                                       LedgerTenantDefinitions definitions) {
        this.payments = payments;
        this.permissions = permissions;
        this.definitions = definitions;
    }

    public record UnverifiedPaymentRequest(String consumer, String channel, String office, String paymentRef,
                                           String amount, String currency, String paidAt, String payerName,
                                           String instrument, String reason, String evidenceRef,
                                           Map<String, String> details) {
    }

    public record UnverifiedPaymentResolveRequest(String outcome, String reason, String verificationRef,
                                                  String evidenceRef) {
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> accept(RequestContext ctx, @RequestParam("ledger") String ledger,
                                                      @RequestBody UnverifiedPaymentRequest body) {
        permissions.require(ctx, ACCEPT);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a request body is required");
        }
        definitions.ensure(ctx.tenantId());
        String ledgerCurrency = definitions.ledger().definition(ctx.tenantId(), ledger).currency();
        if (body.currency() == null || !body.currency().equals(ledgerCurrency)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "currency must be the ledger currency " + ledgerCurrency);
        }
        Money amount;
        try {
            amount = Money.parse(body.amount(), ledgerCurrency);
        } catch (RuntimeException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount must be a decimal string in " + ledgerCurrency
                    + " with at most its minor-unit digits (MoneyAmount): " + e.getMessage());
        }
        UnverifiedPayments.AcceptRequest r = new UnverifiedPayments.AcceptRequest(ledger, body.consumer(),
                body.office(), body.channel(), body.paymentRef(), amount, instant(body.paidAt()), body.payerName(),
                body.instrument(), body.details(), body.reason(), body.evidenceRef());
        UnverifiedPayment p = call(() -> payments.accept(caller(ctx), ctx.tenantId(), r));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(p));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(RequestContext ctx, @RequestParam("ledger") String ledger,
                                                          @RequestParam(required = false) String state,
                                                          @RequestParam(required = false) String channel,
                                                          @RequestParam(required = false) String office,
                                                          @RequestParam(required = false) Integer minAgeDays,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        permissions.require(ctx, BillingController.READ);
        Page.PageRequest pr = Lists.page(page, size);
        UnverifiedPayment.State s = state == null ? null : parseState(state);
        Instant acceptedBefore = null;
        if (minAgeDays != null) {
            if (minAgeDays < 0) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "minAgeDays must be >= 0");
            }
            if (s != null && !s.pending()) {    // only a pending payment has an age
                return Lists.ok(Page.of(List.of(), 0, false));
            }
            s = UnverifiedPayment.State.PAYMENT_ACCEPTED_UNVERIFIED;
            // ageDays(now) >= n exactly when accepted at or before now - n days
            acceptedBefore = payments.now().minus(Duration.ofDays(minAgeDays));
        }
        UnverifiedPaymentStore.Slice slice = payments.list(ctx.tenantId(), new UnverifiedPaymentStore.Query(ledger, s,
                channel, office, acceptedBefore, pr.firstResult(), pr.size()));
        return Lists.ok(Page.of(slice.items().stream().map(this::view).toList(), slice.total(), false));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @GetMapping("/{id}")
    public Map<String, Object> get(RequestContext ctx, @RequestParam("ledger") String ledger, @PathVariable String id) {
        permissions.require(ctx, BillingController.READ);
        return payments.find(ctx.tenantId(), id).filter(p -> p.ledgerId().equals(ledger)).map(this::view)
                .orElseThrow(() -> ApiProblem.notFound("unverified payment", id));
    }

    /** Decision 393: the value can carry '/' or ':', so it is a required query parameter. */
    @PostMapping("/{id}/resolution")
    public Map<String, Object> resolve(RequestContext ctx, @RequestParam("ledger") String ledger, @PathVariable String id,
                                       @RequestBody UnverifiedPaymentResolveRequest body) {
        permissions.require(ctx, RESOLVE);
        if (body == null || body.outcome() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "outcome is required");
        }
        UnverifiedPayment.State outcome = parseState(body.outcome());
        payments.find(ctx.tenantId(), id).filter(p -> p.ledgerId().equals(ledger))
                .orElseThrow(() -> ApiProblem.notFound("unverified payment", id));
        return view(call(() -> payments.resolve(caller(ctx), ctx.tenantId(), id, outcome, body.reason(),
                body.verificationRef(), body.evidenceRef())));
    }

    /** The caller from the authenticated request context only, typed by how the server authenticated it. */
    static UnverifiedPaymentAuthority.Caller caller(RequestContext ctx) {
        if (ctx.principalId() == null || ctx.principalId().isBlank() || ctx.tenantId() == null
                || ctx.tenantId().isBlank()) {
            throw ApiProblem.notPermitted(ACCEPT);
        }
        return new UnverifiedPaymentAuthority.Caller(ctx.principalId(), ctx.tenantId(), ctx.seatId(),
                actorType(ctx.authMethod()));
    }

    /**
     * Decision 201: OIDC and SAML sign-ins are staff; API_KEY and SYSTEM are what they say; any other method (a
     * development token included) is a service identity. Only staff may act; the service refuses the rest.
     */
    static UnverifiedPaymentAuthority.ActorType actorType(String authMethod) {
        String m = authMethod == null ? "" : authMethod.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (m) {
            case "OIDC", "SAML" -> UnverifiedPaymentAuthority.ActorType.STAFF;
            case "API_KEY" -> UnverifiedPaymentAuthority.ActorType.API_KEY;
            case "SYSTEM" -> UnverifiedPaymentAuthority.ActorType.SYSTEM;
            default -> UnverifiedPaymentAuthority.ActorType.SERVICE;
        };
    }

    private static UnverifiedPayment call(java.util.function.Supplier<UnverifiedPayment> work) {
        try {
            return work.get();
        } catch (UnverifiedPaymentException e) {
            throw switch (e.code()) {
                case NOT_PERMITTED, CHANNEL_NOT_ENABLED -> ApiProblem.forbidden(e.code().name(), e.getMessage());
                case DUPLICATE -> ApiProblem.conflict("DUPLICATE", e.getMessage());
                case INVALID_TRANSITION -> ApiProblem.conflict("INVALID_TRANSITION", e.getMessage());
                case NOT_FOUND -> ApiProblem.notFound("unverified payment", e.getMessage());
                case LIMIT_EXCEEDED -> ApiProblem.unprocessable("LIMIT_EXCEEDED", e.getMessage());
                case INVALID_REQUEST -> ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage());
            };
        }
    }

    private static UnverifiedPayment.State parseState(String s) {
        try {
            return UnverifiedPayment.State.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "unknown state " + s);
        }
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank()) {
            return null;    // the service refuses a missing paidAt
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "paidAt must be an RFC 3339 date-time: " + s);
        }
    }

    private Map<String, Object> view(UnverifiedPayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("ledger", p.ledgerId());
        m.put("consumer", p.consumer());
        m.put("channel", p.channel());
        m.put("office", p.office());
        m.put("paymentRef", p.paymentRef());
        m.put("amount", p.amount().toPlainString());
        m.put("currency", p.amount().currencyCode());
        m.put("paidAt", p.paidAt().toString());
        m.put("state", p.state().name());
        m.put("reason", p.reason());
        m.put("evidenceRef", p.evidenceRef());
        m.put("acceptedBy", p.acceptedBy());
        m.put("acceptedAt", p.acceptedAt().toString());
        m.put("ageDays", p.ageDays(payments.now()));
        m.put("policyVersion", p.policyVersion());
        m.put("downstreamPermitted", p.downstreamPermitted());
        if (p.resolution() != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("outcome", p.resolution().outcome().name());
            r.put("reason", p.resolution().reason());
            if (p.resolution().verificationRef() != null) {
                r.put("verificationRef", p.resolution().verificationRef());
            }
            if (p.resolution().evidenceRef() != null) {
                r.put("evidenceRef", p.resolution().evidenceRef());
            }
            r.put("resolvedBy", p.resolution().resolvedBy());
            r.put("resolvedAt", p.resolution().resolvedAt().toString());
            m.put("resolution", r);
        }
        return m;
    }
}
