// SPDX-License-Identifier: Apache-2.0
package org.sangam.security;

import org.sangam.core.layer.Transactions;
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.BulkOperationGuard;
import org.sangam.security.admin.GroupAdminService;
import org.sangam.security.admin.ModuleDefaults;
import org.sangam.security.admin.PolicyAdminService;
import org.sangam.security.admin.PrivilegeService;
import org.sangam.security.admin.RoleAdminService;
import org.sangam.security.admin.SecurityDirectoryService;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.admin.WorkerBindingService;
import org.sangam.security.approval.ApprovalBindingService;
import org.sangam.security.assignment.SeatAssignmentResolver;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.delegation.DelegationOfPowersService;
import org.sangam.security.delegation.DmnPowerEvaluator;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.identity.PrincipalResolver;
import org.sangam.security.mask.FieldMaskService;
import org.sangam.security.org.OrgUnitService;
import org.sangam.security.org.PersonService;
import org.sangam.security.org.PostingService;
import org.sangam.security.org.SeatService;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.AuthorizedQueryBuilder;
import org.sangam.security.policy.MembershipIndex;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.sod.SegregationOfDutiesService;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Wiring of the whole security layer (one instance per engine database). {@code sangam-server} creates it once and
 * exposes the services as beans; {@code sangam-engine} passes it to {@code SangamSecurityEnginePlugin}.
 */
public final class SangamSecurity {

    /**
     * @param maxStaleness       list-predicate cache staleness (VI.4 rule 5: ≤ 5 s; zero = no cache)
     * @param sodConflicts       duty conflicts (null = INITIATOR/CHECKER/APPROVER)
     * @param bulkFourEyesAbove  item count above which a bulk operation needs a second approver (R64)
     * @param denialTx           separate transaction for denial audit events (null = in-line)
     * @param openWorkProbe      open-work check for seat abolition (null = seat-task grants)
     */
    public record Options(Duration maxStaleness, Map<String, Set<String>> sodConflicts, long bulkFourEyesAbove,
                          Transactions denialTx, SeatService.OpenWorkProbe openWorkProbe) {
        public static Options defaults() {
            return new Options(Duration.ofSeconds(5), null, 100, null, null);
        }
    }

    private final SecurityDialect dialect;
    private final ResourceRegistry registry;
    private final PolicyCompiler compiler;
    private final AuthorizedQueryBuilder queries;
    private final SecurityAuditService audit;
    private final AuthorizationService authorization;
    private final AuthEffectiveMaintainer maintainer;
    private final MembershipIndex memberships;
    private final OrgUnitService orgUnits;
    private final SeatService seats;
    private final PostingService postings;
    private final PersonService persons;
    private final SegregationOfDutiesService sod;
    private final DelegationOfPowersService powers;
    private final ApprovalBindingService approvals;
    private final SeatAssignmentResolver assignment;
    private final TenantAdminService tenants;
    private final PolicyAdminService policies;
    private final RoleAdminService roles;
    private final GroupAdminService groups;
    private final PrivilegeService privileges;
    private final ApiTokenService tokens;
    private final WorkerBindingService workers;
    private final BulkOperationGuard bulk;
    private final FieldMaskService masks;
    private final PrincipalResolver principals;
    private final SecurityDirectoryService directory;
    private final ModuleDefaults defaults;

