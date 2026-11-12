# SPDX-License-Identifier: Apache-2.0
# sangam-resources: booking (/api/v1/resources), queues (/api/v1/queues) and rosters (/api/v1/rosters) over the
# module's REST ports (resources README "REST ports"), served by sangam-rest's modules/resources controllers and
# registered by ResourcesRestConfiguration. Operations are derived from the controller source (_v2_spring.py).
TAGS.append(("Resources", "Bookable resources, calendars, availability, holds and bookings, waitlists, draws and "
                          "allotments (sangam-resources N01-N06)"))
TAGS.append(("Queues", "Service points, counters and tokens, call-next, SLA breaches and display boards "
                       "(sangam-resources N07)"))
TAGS.append(("Rosters", "Shifts, rotations, roster periods, generation, publication, assignments and swaps "
                        "(sangam-resources N08)"))
_V2_RESOURCES_DIR = "sangam-rest/src/main/java/org/sangam/rest/modules/resources"
_V2_RESOURCES_REQS = ["R19", "R28", "R41", "R42"]
v2_spring_routes(_V2_RESOURCES_DIR, "Resources", "resources", "resources", "resources.read", "resources.write",
                 _V2_RESOURCES_REQS, no_cmd=("searchAvailable", "quote", "verifyDraw"),
                 files=["ResourcesController.java"])
v2_spring_routes(_V2_RESOURCES_DIR, "Queues", "queues", "resources", "resources.read", "resources.write",
                 _V2_RESOURCES_REQS, files=["QueueController.java"])
v2_spring_routes(_V2_RESOURCES_DIR, "Rosters", "rosters", "resources", "resources.read", "resources.write",
                 _V2_RESOURCES_REQS, files=["RosterController.java"])
# M-051: the Map-backed request bodies above are typed from the keys the resources services read (_v2_bodykeys.py).
# These are the uses of a whole map that read no key; each nested map named here stays an open object in the contract.
bk_opaque_target({
    "Sql.toJson": "serialised whole into an event payload; no member is read",
})
bk_opaque({
    ("ResourcesContext.event", "record"): "the event payload is forwarded whole to the audit port; no member is read",
    ("ResourceType.fromMap", "new ResourceType"): "free-form extra settings of the resource type, stored as given",
    ("AvailabilityCalendar.fromMap", "hm.entrySet"): "opening hours keyed by day (MONDAY ... SUNDAY, ALL or DAILY), "
                                                     "each a list of HH:mm-HH:mm windows",
})
