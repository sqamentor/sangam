// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.registry.custody.CustodyItem;
import org.sangam.registry.custody.CustodyRequests;
import org.sangam.registry.custody.CustodyService;
import org.sangam.registry.custody.CustodyTransfer;
import org.sangam.registry.custody.DisposalOrder;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;

import java.time.Duration;
import java.util.Map;

/**
 * Chain-of-custody steps for BPMN service tasks. Fields:
 * <ul>
 *   <li>{@code op}: {@code REGISTER}, {@code INITIATE_TRANSFER}, {@code ACCEPT_TRANSFER}, {@code HANDOVER},
 *       {@code SEAL}, {@code DISPOSE}, {@code GAP_CHECK};</li>
 *   <li>{@code request}: the process variable holding the request map (default {@code custodyRequest}); its keys are
 *       those of {@link CustodyRequests};</li>
 *   <li>{@code itemVariable}: variable holding the item id (default {@code custodyItemId}).</li>
 * </ul>
 * Outputs: {@code custodyItemId}, {@code custodyTransferId}, {@code custodyReceiptNumber},
 * {@code custodyDisposalOrderId}, {@code custodyState}; GAP_CHECK sets {@code custodyGapCount} and
 * {@code custodyGapReport} (JSON). DISPOSE takes {@code action} = {@code ORDER} (default), {@code EXECUTE} or
 * {@code CANCEL} in the request ({@code orderId} defaults to {@code custodyDisposalOrderId}).
 */
public class CustodyDelegate implements JavaDelegate {

    private Expression op;
    private Expression request;
    private Expression itemVariable;

    @Override
    public void execute(DelegateExecution ex) {
        String operation = DelegateSupport.field(op, ex, "").toUpperCase();
        Map<String, Object> req = DelegateSupport.map(ex, DelegateSupport.field(request, ex, "custodyRequest"));
        String itemVar = DelegateSupport.field(itemVariable, ex, "custodyItemId");
        CustodyService custody = DelegateSupport.module().custody();
        switch (operation) {
            case "REGISTER" -> {
                CustodyItem item = DelegateSupport.run(ex, (c, ctx) -> custody.register(c, ctx, CustodyRequests.Register.from(req)));
                ex.setVariable(itemVar, item.objectId());
                ex.setVariable("custodyState", item.custodyState());
            }
            case "INITIATE_TRANSFER" -> {
                String itemId = itemId(ex, req, itemVar);
                CustodyTransfer t = DelegateSupport.run(ex, (c, ctx) ->
                        custody.initiateTransfer(c, ctx, itemId, CustodyRequests.Transfer.from(req)));
                ex.setVariable("custodyTransferId", t.id());
                ex.setVariable("custodyState", CustodyService.IN_TRANSIT);
            }
            case "ACCEPT_TRANSFER" -> {
                String transferId = Maps.str(req, "transferId", DelegateSupport.str(ex, "custodyTransferId"));
                if (transferId == null) {
                    throw new ProcessEngineException("ACCEPT_TRANSFER: no transferId / custodyTransferId");
                }
                CustodyTransfer t = DelegateSupport.run(ex, (c, ctx) ->
                        custody.acceptTransfer(c, ctx, transferId, CustodyRequests.Accept.from(req)));
                ex.setVariable("custodyReceiptNumber", t.receiptNumber());
                ex.setVariable("custodyState", CustodyService.IN_CUSTODY);
            }
            case "HANDOVER" -> {
                String itemId = itemId(ex, req, itemVar);
                CustodyTransfer t = DelegateSupport.run(ex, (c, ctx) ->
                        custody.handover(c, ctx, itemId, CustodyRequests.Handover.from(req)));
                ex.setVariable("custodyTransferId", t.id());
                ex.setVariable("custodyReceiptNumber", t.receiptNumber());
            }
            case "SEAL" -> {
                String itemId = itemId(ex, req, itemVar);
                CustodyItem item = DelegateSupport.run(ex, (c, ctx) -> custody.seal(c, ctx, itemId, CustodyRequests.Seal.from(req)));
                ex.setVariable("custodySealNo", item.sealNo());
            }
            case "DISPOSE" -> {
                String action = Maps.str(req, "action", "ORDER").toUpperCase();
                String orderId = Maps.str(req, "orderId", DelegateSupport.str(ex, "custodyDisposalOrderId"));
                DisposalOrder o = switch (action) {
                    case "ORDER" -> {
                        String itemId = itemId(ex, req, itemVar);
                        yield DelegateSupport.run(ex, (c, ctx) -> custody.orderDisposal(c, ctx, itemId,
                                CustodyRequests.Disposal.from(req)));
                    }
                    case "EXECUTE" -> DelegateSupport.run(ex, (c, ctx) -> custody.executeDisposal(c, ctx, required(orderId),
                            Maps.str(req, "witnessRef"), Maps.map(req, "details")));
                    case "CANCEL" -> DelegateSupport.run(ex, (c, ctx) -> custody.cancelDisposal(c, ctx, required(orderId),
                            Maps.str(req, "reason")));
                    default -> throw new ProcessEngineException("DISPOSE: unknown action " + action);
                };
                ex.setVariable("custodyDisposalOrderId", o.id());
                ex.setVariable("custodyDisposalStatus", o.status());
            }
            case "GAP_CHECK" -> {
                Long hours = Maps.lng(req, "inTransitHours");
                Long days = Maps.lng(req, "staleDays");
                int limit = Maps.integer(req, "limit", 100);
                CustodyService.GapReport r = DelegateSupport.run(ex, (c, ctx) -> custody.gapCheck(c, ctx,
                        Duration.ofHours(hours == null ? 24 : hours), days == null ? null : Duration.ofDays(days), limit));
                ex.setVariable("custodyGapCount", (long) r.total());
                ex.setVariable("custodyGapReport", Json.write(Maps.of("checkedAt", r.checkedAt().toString(),
                        "withoutCustodian", ids(r.withoutCustodian()), "inTransitTooLong", ids(r.inTransitTooLong()),
                        "stale", ids(r.stale()), "sealBroken", ids(r.sealBroken()))));
            }
            default -> throw new ProcessEngineException("unknown custody op: " + operation);
        }
    }

    private static String itemId(DelegateExecution ex, Map<String, Object> req, String itemVar) {
        String id = Maps.str(req, "itemId", DelegateSupport.str(ex, itemVar));
        if (id == null) {
            throw new ProcessEngineException("custody delegate: no itemId in the request or variable " + itemVar);
        }
        return id;
    }

    private static java.util.List<String> ids(java.util.List<CustodyItem> items) {
        return items.stream().map(i -> i.objectId() + " (" + i.label() + ")").toList();
    }

    private static String required(String orderId) {
        if (orderId == null) {
            throw new ProcessEngineException("DISPOSE: no orderId / custodyDisposalOrderId");
        }
        return orderId;
    }
}
