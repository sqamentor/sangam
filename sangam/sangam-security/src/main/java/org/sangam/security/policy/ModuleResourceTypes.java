// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.sangam.security.policy.Permission.ADMIN;
import static org.sangam.security.policy.Permission.APPROVE;
import static org.sangam.security.policy.Permission.ASSIGN;
import static org.sangam.security.policy.Permission.BULK;
import static org.sangam.security.policy.Permission.CANCEL;
import static org.sangam.security.policy.Permission.DELETE_INSTANCE;
import static org.sangam.security.policy.Permission.DEPLOY;
import static org.sangam.security.policy.Permission.ERASE_DATA;
import static org.sangam.security.policy.Permission.EXPORT;
import static org.sangam.security.policy.Permission.MODIFY;
import static org.sangam.security.policy.Permission.READ;
import static org.sangam.security.policy.Permission.READ_AUDIT;
import static org.sangam.security.policy.Permission.READ_HISTORY;
import static org.sangam.security.policy.Permission.READ_SENSITIVE;
import static org.sangam.security.policy.Permission.START;

/**
 * Resource types of the generic product modules (resources, case, programme, compliance, registry, config, ledger,
 * billing, field, dispatch, geo), with their table mappings, condition attributes, broader scopes (A-13) and the
 * permission set that is meaningful on each type. Names, tables and id columns come from the module READMEs and
 * schema classes; the type names equal the modules' constants (e.g. {@code ResourcesContext.RT_*}).
 *
 * <p>Modules without a list path through the VI.4 predicate (config, ledger, billing, field, dispatch, geo,
 * compliance) get <b>virtual</b> types: their endpoints use object checks on {@code '*'} (module-wide) or on an entity
 * id. Admin defaults and self-service templates are in {@code admin.ModuleDefaults}.
 */
public final class ModuleResourceTypes {

    // ---- sangam-resources (ResourcesContext.RT_*)
    public static final String RES_RESOURCE = "RES_RESOURCE";
    public static final String RES_BOOKING = "RES_BOOKING";
    public static final String RES_ALLOTMENT = "RES_ALLOTMENT";
    public static final String RES_WAITLIST = "RES_WAITLIST";
    public static final String RES_DRAW = "RES_DRAW";
    public static final String RES_EVENT = "RES_EVENT";
    public static final String RES_SERVICE_POINT = "RES_SERVICE_POINT";
    public static final String RES_COUNTER = "RES_COUNTER";
    public static final String RES_TOKEN = "RES_TOKEN";
    public static final String RES_CALL_EVENT = "RES_CALL_EVENT";
    public static final String RES_ROSTER = "RES_ROSTER";
    public static final String RES_SHIFT_TEMPLATE = "RES_SHIFT_TEMPLATE";
    public static final String RES_ROTATION = "RES_ROTATION";
    public static final String RES_ROSTER_MEMBER = "RES_ROSTER_MEMBER";
    public static final String RES_COVERAGE_DEMAND = "RES_COVERAGE_DEMAND";
    public static final String RES_ROSTER_ASSIGNMENT = "RES_ROSTER_ASSIGNMENT";
    public static final String RES_SWAP_REQUEST = "RES_SWAP_REQUEST";
    // ---- sangam-case
    public static final String CASE_FILE = "CASE_FILE";
    public static final String CASE_REFERENCE = "CASE_REFERENCE";
    public static final String COMMITTEE = "COMMITTEE";
    // ---- sangam-programme
    public static final String PROGRAMME = "PROGRAMME";
    public static final String PROGRAMME_RETURN = "PROGRAMME_RETURN";
    // ---- sangam-registry (RegisteredObjectService.RESOURCE, PartyService.RESOURCE, privacy aliases)
    public static final String REGISTRY_OBJECT = "registry-object";
    public static final String REGISTRY_PARTY = "registry-party";
    public static final String REGISTRY_ASSET = "registry-asset";
    public static final String REGISTRY_WORK_ORDER = "registry-work-order";
    public static final String REGISTRY_INSTRUMENT_PUBLIC = "registry-instrument-public";
    // ---- sangam-ai KnowledgeBaseService (policy resource type of a chunk's source document)
    public static final String DOCUMENT = "document";
    // ---- virtual module types
    public static final String COMPLIANCE = "COMPLIANCE";
    public static final String CONFIG = "CONFIG";
    public static final String LEDGER = "LEDGER";
    public static final String BILLING = "BILLING";
    public static final String FIELD = "FIELD";
    public static final String DISPATCH = "DISPATCH";
    public static final String GEO = "GEO";

