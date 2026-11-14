import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()


def rep(old, new):
    global s
    assert s.count(old) == 1, old[:90]
    s = s.replace(old, new)


rep("""import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
""", """import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
""")
rep("""import org.sangam.billing.TariffEngine;
""", """import org.sangam.billing.JdbcUnverifiedPaymentStore;
import org.sangam.billing.TariffEngine;
import org.sangam.billing.UnverifiedPayment;
import org.sangam.billing.UnverifiedPaymentAuthority;
import org.sangam.billing.UnverifiedPaymentPolicy;
import org.sangam.billing.UnverifiedPayments;
""")
rep("""import org.sangam.config.profile.ProfileResolver;
""", """import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.layer.PendingAuditDrainer;
""")
rep("""import org.sangam.integration.packs.PackConnectorBindings;
""", """import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.integration.relay.LeaderElection;
""")
rep("""import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.support.TxSupport;
""", """import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
""")
rep("""import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
""", """import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
""")
rep(""" * {@code sangam.billing.trusted-channels} (default empty: every collection is rail-verified; a non-empty list in
 * production needs {@code sangam.billing.trusted-channels-acknowledged=true}, owner decision 136),""",
    """ * {@code sangam.billing.trusted-channels} (default empty: every collection is rail-verified; a non-empty list in
 * production needs {@code sangam.billing.trusted-channels-acknowledged=true}, owner decision 136; a trusted channel is
 * refused by every collection call and its payments go only through {@link UnverifiedPayments}, decision 141, with the
 * per-channel policy {@code sangam.billing.unverified.channels.<channel>.*} and
 * {@code sangam.billing.unverified.reconcile-minutes}, default 60),""")
OLD_POLICY = """    /**
     * RM-03 (owner decision 136): the trusted channels come from the server configuration only, default empty, and a
     * production start with a non-empty list and no acknowledgement is refused. No unverified-payment gate is bound
     * yet (the channel permission it must check is an open question), so a listed channel is refused at collection
     * (fail closed) rather than accepted without its permission check and audit event.
     */
    @Bean
    public PaymentConfirmationPolicy sangamBillingPaymentPolicy(Environment env) {
        return PaymentConfirmationPolicy.fromConfiguration(env.getProperty(PaymentConfirmationPolicy.TRUSTED_CHANNELS),
                env.getProperty(PaymentConfirmationPolicy.TRUSTED_CHANNELS_ACKNOWLEDGED, Boolean.class, false),
                ProductionEnvironment.isProduction(env), null);
    }
"""
NEW_POLICY = open(sys.argv[2], encoding="utf-8").read()
rep(OLD_POLICY, NEW_POLICY)
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
