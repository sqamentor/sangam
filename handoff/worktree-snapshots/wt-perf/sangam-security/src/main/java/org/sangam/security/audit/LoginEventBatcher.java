// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.audit;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Batches login and login-failure events per tenant into one chained {@code LOGIN_BATCH} event per interval (default
 * 1 s), keeping logins off the security-chain hot path (X.2.3.3 rule 2). On shutdown the queue is flushed.
 */
public final class LoginEventBatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LoginEventBatcher.class);

    private record Login(String type, String personId, String subject, String authMethod, String clientIp, Instant at,
                         String reason) {
    }

    private final Transactions tx;
    private final SecurityAuditService audit;
    private final Map<String, ConcurrentLinkedQueue<Login>> queues = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;

    public LoginEventBatcher(Transactions tx, SecurityAuditService audit, long intervalMillis) {
        this.tx = tx;
        this.audit = audit;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-login-audit");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::flushQuietly, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void loggedIn(SecurityIdentity who) {
        queue(who.tenantId()).add(new Login("AUTH_LOGIN", who.personId(), who.subject(), who.authMethod(), who.clientIp(),
                Instant.now(), null));
    }

    public void failed(String tenant, String subject, String authMethod, String clientIp, String reason) {
        queue(tenant).add(new Login("LOGIN_FAILED", null, subject, authMethod, clientIp, Instant.now(), reason));
    }

    private ConcurrentLinkedQueue<Login> queue(String tenant) {
        return queues.computeIfAbsent(tenant, t -> new ConcurrentLinkedQueue<>());
    }

    public void flush() {
        for (Map.Entry<String, ConcurrentLinkedQueue<Login>> e : queues.entrySet()) {
            List<Map<String, Object>> batch = new ArrayList<>();
            Login l;
            while ((l = e.getValue().poll()) != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", l.type());
                if (l.personId() != null) {
                    m.put("person_id", l.personId());
                }
                if (l.subject() != null) {
                    m.put("subject", l.subject());
                }
                m.put("auth_method", l.authMethod());
                if (l.clientIp() != null) {
                    m.put("client_ip", l.clientIp());
                }
                m.put("at", l.at().toString());
                if (l.reason() != null) {
                    m.put("reason", l.reason());
                }
                batch.add(m);
            }
            if (batch.isEmpty()) {
                continue;
            }
            String tenant = e.getKey();
            tx.inTransaction(c -> {
                audit.security(c, SecurityIdentity.system(tenant, null), SecurityEventType.LOGIN_BATCH,
                        Map.of("count", batch.size(), "events", batch));
                return null;
            });
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException ex) {
            LOG.error("login audit flush failed; events are retried in the next batch only if still queued", ex);
        }
    }

    @Override
    public void close() {
        timer.shutdown();
        flushQuietly();
    }
}
