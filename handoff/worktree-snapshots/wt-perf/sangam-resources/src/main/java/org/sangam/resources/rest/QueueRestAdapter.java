// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rest;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.queue.QueueModel;
import org.sangam.resources.queue.QueueService;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.VisibleQuery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link QueueRestPort} over {@link QueueService}; one transaction per call. */
public final class QueueRestAdapter implements QueueRestPort {

    private final Transactions tx;
    private final QueueService queue;

    public QueueRestAdapter(Transactions tx, QueueService queue) {
        this.tx = tx;
        this.queue = queue;
    }

    @Override
    public QueueModel.ServicePoint defineServicePoint(SecurityIdentity actor, String servicePointId, ServicePointRequest r) {
        return tx.inTransaction(c -> queue.defineServicePoint(c, actor, servicePointId, r.name(), r.resourceId(),
                r.calendarId(), r.tokenPrefix(), r.slaMinutes(), r.priorityDecision(), r.routingDecision(), r.config()));
    }

    @Override
    public QueueModel.ServicePoint setServicePointStatus(SecurityIdentity actor, String servicePointId, String status) {
        return tx.inTransaction(c -> queue.setServicePointStatus(c, actor, servicePointId, status));
    }

    @Override
    public QueueModel.ServicePoint servicePoint(SecurityIdentity actor, String servicePointId) {
        return tx.inTransaction(c -> queue.servicePoint(c, actor, servicePointId));
    }