    /** Catalogue-like data (resources, templates, configuration). */
    public static final Set<Permission> CATALOGUE = EnumSet.of(READ, READ_HISTORY, EXPORT, START, MODIFY, DELETE_INSTANCE, ADMIN);
    /** Transactions with a life cycle (bookings, allotments, cases, returns). */
    public static final Set<Permission> TRANSACTIONAL = EnumSet.of(READ, READ_HISTORY, READ_SENSITIVE, EXPORT, START, MODIFY,
            APPROVE, CANCEL, ASSIGN, BULK, ADMIN);
    /** Front-office operation (queues, counters, tokens). */
    public static final Set<Permission> OPERATIONAL = EnumSet.of(READ, READ_HISTORY, EXPORT, START, MODIFY, ASSIGN, CANCEL, ADMIN);
    /** Append-only event logs. */
    public static final Set<Permission> EVENT_LOG = EnumSet.of(READ, READ_HISTORY, READ_AUDIT, EXPORT);
    /** Records of a register (objects, parties). */
    public static final Set<Permission> RECORD = EnumSet.of(READ, READ_HISTORY, READ_SENSITIVE, EXPORT, START, MODIFY, APPROVE,
            CANCEL, ERASE_DATA, BULK, ADMIN);

    private ModuleResourceTypes() {
    }

    static void registerAll(ResourceRegistry r) {
        resources(r);
        caseFiles(r);
        programmes(r);
        registry(r);
        virtual(r);
        // one document has many chunks, so the id is not unique; the object check only needs "some visible row". No default
        // grant exists: access needs a policy on the document, its "kb:<key>" scope, '*', or the tenant-admin '*' type.
        r.register(table(DOCUMENT, "AI_KB_CHUNK", "document_id", List.of(scope("kb:", "kb_key")),
                Map.of("kbKey", "kb_key", "docVersion", "doc_version")), EnumSet.of(READ, MODIFY, ADMIN));
    }

    private static ResourceDescriptor.Scope scope(String prefix, String column) {
        return new ResourceDescriptor.Scope(prefix, column);
    }

    private static ResourceDescriptor table(String type, String table, String id, List<ResourceDescriptor.Scope> scopes,
                                            Map<String, String> attributes) {
        return new ResourceDescriptor(type, table, "tenant_id", id, null, scopes, attributes);
    }

