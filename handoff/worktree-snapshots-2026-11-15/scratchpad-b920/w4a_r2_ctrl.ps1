$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-rest\src\main\java\org\sangam\rest\modules\billing\UnverifiedPaymentController.java"
$script:s = [IO.File]::ReadAllText($p).Replace("`r`n", "`n")
function Rep([string]$o, [string]$n) {
    $o = $o.Replace("`r`n", "`n"); $n = $n.Replace("`r`n", "`n")
    if (-not $script:s.Contains($o)) { throw "missing: $o" }
    $script:s = $script:s.Replace($o, $n)
}
Rep "import java.time.Instant;`nimport java.time.OffsetDateTime;`nimport java.util.ArrayList;`nimport java.util.LinkedHashMap;" "import java.time.Duration;`nimport java.time.Instant;`nimport java.time.OffsetDateTime;`nimport java.util.LinkedHashMap;"
Rep "import org.sangam.billing.UnverifiedPaymentException;`n" "import org.sangam.billing.UnverifiedPaymentException;`nimport org.sangam.billing.UnverifiedPaymentStore;`n"
Rep "import org.sangam.rest.port.ApiProblem;`n" "import org.sangam.rest.port.ApiProblem;`nimport org.sangam.rest.port.Page;`n"
Rep "import org.sangam.rest.port.RequestContext;`n" "import org.sangam.rest.port.RequestContext;`nimport org.sangam.rest.web.Lists;`n"
Rep " * {@code /api/v1/billing/{ledger}/unverified-payments} (RM-03, owner decision 141; contract" " * {@code /api/v1/billing/{ledger}/unverified-payments} (RM-03, owner decisions 141 and 193-201; contract"
Rep " * request context: no request field names an actor.`n */" " * request context: no request field names an actor, and its kind of sign-in decides its actor type (decision 201:`n * OIDC and SAML are staff; API keys, system and every other method are refused by the service). The list filters in`n * the store before paging ({@code page}, {@code size}, {@code X-Total-Count}).`n */"
Rep "    private static final int LIMIT = 500;`n`n" ""
Rep "    public record UnverifiedPaymentResolveRequest(String outcome, String reason, String verificationRef) {" "    public record UnverifiedPaymentResolveRequest(String outcome, String reason, String verificationRef,`n                                                  String evidenceRef) {"
Rep @'
    public List<Map<String, Object>> list(RequestContext ctx, @PathVariable String ledger,
                                          @RequestParam(required = false) String state,
                                          @RequestParam(required = false) String channel,
                                          @RequestParam(required = false) String office,
                                          @RequestParam(required = false) Integer minAgeDays) {
        permissions.require(ctx, BillingController.READ);
        UnverifiedPayment.State s = state == null ? null : parseState(state);
        List<Map<String, Object>> out = new ArrayList<>();
        Instant now = payments.now();
        for (UnverifiedPayment p : payments.list(ctx.tenantId(), s, LIMIT)) {
            if (p.ledgerId().equals(ledger) && (channel == null || channel.equals(p.channel()))
                    && (office == null || office.equals(p.office()))
                    && (minAgeDays == null || (p.state().pending() && p.ageDays(now) >= minAgeDays))) {
                out.add(view(p));
            }
        }
        return out;
    }
'@ @'
    public ResponseEntity<List<Map<String, Object>>> list(RequestContext ctx, @PathVariable String ledger,
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
'@
Rep "                body.verificationRef())));" "                body.verificationRef(), body.evidenceRef())));"
Rep @'
    /** The caller from the authenticated request context only. */
    private static UnverifiedPaymentAuthority.Caller caller(RequestContext ctx) {
        if (ctx.principalId() == null || ctx.principalId().isBlank() || ctx.tenantId() == null
                || ctx.tenantId().isBlank()) {
            throw ApiProblem.notPermitted(ACCEPT);
        }
        return new UnverifiedPaymentAuthority.Caller(ctx.principalId(), ctx.tenantId(), ctx.seatId());
    }
'@ @'
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
'@
Rep @'
            if (p.resolution().verificationRef() != null) {
                r.put("verificationRef", p.resolution().verificationRef());
            }
'@ @'
            if (p.resolution().verificationRef() != null) {
                r.put("verificationRef", p.resolution().verificationRef());
            }
            if (p.resolution().evidenceRef() != null) {
                r.put("evidenceRef", p.resolution().evidenceRef());
            }
'@
[IO.File]::WriteAllText($p, $script:s)
"OK"