    @Override
    public List<QueueModel.ServicePoint> servicePoints(SecurityIdentity actor, int page, int size) {
        int sz = size <= 0 ? 50 : Math.min(size, VisibleQuery.MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * sz;
        return tx.inTransaction(c -> queue.servicePoints(c, actor, offset, sz));
    }

    @Override
    public QueueModel.Counter defineCounter(SecurityIdentity actor, String servicePointId, String counterId, CounterRequest r) {
        return tx.inTransaction(c -> queue.defineCounter(c, actor, servicePointId, counterId, r.name(), r.skills()));
    }

    @Override
    public List<QueueModel.Counter> counters(SecurityIdentity actor, String servicePointId) {
        return tx.inTransaction(c -> queue.counters(c, actor, servicePointId));
    }

    @Override
    public QueueModel.Counter openCounter(SecurityIdentity actor, String counterId, String operatorId) {
        return tx.inTransaction(c -> queue.openCounter(c, actor, counterId, operatorId));
    }

    @Override
    public QueueModel.Counter pauseCounter(SecurityIdentity actor, String counterId, String reason) {
        return tx.inTransaction(c -> queue.pauseCounter(c, actor, counterId, reason));
    }

    @Override
    public QueueModel.Counter closeCounter(SecurityIdentity actor, String counterId) {
        return tx.inTransaction(c -> queue.closeCounter(c, actor, counterId));
    }

    @Override
    public QueueModel.Token issueToken(SecurityIdentity actor, String servicePointId, IssueTokenRequest r) {
        QueueModel.Channel ch;
        try {
            ch = r.channel() == null ? null : QueueModel.Channel.valueOf(r.channel());
        } catch (IllegalArgumentException e) {
            throw ResourcesException.invalid("CHANNEL", "unknown channel: " + r.channel());
        }
        QueueModel.IssueRequest req = new QueueModel.IssueRequest(servicePointId, ch, r.bookingId(), r.partyId(),
                r.priorityClass(), r.skill(), r.routingKey(), r.attributes());
        return tx.inTransaction(c -> queue.issue(c, actor, req));
    }

    @Override
    public List<QueueModel.Token> tokens(SecurityIdentity actor, String servicePointId, String state, int page, int size) {
        QueueModel.TokenState st = tokenState(state);
        int sz = size <= 0 ? 50 : Math.min(size, 1000);
        int offset = Math.max(page, 0) * sz;
        return tx.inTransaction(c -> queue.tokens(c, actor, servicePointId, st, offset, sz));
    }

    @Override
    public VisibleQuery.CappedCount countTokens(SecurityIdentity actor, String servicePointId, String state, int cap) {
        QueueModel.TokenState st = tokenState(state);
        return tx.inTransaction(c -> queue.countTokens(c, actor, servicePointId, st, cap));
    }

    private static QueueModel.TokenState tokenState(String state) {
        try {
            return state == null ? null : QueueModel.TokenState.valueOf(state);
        } catch (IllegalArgumentException e) {
            throw ResourcesException.invalid("STATE", "unknown token state: " + state);
        }
    }

    @Override
    public QueueModel.Token token(SecurityIdentity actor, String tokenId) {
        return tx.inTransaction(c -> queue.token(c, actor, tokenId));
    }

    @Override
    public Map<String, Object> position(SecurityIdentity actor, String tokenId) {
        return tx.inTransaction(c -> queue.position(c, actor, tokenId));
    }

    @Override
    public QueueModel.Token transfer(SecurityIdentity actor, String tokenId, TransferRequest r) {
        return tx.inTransaction(c -> queue.transfer(c, actor, tokenId, r.targetServicePointId(), r.targetSkill(),
                r.targetCounterId(), r.reason()));
    }

    @Override
    public QueueModel.Token cancel(SecurityIdentity actor, String tokenId, String reason) {
        return tx.inTransaction(c -> queue.cancel(c, actor, tokenId, reason));
    }

    @Override
    public Optional<QueueModel.Token> callNext(SecurityIdentity actor, String counterId) {
        return tx.inTransaction(c -> queue.callNext(c, actor, counterId));
    }

    @Override
    public QueueModel.Token callToken(SecurityIdentity actor, String counterId, String tokenId, String reason) {
        return tx.inTransaction(c -> queue.callToken(c, actor, counterId, tokenId, reason));
    }

    @Override
    public QueueModel.Token recall(SecurityIdentity actor, String counterId) {
        return tx.inTransaction(c -> queue.recall(c, actor, counterId));
    }

    @Override
    public QueueModel.Token skip(SecurityIdentity actor, String counterId, String reason) {
        return tx.inTransaction(c -> queue.skip(c, actor, counterId, reason));
    }

    @Override
    public QueueModel.Token startServing(SecurityIdentity actor, String counterId) {
        return tx.inTransaction(c -> queue.startServing(c, actor, counterId));
    }

    @Override
    public QueueModel.Token complete(SecurityIdentity actor, String counterId, String outcome) {
        return tx.inTransaction(c -> queue.complete(c, actor, counterId, outcome));
    }

    @Override
    public int noShowSweep(SecurityIdentity actor, String servicePointId) {
        return tx.inTransaction(c -> queue.noShowSweep(c, actor, servicePointId));
    }

    @Override
    public List<QueueModel.Token> slaBreaches(SecurityIdentity actor, String servicePointId) {
        return tx.inTransaction(c -> queue.slaBreaches(c, actor, servicePointId));
    }

    @Override
    public QueueModel.QueueStats stats(SecurityIdentity actor, String servicePointId, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw ResourcesException.invalid("RANGE", "from and to are required and to must be after from");
        }
        return tx.inTransaction(c -> queue.stats(c, actor, servicePointId, from, to));
    }

    @Override
    public QueueModel.DisplaySnapshot displaySnapshot(SecurityIdentity actor, String servicePointId, int next) {
        return tx.inTransaction(c -> queue.displaySnapshot(c, actor, servicePointId, Math.min(Math.max(next, 0), 100)));
    }

    @Override
    public List<QueueModel.CallEvent> callEvents(SecurityIdentity actor, String servicePointId, long afterEventId, int limit) {
        return tx.inTransaction(c -> queue.callEvents(c, actor, servicePointId, afterEventId, limit));
    }
}