    private static void resources(ResourceRegistry r) {
        r.register(table(RES_RESOURCE, "RES_RESOURCE", "resource_id",
                List.of(scope("type:", "type_id"), scope("unit:", "org_unit_id"), scope("resource:", "parent_id")),
                Map.of("typeId", "type_id", "orgUnitId", "org_unit_id", "locationId", "location_id", "status", "status",
                        "parentId", "parent_id", "code", "code")), CATALOGUE);
        r.register(table(RES_BOOKING, "RES_BOOKING", "booking_id",
                List.of(scope("resource:", "resource_id"), scope("type:", "type_id")),
                Map.of("requesterId", "requester_id", "beneficiaryId", "beneficiary_id", "resourceId", "resource_id",
                        "typeId", "type_id", "state", "state", "entitlementClass", "entitlement_class",
                        "businessKey", "business_key")), TRANSACTIONAL);
        r.register(table(RES_ALLOTMENT, "RES_ALLOTMENT", "allotment_id", List.of(scope("resource:", "resource_id")),
                Map.of("holderId", "holder_id", "beneficiaryId", "beneficiary_id", "resourceId", "resource_id",
                        "state", "state")), TRANSACTIONAL);
        r.register(table(RES_WAITLIST, "RES_WAITLIST", "entry_id",
                List.of(scope("resource:", "resource_id"), scope("type:", "type_id")),
                Map.of("requesterId", "requester_id", "beneficiaryId", "beneficiary_id", "category", "category",
                        "state", "state", "scopeKey", "scope_key")), TRANSACTIONAL);
        r.register(table(RES_DRAW, "RES_DRAW", "draw_id", List.of(), Map.of("scopeKey", "scope_key", "drawnBy", "drawn_by")),
                EVENT_LOG);
        r.register(table(RES_EVENT, "RES_EVENT", "entity_id", List.of(),
                Map.of("entityType", "entity_type", "eventType", "event_type", "actorId", "actor_id")), EVENT_LOG);
        r.register(table(RES_SERVICE_POINT, "RES_SERVICE_POINT", "service_point_id", List.of(scope("resource:", "resource_id")),
                Map.of("resourceId", "resource_id", "status", "status")), OPERATIONAL);
        r.register(table(RES_COUNTER, "RES_COUNTER", "counter_id", List.of(scope("sp:", "service_point_id")),
                Map.of("servicePointId", "service_point_id", "operatorId", "operator_id", "status", "status")), OPERATIONAL);
        r.register(table(RES_TOKEN, "RES_TOKEN", "token_id", List.of(scope("sp:", "service_point_id")),
                Map.of("servicePointId", "service_point_id", "partyId", "party_id", "counterId", "counter_id",
                        "state", "state", "priorityClass", "priority_class")), OPERATIONAL);
        r.register(table(RES_CALL_EVENT, "RES_CALL_EVENT", "token_id", List.of(scope("sp:", "service_point_id")),
                Map.of("servicePointId", "service_point_id", "counterId", "counter_id", "actorId", "actor_id")), EVENT_LOG);
        r.register(table(RES_ROSTER, "RES_ROSTER_PERIOD", "period_id", List.of(scope("unit:", "unit_id")),
                Map.of("unitId", "unit_id", "state", "state")), TRANSACTIONAL);
        r.register(table(RES_SHIFT_TEMPLATE, "RES_SHIFT_TEMPLATE", "shift_id", List.of(),
                Map.of("code", "code", "zoneId", "zone_id")), CATALOGUE);
        r.register(table(RES_ROTATION, "RES_ROTATION", "rotation_id", List.of(), Map.of("name", "name")), CATALOGUE);
        r.register(table(RES_ROSTER_MEMBER, "RES_ROSTER_MEMBER", "person_id", List.of(scope("period:", "period_id")),
                Map.of("personId", "person_id", "seatId", "seat_id", "periodId", "period_id")), CATALOGUE);
        r.register(table(RES_COVERAGE_DEMAND, "RES_COVERAGE_DEMAND", "demand_id", List.of(scope("period:", "period_id")),
                Map.of("periodId", "period_id", "shiftId", "shift_id", "dutySeatId", "duty_seat_id")), CATALOGUE);
        r.register(table(RES_ROSTER_ASSIGNMENT, "RES_ROSTER_ASSIGNMENT", "assignment_id", List.of(scope("period:", "period_id")),
                Map.of("personId", "person_id", "seatId", "seat_id", "periodId", "period_id", "shiftId", "shift_id",
                        "state", "state", "dutySeatId", "duty_seat_id")), TRANSACTIONAL);
        r.register(table(RES_SWAP_REQUEST, "RES_SWAP_REQUEST", "swap_id", List.of(),
                Map.of("requesterId", "requester_id", "counterpartId", "counterpart_id", "state", "state",
                        "assignmentId", "assignment_id")), TRANSACTIONAL);
    }

    private static void caseFiles(ResourceRegistry r) {
        Set<Permission> casePerms = EnumSet.of(READ, READ_SENSITIVE, READ_HISTORY, EXPORT, MODIFY, START, ASSIGN, APPROVE,
                CANCEL, BULK, ADMIN);
        r.register(table(CASE_FILE, "CF_CASE", "case_id",
                List.of(scope("unit:", "org_unit"), scope("casetype:", "case_type"), scope("seat:", "owner_seat")),
                Map.of("caseType", "case_type", "orgUnit", "org_unit", "ownerSeat", "owner_seat",
                        "confidentiality", "confidentiality", "status", "status", "openedBy", "opened_by",
                        "businessKey", "business_key")), casePerms);
        r.register(table(CASE_REFERENCE, "CF_REFERENCE", "reference_id",
                List.of(scope("unit:", "org_unit"), scope("refkind:", "ref_kind"), scope("case:", "case_id")),
                Map.of("refKind", "ref_kind", "orgUnit", "org_unit", "assignedSeat", "assigned_seat",
                        "assignedPerson", "assigned_person", "sourceCategory", "source_category", "status", "status",
                        "caseId", "case_id")), casePerms);
        r.register(table(COMMITTEE, "CF_COMMITTEE", "committee_id", List.of(scope("unit:", "org_unit")),
                Map.of("committeeKind", "committee_kind", "orgUnit", "org_unit", "status", "status")), casePerms);
    }

