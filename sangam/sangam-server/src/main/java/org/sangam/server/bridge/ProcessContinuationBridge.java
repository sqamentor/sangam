// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;

import org.operaton.bpm.engine.exception.NotFoundException;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.continuation.ContinuationNotAllowedException;
import org.sangam.engine.continuation.ProcessContinuations;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ProcessContinuationPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.SlotVariableGuard;

/**
 * Decision 149: {@link ProcessContinuationPort} over the engine's {@link ProcessContinuations}. The bridge only maps
 * shapes and errors; every rule is the engine's. Errors (contract {@code routes/continuation.py}):
 * {@link ContinuationNotAllowedException} is 409 {@code CONTINUATION_NOT_ALLOWED} with the extension {@code reason} and,
 * for an already continued instance, {@code successorId}; an unknown instance, one of another tenant (decision 145) or
 * an unknown {@code processDefinitionId} is 404; a refused object check is 403 {@code ACTION_NOT_PERMITTED}; the
 * engine's {@code VALIDATION_FAILED} refusals are 400 {@code VALIDATION_FAILED}.
 */
public final class ProcessContinuationBridge implements ProcessContinuationPort {

    /** The two engine calls; {@link #of(ProcessContinuations)} for the real engine. */
    public interface Continuations {
        ProcessContinuations.Result continueAsNew(SecurityIdentity actor, String instanceId,
                                                  ProcessContinuations.Request request);

        ProcessContinuations.Chain chain(SecurityIdentity actor, String instanceId);

        static Continuations of(ProcessContinuations engine) {
            return new Continuations() {
                @Override
                public ProcessContinuations.Result continueAsNew(SecurityIdentity actor, String instanceId,
                                                                 ProcessContinuations.Request request) {
                    return engine.continueAsNew(actor, instanceId, request);
                }

                @Override
                public ProcessContinuations.Chain chain(SecurityIdentity actor, String instanceId) {
                    return engine.chain(actor, instanceId);
                }
            };
        }
    }

    static final String VALIDATION_PREFIX = "VALIDATION_FAILED: ";

    private final Continuations engine;

    public ProcessContinuationBridge(Continuations engine) {
        this.engine = engine;
    }

    @Override
    public ContinueAsNewResult continueAsNew(RequestContext ctx, String processInstanceId, ContinueAsNewRequest r) {
        ProcessContinuations.Request request = new ProcessContinuations.Request(r.continuationRequestId(), r.reason(),
                r.processDefinitionId(), r.carryVariables(),
                r.variables() == null ? null : SlotVariableGuard.callerVariables(r.variables()));
        ProcessContinuations.Result res;
        try {
            res = engine.continueAsNew(SecurityIdentities.of(ctx), processInstanceId, request);
        } catch (RuntimeException e) {
            throw problem(e, processInstanceId, r.processDefinitionId());
        }
        return new ContinueAsNewResult(res.requestId(), res.predecessorId(), res.successorId(), res.chainId(),
                res.generation(), res.businessKey(), res.processDefinitionId(), res.outcome().name(), res.actor(),
                res.reason(), res.continuedAt(),
                res.ended().stream().map(x -> new ContinuationEnded(x.kind().name(), x.ended())).toList());
    }

    @Override
    public ProcessContinuationChain chain(RequestContext ctx, String processInstanceId) {
        ProcessContinuations.Chain c;
        try {
            c = engine.chain(SecurityIdentities.of(ctx), processInstanceId);
        } catch (RuntimeException e) {
            throw problem(e, processInstanceId, null);
        }
        List<ProcessContinuationLink> links = c.links().stream().map(l -> new ProcessContinuationLink(l.predecessorId(),
                l.successorId(), l.generation(), l.requestId(), l.actor(), l.reason(), l.continuedAt())).toList();
        return new ProcessContinuationChain(c.chainId(), c.instanceIds(), links);
    }

    /** The REST problem for an engine refusal (the cause chain is searched, so a wrapped refusal maps the same). */
    static RuntimeException problem(RuntimeException e, String instanceId, String definitionId) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiProblem p) {
                return p;
            }
            if (t instanceof ContinuationNotAllowedException c) {
                ApiProblem p = ApiProblem.conflict(ContinuationNotAllowedException.CODE, c.getMessage())
                        .with("reason", c.reason().name());
                return c.successorId() == null ? p : p.with("successorId", c.successorId());
            }
            if (t instanceof NotFoundException) {
                boolean definition = definitionId != null && t.getMessage() != null
                        && t.getMessage().startsWith("no process definition");
                return definition ? ApiProblem.notFound("process definition", definitionId)
                        : ApiProblem.notFound("process instance", instanceId);
            }
            if (t instanceof SangamSecurityException.AccessDenied) {
                return ApiProblem.forbidden("ACTION_NOT_PERMITTED", t.getMessage());
            }
            if (t instanceof IllegalArgumentException && t.getMessage() != null
                    && t.getMessage().startsWith(VALIDATION_PREFIX)) {
                return ApiProblem.badRequest("VALIDATION_FAILED", t.getMessage().substring(VALIDATION_PREFIX.length()));
            }
        }
        return e;
    }
}