    public SangamSecurity(SecurityDialect dialect, Options options) {
        Options o = options == null ? Options.defaults() : options;
        this.dialect = dialect;
        this.registry = new ResourceRegistry();
        this.compiler = new PolicyCompiler(dialect, registry, o.maxStaleness());
        this.queries = new AuthorizedQueryBuilder(compiler);
        this.audit = new SecurityAuditService(dialect, o.denialTx());
        this.authorization = new AuthorizationService(compiler, audit);
        this.maintainer = new AuthEffectiveMaintainer(dialect);
        this.memberships = new MembershipIndex(dialect, maintainer);
        this.orgUnits = new OrgUnitService(dialect, authorization, audit);
        this.seats = new SeatService(dialect, authorization, audit, orgUnits, o.openWorkProbe());
        this.postings = new PostingService(dialect, authorization, audit, seats, memberships);
        this.persons = new PersonService(dialect, authorization, audit);
        this.sod = new SegregationOfDutiesService(dialect, authorization, audit, seats, postings, o.sodConflicts());
        this.powers = new DelegationOfPowersService(dialect, authorization, audit, seats, orgUnits, postings, new DmnPowerEvaluator());
        this.approvals = new ApprovalBindingService(dialect, audit);
        this.assignment = new SeatAssignmentResolver(seats, postings, sod, maintainer, audit);
        this.tenants = new TenantAdminService(dialect, authorization, audit);
        this.policies = new PolicyAdminService(dialect, authorization, maintainer, audit, tenants);
        this.roles = new RoleAdminService(dialect, authorization, maintainer, memberships, audit);
        this.groups = new GroupAdminService(dialect, authorization, memberships, audit);
        this.privileges = new PrivilegeService(dialect, authorization, maintainer, audit);
        this.tokens = new ApiTokenService(dialect, authorization, audit);
        this.workers = new WorkerBindingService(dialect, authorization, maintainer, audit);
        this.bulk = new BulkOperationGuard(dialect, authorization, audit, o.bulkFourEyesAbove());
        this.masks = new FieldMaskService(authorization, audit);
        this.principals = new PrincipalResolver(dialect, persons, postings, memberships, tenants);
        this.directory = new SecurityDirectoryService(dialect, authorization);
        this.defaults = new ModuleDefaults(roles, policies, registry);
    }

    public SecurityDialect dialect() { return dialect; }
    public ResourceRegistry registry() { return registry; }
    public PolicyCompiler compiler() { return compiler; }
    public AuthorizedQueryBuilder queries() { return queries; }
    public SecurityAuditService audit() { return audit; }
    public AuthorizationService authorization() { return authorization; }
    public AuthEffectiveMaintainer maintainer() { return maintainer; }
    public MembershipIndex memberships() { return memberships; }
    public OrgUnitService orgUnits() { return orgUnits; }
    public SeatService seats() { return seats; }
    public PostingService postings() { return postings; }
    public PersonService persons() { return persons; }
    public SegregationOfDutiesService sod() { return sod; }
    public DelegationOfPowersService powers() { return powers; }
    public ApprovalBindingService approvals() { return approvals; }
    public SeatAssignmentResolver assignment() { return assignment; }
    public TenantAdminService tenants() { return tenants; }
    public PolicyAdminService policies() { return policies; }
    public RoleAdminService roles() { return roles; }
    public GroupAdminService groups() { return groups; }
    public PrivilegeService privileges() { return privileges; }
    public ApiTokenService tokens() { return tokens; }
    public WorkerBindingService workers() { return workers; }
    public BulkOperationGuard bulk() { return bulk; }
    public FieldMaskService masks() { return masks; }
    public PrincipalResolver principals() { return principals; }
    public SecurityDirectoryService directory() { return directory; }
    public ModuleDefaults defaults() { return defaults; }

    /**
     * Builds the tenant-key {@link org.sangam.core.security.FieldEncryptor} (AES-256-GCM, key per tenant, versioned key
     * ids) on {@code SEC_TENANT_KEY}. {@code tx} must be the product data source; key transactions are short and separate.
     * An ephemeral development KEK is refused when {@code production} is true.
     */
    public org.sangam.security.crypto.AesGcmFieldEncryptor newFieldEncryptor(Transactions tx,
                                                                           org.sangam.security.crypto.MasterKeyProvider kek,
                                                                           boolean production) {
        if (production && kek.ephemeral()) {
            throw new IllegalStateException("an ephemeral master key cannot be used on a production profile");
        }
        return new org.sangam.security.crypto.AesGcmFieldEncryptor(
                new org.sangam.security.crypto.JdbcTenantKeyStore(tx, dialect, kek, Duration.ofSeconds(60)));
    }
}