    private static void programmes(ResourceRegistry r) {
        r.register(table(PROGRAMME, "PG_PROGRAMME", "programme_id", List.of(scope("programme:", "parent_id")),
                Map.of("kind", "kind", "ownerOrg", "owner_org", "status", "status", "parentId", "parent_id")),
                EnumSet.of(READ, READ_HISTORY, EXPORT, START, MODIFY, APPROVE, CANCEL, ADMIN));
        r.register(table(PROGRAMME_RETURN, "PG_RETURN", "return_id",
                List.of(scope("programme:", "programme_id"), scope("unit:", "org_unit"), scope("return:", "return_key")),
                Map.of("orgUnit", "org_unit", "returnKey", "return_key", "programmeId", "programme_id", "periodId", "period_id",
                        "state", "state", "submittedBy", "submitted_by")), TRANSACTIONAL);
    }

    private static void registry(ResourceRegistry r) {
        r.register(new ResourceDescriptor(REGISTRY_OBJECT, "REG_OBJECT", "tenant_id", "id", null,
                List.of(scope("type:", "type_key"), scope("kind:", "kind"), scope("holder:", "holder_party_id"),
                        scope("object:", "parent_id")),
                Map.of("typeKey", "type_key", "kind", "kind", "state", "state", "holderPartyId", "holder_party_id",
                        "parentId", "parent_id", "objectNumber", "object_number")), RECORD);
        r.register(new ResourceDescriptor(REGISTRY_PARTY, "REG_PARTY", "tenant_id", "id", null,
                List.of(scope("kind:", "party_kind"), scope("source:", "source")),
                Map.of("partyKind", "party_kind", "status", "status", "source", "source", "createdBy", "created_by")), RECORD);
        // privacy-mask aliases (field rules are keyed by these names); object checks only
        r.register(ResourceDescriptor.virtual(REGISTRY_ASSET), RECORD);
        r.register(ResourceDescriptor.virtual(REGISTRY_WORK_ORDER), TRANSACTIONAL);
        r.register(ResourceDescriptor.virtual(REGISTRY_INSTRUMENT_PUBLIC), EnumSet.of(READ, READ_SENSITIVE));
    }

    private static void virtual(ResourceRegistry r) {
        r.register(ResourceDescriptor.virtual(COMPLIANCE),
                EnumSet.of(READ, READ_AUDIT, READ_SENSITIVE, EXPORT, START, MODIFY, APPROVE, ERASE_DATA, ADMIN));
        r.register(ResourceDescriptor.virtual(CONFIG), EnumSet.of(READ, READ_HISTORY, MODIFY, APPROVE, DEPLOY, ADMIN));
        r.register(ResourceDescriptor.virtual(LEDGER),
                EnumSet.of(READ, READ_HISTORY, READ_AUDIT, EXPORT, START, APPROVE, CANCEL, ADMIN));
        r.register(ResourceDescriptor.virtual(BILLING),
                EnumSet.of(READ, READ_HISTORY, EXPORT, START, MODIFY, APPROVE, CANCEL, ADMIN));
        // sangam-field README: field.capture -> START/MODIFY, field.review -> APPROVE, field.admin -> ADMIN
        r.register(ResourceDescriptor.virtual(FIELD), EnumSet.of(READ, READ_SENSITIVE, START, MODIFY, APPROVE, ADMIN));
        // dispatch.operate -> START/MODIFY/ASSIGN/CANCEL
        r.register(ResourceDescriptor.virtual(DISPATCH), EnumSet.of(READ, START, MODIFY, ASSIGN, CANCEL, ADMIN));
        r.register(ResourceDescriptor.virtual(GEO), EnumSet.of(READ, EXPORT, MODIFY, ADMIN));
    }
}
