// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.sangam.core.layer.Hashes;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.Balance;
import org.sangam.ledger.FreezeOrder;
import org.sangam.ledger.LedgerRequest;
import org.sangam.ledger.LedgerService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ledger} (sangam-ledger README "REST endpoints"): balances, operations (idempotent by reference = the
 * Idempotency-Key), freeze orders, holds, period close and reconciliation. The tenant is always the caller's; ledger
 * definitions come from the tenant's configuration profile ({@link LedgerTenantDefinitions}).
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    public static final String READ = "ledger.read";
    public static final String WRITE = "ledger.write";
    public static final String ADMIN = "ledger.admin";

    private final LedgerTenantDefinitions definitions;
    private final PermissionPort permissions;

    public LedgerController(LedgerTenantDefinitions definitions, PermissionPort permissions) {
        this.definitions = definitions;
        this.permissions = permissions;
    }

    /** An account address within the path's ledger; empty fields default to {@code -} (the ledger convention). */
    public record AccountRef(String period, String line, String holder, String stage, String sub) {
        AccountKey key(String tenant, String ledger) {
            return new AccountKey(tenant, ledger, period, line, holder, stage, sub);
        }
    }

    /**
     * One operation: ALLOCATE, DISTRIBUTE, WITHDRAW, COMMIT, ENCUMBER, RELEASE, CONSUME, TRANSFER, ADJUST, REVERSE,
     * WRITE_OFF … ({@code LedgerService.apply}). {@code reference} defaults to the Idempotency-Key; a request without
     * either is refused, because the reference is what makes a retry safe.
     */
    public record OperationRequest(String operation, AccountRef account, AccountRef target, String amount,
                                   String currency, String rateType, String reference, String auditRef, LocalDate asOf,
                                   boolean fromCommitment, boolean fromEncumbrance, boolean holdIfShort,
                                   Map<String, Object> attrs) {
    }

    /** A balance; amounts are decimal strings (R33: money is never a binary float). */
    public record AccountView(String accountId, AccountKey key, String currency, String total, String distributed,
                              String committed, String encumbered, String consumed, String available, String state,
                              long version, Map<String, Object> attrs) {
        static AccountView of(Balance b) {
            return new AccountView(b.key().id(), b.key(), b.currency(), dec(b.total()), dec(b.distributed()),
                    dec(b.committed()), dec(b.encumbered()), dec(b.consumed()), dec(b.available()), b.state(), b.version(),
                    b.attrs());
        }
    }

    /** One accounting entry of the event mapping (cash or accrual basis). */
    public record AccountingView(String reference, String event, String basis, String debit, String credit,
                                 String amount, String currency, String functionalAmount) {
    }

    /** Outcome of an operation; {@code held} = parked in the hold queue until funds arrive. */
    public record ResultView(boolean applied, boolean duplicate, boolean held, String reason,
                             List<org.sangam.config.common.Finding> findings, List<AccountView> balances,
                             List<AccountingView> accounting) {
        static ResultView of(LedgerService.Result r) {
            return new ResultView(r.applied(), r.duplicate(), r.held(), r.reason(),
                    r.findings() == null ? List.of() : List.copyOf(r.findings()),
                    r.balances() == null ? List.of() : r.balances().stream().map(AccountView::of).toList(),
                    r.accounting() == null ? List.of() : r.accounting().stream().map(a -> new AccountingView(
                            a.reference(), a.event(), a.basis(), a.debit(), a.credit(), dec(a.amount()), a.currency(),
                            dec(a.functionalAmount()))).toList());
        }
    }

    static String dec(BigDecimal d) {
        return d == null ? null : d.toPlainString();
    }

    public record FreezeRequest(String id, String period, String linePrefix, String holderPrefix, String stage,
                                String reason) {
    }

    public record CloseRequest(String nextPeriod, LocalDate asOf) {
    }

    public record ReconcileRequest(String period) {
    }

    /** {@code values}: the mirror and external balances as decimal strings. */
    public record ReconcileRow(String accountId, AccountKey key, List<String> values) {
    }

    @GetMapping("/{ledger}/accounts")
    public ResponseEntity<List<AccountView>> accounts(RequestContext ctx, @PathVariable String ledger,
            @RequestParam(required = false) String period, @RequestParam(required = false) String line,
            @RequestParam(required = false) String holder, @RequestParam(required = false) String stage) {
        permissions.require(ctx, READ);
        definitions.ensure(ctx.tenantId());
        return Lists.ok(service().store().find(ctx.tenantId(), ledger, period, line, holder, stage).stream()
                .map(AccountView::of).toList());
    }

    /** One account by its id ({@code AccountKey.id()}); {@code ?ledger=} narrows the search. */
    @GetMapping("/accounts/{accountId}")
    public AccountView account(RequestContext ctx, @PathVariable String accountId,
                               @RequestParam(required = false) String ledger) {
        permissions.require(ctx, READ);
        definitions.ensure(ctx.tenantId());
        List<String> ledgers = new ArrayList<>();
        if (ledger != null) {
            ledgers.add(ledger);
        } else {
            ledgers.addAll(ledgerIds(ctx.tenantId()));
        }
        for (String l : ledgers) {
            for (Balance b : service().store().find(ctx.tenantId(), l, null, null, null, null)) {
                if (b.key().id().equals(accountId)) {
                    return AccountView.of(b);
                }
            }
        }
        throw ApiProblem.notFound("ledger account", accountId);
    }

    @PostMapping("/{ledger}/operations")
    public ResponseEntity<ResultView> apply(RequestContext ctx, @PathVariable String ledger,
                                                      @RequestBody OperationRequest body) {
        permissions.require(ctx, WRITE);
        if (body == null || body.operation() == null || body.account() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "operation and account are required");
        }
        String reference = body.reference() != null && !body.reference().isBlank() ? body.reference()
                : ctx.idempotencyKey();
        if (reference == null) {
            throw ApiProblem.badRequest("REFERENCE_REQUIRED",
                    "a ledger operation needs a reference or an Idempotency-Key (retries must not post twice)");
        }
        definitions.ensure(ctx.tenantId());
        LedgerRequest r = new LedgerRequest(body.operation(), body.account().key(ctx.tenantId(), ledger),
                body.target() == null ? null : body.target().key(ctx.tenantId(), ledger), decimal(body.amount()),
                body.currency(), body.rateType(), reference, body.auditRef(), ctx.principalId(), body.asOf(),
                body.fromCommitment(), body.fromEncumbrance(), body.holdIfShort(), body.attrs());
        LedgerService.Result res = service().apply(r);
        return Lists.okRef(reference, ResultView.of(res));
    }

    @PostMapping("/{ledger}/freeze-orders")
    public ResponseEntity<FreezeOrder> freeze(RequestContext ctx, @PathVariable String ledger,
                                              @RequestBody FreezeRequest body) {
        permissions.require(ctx, ADMIN);
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "a freeze order needs a reason (order reference)");
        }
        String id = body.id() == null || body.id().isBlank() ? Hashes.uuidV7().toString() : body.id();
        FreezeOrder o = new FreezeOrder(ctx.tenantId(), id, ledger, body.period(), body.linePrefix(), body.holderPrefix(),
                body.stage(), body.reason(), ctx.principalId(), Instant.now());
        service().freeze(o);
        return Lists.created("/api/v1/ledger/" + ledger + "/freeze-orders/" + id, id, o);
    }

    @GetMapping("/{ledger}/freeze-orders")
    public ResponseEntity<List<FreezeOrder>> freezes(RequestContext ctx, @PathVariable String ledger) {
        permissions.require(ctx, READ);
        return Lists.ok(service().freezes(ctx.tenantId()).stream().filter(f -> ledger.equals(f.ledger())).toList());
    }

    @DeleteMapping("/{ledger}/freeze-orders/{id}")
    public ResponseEntity<Void> releaseFreeze(RequestContext ctx, @PathVariable String ledger, @PathVariable String id) {
        permissions.require(ctx, ADMIN);
        if (!service().releaseFreeze(ctx.tenantId(), id)) {
            throw ApiProblem.notFound("freeze order", id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{ledger}/periods/{period}/close")
    public ResponseEntity<List<ResultView>> closePeriod(RequestContext ctx, @PathVariable String ledger,
            @PathVariable String period, @RequestBody(required = false) CloseRequest body) {
        permissions.require(ctx, ADMIN);
        definitions.ensure(ctx.tenantId());
        LocalDate asOf = body == null || body.asOf() == null ? LocalDate.now() : body.asOf();
        List<LedgerService.Result> res = service().closePeriod(ctx.tenantId(), ledger, period,
                body == null ? null : body.nextPeriod(), asOf, ctx.principalId());
        return Lists.okRef(ledger + ":" + period, res.stream().map(ResultView::of).toList());
    }

    /** Requests parked for funds on matching accounts ({@code holdIfShort}). */
    @GetMapping("/{ledger}/holds")
    public ResponseEntity<List<LedgerRequest>> holds(RequestContext ctx, @PathVariable String ledger,
            @RequestParam(required = false) String period, @RequestParam(required = false) String line,
            @RequestParam(required = false) String holder, @RequestParam(required = false) String stage) {
        permissions.require(ctx, READ);
        definitions.ensure(ctx.tenantId());
        List<LedgerRequest> out = new ArrayList<>();
        for (Balance b : service().store().find(ctx.tenantId(), ledger, period, line, holder, stage)) {
            out.addAll(service().held(b.key()));
        }
        return Lists.ok(out);
    }

    /** Mirror versus external balances (SHADOW ledgers); a ledger without an external port reports none. */
    @PostMapping("/{ledger}/reconcile")
    public ResponseEntity<List<ReconcileRow>> reconcile(RequestContext ctx, @PathVariable String ledger,
                                                        @RequestBody ReconcileRequest body) {
        permissions.require(ctx, ADMIN);
        if (body == null || body.period() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "period is required");
        }
        definitions.ensure(ctx.tenantId());
        List<ReconcileRow> out = new ArrayList<>();
        for (Map.Entry<AccountKey, BigDecimal[]> e : service().reconcile(ctx.tenantId(), ledger, body.period()).entrySet()) {
            out.add(new ReconcileRow(e.getKey().id(), e.getKey(), java.util.Arrays.stream(e.getValue()).map(LedgerController::dec).toList()));
        }
        return Lists.okRef(ledger + ":" + body.period(), out);
    }

    private LedgerService service() {
        return definitions.ledger();
    }

    /** The tenant's ledgers (from its profile); with no profile, {@code ?ledger=} is required. */
    private List<String> ledgerIds(String tenant) {
        List<String> ids = definitions.ids(tenant);
        if (ids.isEmpty()) {
            throw ApiProblem.badRequest("LEDGER_REQUIRED", "the tenant has no profile-defined ledgers; pass ?ledger=");
        }
        return ids;
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount must be a decimal string (R33)");
        }
    }

}
