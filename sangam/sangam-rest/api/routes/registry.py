# SPDX-License-Identifier: Apache-2.0
# sangam-registry: the module's own Spring controllers under /api/v1/registry (registry README §2), registered by
# sangam-rest's RegistryRestConfiguration. Operations are derived from the controller source (_v2_spring.py).
TAGS.append(("Registry", "Registers, numbering, instruments, parties (payees, venue parties), assets and custody "
                         "(sangam-registry; public verification endpoints are unauthenticated and rate-limited)"))
_V2_REGISTRY_PUBLIC = ("/registry/instruments/public/{tenant}/verify", "/registry/instruments/public/verify-qr",
                       "/registry/registers/public/{tenant}/extracts/verify")
v2_spring_routes("sangam-registry/src/main/java/org/sangam/registry/rest", "Registry", "registry", "registry",
                 "registry.read", "registry.write", ["R19", "R41", "R42", "R50", "R53", "R74"],
                 no_cmd=("match", "matchParty"), public_paths=_V2_REGISTRY_PUBLIC)
# M-051: the Map-backed request bodies above are typed from the keys the registry services read (_v2_bodykeys.py). These
# are the uses of a whole map that read no key; each nested map named here stays an open object in the contract.
bk_opaque_target({
    "Json.write": "serialised and stored as given (REG_* JSON column); no member is read",
    "Json.canonical": "canonicalised for a hash or for storage; no member is read",
    "Maps.of": "put whole into an audit event or result payload; no member is read",
    "PartyService.fieldNames": "only the member names are recorded in the audit event",
})
bk_opaque({
    ("CustodyService.inspect", "details.forEach"): "free-form inspection details: every member is recorded as an event detail",
    ("CustodyService.executeDisposal", "putAll"): "free-form disposal details merged into the order's details",
    ("CustodyService.executeDisposal", "details.forEach"): "free-form disposal details: every member is recorded as an "
                                                           "event detail",
    ("InstrumentService.endorse", "new Endorsement"): "free-form endorsement content, stored and returned as given",
    ("PartyService.update", "new Party"): "the party's names map, stored as given",
    ("InstrumentService.amend", "putAll"): "attribute patch merged into the instrument's attributes, then validated "
                                           "against the object type's attribute schema (tenant-defined)",
    ("InstrumentService.renew", "validate"): "attribute patch validated against the object type's attribute schema "
                                             "(tenant-defined)",
    ("RegisteredObjectService.update", "validate"): "attribute patch validated against the object type's attribute "
                                                    "schema (tenant-defined)",
    ("RegisterBookService.append", "validate"): "register columns validated against the register's column definitions "
                                                "(tenant-defined)",
    ("RegisterBookService.correct", "putAll"): "corrected columns merged into the original entry's columns, then "
                                               "validated against the register's column definitions (tenant-defined)",
    ("PartyService.snapshot", "put"): "free-form extra content stored in the snapshot",
    ("NumberSeriesDefinition.format", "attrs.get"): "the attribute a {attr:<name>} token of the series pattern names",
    ("ObjectTypeDefinition.from", "docs.forEach"): "document templates keyed by purpose (ISSUE, RENEWAL, ...)",
})
