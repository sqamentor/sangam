// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.billing.BillingConnector;
import org.sangam.billing.BillingService;
import org.sangam.core.layer.Hashes;
import org.sangam.ledger.AccountKey;
import org.sangam.rest.modules.config.ModuleConnectors;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/billing/{ledger}} (sangam-billing): accounts of a cycle, invoices, collections, arrears, penalties,
 * waivers and refunds on a RECEIVABLE ledger. The endpoints run the same {@link BillingConnector} operations as the
 * billing packs, so an invoice raised by a pack step and by an operator are the same posting (idempotent per consumer
 * × cycle × component; collections and refunds per payment reference / operation).
 */
@RestController
@RequestMapping("/api/v1/billing/{ledger}")
public class BillingController {

    public static final String READ = "billing.read";
    public static final String WRITE = "billing.write";
    public static final String ADMIN = "billing.admin";

    private final BillingConnector connector;
    private final BillingService billing;
    private final PermissionPort permissions;
    private final LedgerTenantDefinitions definitions;

    public BillingController(BillingConnector connector, BillingService billing, PermissionPort permissions,
                             LedgerTenantDefinitions definitions) {
        this.definitions = definitions;
        this.connector = connector;
        this.billing = billing;
        this.permissions = permissions;
    }

    /** {@code measures}: component measure → decimal string (e.g. {@code units: "42"}). */
    public record InvoiceRequest(String consumer, String cycle, String tariffId, String period,
                                 Map<String, String> measures, String units, Integer dueDays,
                                 Map<String, Object> attributes) {
    }

    public record CollectionRequest(String consumer, String channel, String paymentRef, String amount) {
    }

    public record PenaltyRequest(String tariffId, String period) {
    }

    public record WaiverRequest(String consumer, String head, String cycle, String period, String amount,
                                String orderRef) {
    }

    public record RefundRequest(String consumer, String head, String cycle, String period, String channel,
                                String paymentRef, String amount) {
    }

    public record RefundResult(String railReference, String operationId) {
    }

    @GetMapping("/accounts")
    public Map<String, Object> accounts(RequestContext ctx, @PathVariable String ledger,
                                        @RequestParam(required = false) String cycle) {
        permissions.require(ctx, READ);
        Map<String, Object> in = base(ledger);
        put(in, "cycle", cycle);
        return call(ctx, "accounts", in);
    }

    @PostMapping("/invoices")
    public ResponseEntity<Map<String, Object>> invoice(RequestContext ctx, @PathVariable String ledger,
                                                       @RequestBody InvoiceRequest body) {
        permissions.require(ctx, WRITE);
        if (body == null || body.consumer() == null || body.cycle() == null || body.tariffId() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "consumer, cycle and tariffId are required");
        }
        Map<String, Object> in = base(ledger);
        if (body.attributes() != null) {
            in.putAll(body.attributes());
        }
        in.put("consumer", body.consumer());
        in.put("cycle", body.cycle());
        in.put("tariffId", body.tariffId());
        put(in, "period", body.period());
        if (body.measures() != null) {
            in.put("measures", body.measures());
        }
        put(in, "units", body.units());
        if (body.dueDays() != null) {
            in.put("dueDays", body.dueDays());
        }
        Map<String, Object> out = call(ctx, "invoice", in);
        return Lists.okRef(String.valueOf(out.get("invoiceNo")), out);
    }

    @PostMapping("/collections")
    public ResponseEntity<Map<String, Object>> collect(RequestContext ctx, @PathVariable String ledger,
                                                       @RequestBody CollectionRequest body) {
        permissions.require(ctx, WRITE);
        if (body == null || body.consumer() == null || body.channel() == null || body.paymentRef() == null
                || body.amount() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "consumer, channel, paymentRef and amount are required");
        }
        Map<String, Object> in = base(ledger);
        in.put("consumer", body.consumer());
        in.put("channel", body.channel());
        in.put("paymentRef", body.paymentRef());
        in.put("amount", decimal(body.amount()).toPlainString());
        Map<String, Object> out = call(ctx, "collect", in);
        return Lists.okRef(String.valueOf(out.get("receiptNo")), out);
    }

    @GetMapping("/consumers/{consumer}/arrears")
    public Map<String, Object> arrears(RequestContext ctx, @PathVariable String ledger, @PathVariable String consumer) {
        permissions.require(ctx, READ);
        Map<String, Object> in = base(ledger);
        in.put("consumer", consumer);
        return call(ctx, "arrears", in);
    }

    @PostMapping("/consumers/{consumer}/penalties")
    public ResponseEntity<Map<String, Object>> penalties(RequestContext ctx, @PathVariable String ledger,
                                                         @PathVariable String consumer,
                                                         @RequestBody PenaltyRequest body) {
        permissions.require(ctx, WRITE);
        if (body == null || body.tariffId() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "tariffId is required");
        }
        Map<String, Object> in = base(ledger);
        in.put("consumer", consumer);
        in.put("tariffId", body.tariffId());
        put(in, "period", body.period());
        return Lists.okRef(consumer, call(ctx, "penalty", in));
    }

    /** Waiver / write-off of outstanding demand; the approval is a pack, this records the approved order. */
    @PostMapping("/waivers")
    public ResponseEntity<Map<String, Object>> waive(RequestContext ctx, @PathVariable String ledger,
                                                     @RequestBody WaiverRequest body) {
        permissions.require(ctx, ADMIN);
        if (body == null || body.consumer() == null || body.amount() == null || body.orderRef() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "consumer, amount and orderRef are required");
        }
        Map<String, Object> in = base(ledger);
        in.put("consumer", body.consumer());
        put(in, "head", body.head());
        put(in, "cycle", body.cycle());
        put(in, "period", body.period());
        in.put("amount", decimal(body.amount()).toPlainString());
        in.put("orderRef", body.orderRef());
        return Lists.okRef(body.orderRef(), call(ctx, "waive", in));
    }

    /** Refund of a collection: reverses consumption and calls the payment rail once per operation id. */
    @PostMapping("/refunds")
    public ResponseEntity<RefundResult> refund(RequestContext ctx, @PathVariable String ledger,
                                               @RequestBody RefundRequest body) {
        permissions.require(ctx, ADMIN);
        if (body == null || body.consumer() == null || body.paymentRef() == null || body.amount() == null
                || body.channel() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "consumer, channel, paymentRef and amount are required");
        }
        String operationId = ctx.idempotencyKey() != null ? ctx.idempotencyKey() : Hashes.uuidV7().toString();
        String period = body.period() == null ? Integer.toString(LocalDate.now().getYear()) : body.period();
        AccountKey demand = new AccountKey(ctx.tenantId(), ledger, period, body.head(), body.consumer(), "DEMAND",
                body.cycle());
        definitions.ensure(ctx.tenantId());
        String rail = billing.refund(demand, body.channel(), body.paymentRef(), decimal(body.amount()), operationId,
                ctx.principalId());
        return Lists.okRef(operationId, new RefundResult(rail, operationId));
    }

    /** Runs a billing connector operation after registering the tenant's ledger definitions. */
    private Map<String, Object> call(RequestContext ctx, String operation, Map<String, Object> input) {
        definitions.ensure(ctx.tenantId());
        return ModuleConnectors.call(connector, ctx, ctx.tenantId(), operation, input);
    }

    private static Map<String, Object> base(String ledger) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("ledgerId", ledger);
        return in;
    }

    private static void put(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static BigDecimal decimal(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (RuntimeException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount must be a decimal string (R33)");
        }
    }
}
