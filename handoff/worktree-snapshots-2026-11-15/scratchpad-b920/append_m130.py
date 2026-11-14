import sys
h = sys.argv[1]
ts = sys.argv[2]
claims = (
    f"{ts} B53 RELEASE W3-L M-130 sangam/sangam-ops/src/main/java/org/sangam/ops/bulk/BulkIngest.java E9C5243A2D2113B5ABB92C7106BD3CEFFF4AA4892988E1269A63991E339A2C33; "
    "sangam/sangam-ops/src/main/java/org/sangam/ops/bulk/CreatorSnapshot.java (new, own module) 2E5A0D2AD5B5F3548355B6FBBA6A94E4E25C8BADCBC082BCEFB837A197269CAF; "
    "sangam/sangam-ops/src/main/java/org/sangam/ops/bulk/IdentitySink.java (new, own module) 62ADD7F1AE245CC54B2A056475CBC73BB635588755CCC88CD3A040086492FE1F; "
    "sangam/sangam-ops/src/main/java/org/sangam/ops/OpsSchema.java 8B146A525A8E827990E9E228FB46EE708F4D3AFD99FCA8A02F571A28E37991ED; "
    "sangam/sangam-ops/README.md E2154BE0D2757E56EC3DD651E4812549F0F3CABEEFCAA75A5F48DE3F6887759E; "
    "sangam/sangam-ops/src/test/java/org/sangam/ops/bulk/BulkIngestCreatorIdentityH2Test.java (new) 4C0A4C93690FE5300875F53287DEF00E0D3C9DD313F8204069666138C2DC2BA7; "
    "sangam/sangam-resources/src/main/java/org/sangam/resources/catalogue/ResourceCatalogue.java 169A31024761B9CD1AB44AFB3EB938E2D665EA834891A458D56F101408E732E3; "
    "sangam/sangam-resources/src/main/java/org/sangam/resources/roster/RosterBulkApply.java (new) 7860E0915B55167506D11DBDA8BB50EAE71F16E5FA2EEF8F50632708E6900E74; "
    "sangam/sangam-resources/src/test/java/org/sangam/resources/catalogue/ResourceCatalogueUpsertH2Test.java (new) 04643EEAFE61D42C0971962613459C61B45FE04B01E839E6656BF6B3A52507B3; "
    "sangam/sangam-resources/src/test/java/org/sangam/resources/roster/RosterBulkApplyMatchTest.java (new) 36F9C49ACBBBCBE14EB348B99859C5DA55097F904D313F707126D3249C84A778; "
    "sangam/sangam-config/src/main/java/org/sangam/config/store/JdbcConfigStore.java D2FA6F6290E15D2BEE2DCC12A141A518E2872528EF26207DEFE7CC7B9650A15B; "
    "sangam/sangam-config/src/test/java/org/sangam/config/store/MasterCodeCallerConnectionH2Test.java (new) A06044AC20C5EC0B76D6D6C1FE27B0943A442CB17B6DF61EF643FBE553780468; "
    "sangam/sangam-server/src/main/java/org/sangam/server/bridge/ModuleBulkSinks.java (new) F33E6374D54CFF16FF266941222D5F50FA3436E266E481472E5523987AEFA6D3; "
    "sangam/sangam-server/src/main/java/org/sangam/server/config/OpsWiring.java 9CC24E944DBDF66C36CF9C1F00D8D6C528F97A4E020ABBA8E60BB8E141ACA8F4; "
    "sangam/sangam-server/src/test/java/org/sangam/server/bridge/ModuleBulkSinksTest.java (new) E1D0755E2E8FF95DC943E808CC07691F37155E68142247310735A9839C99017E. B53 holds no claims.\n"
)
board = (
    f"{ts} | W3-L/B53 | DONE | M-130 decision 59 (A) coded: IdentitySink jobs snapshot the creating request's resolved identity (TenantContext via OpsWiring) in OPS_BULK_JOB.creator_identity (CreatorSnapshot v1: tenant, person, subject, groups, roles, seats, actingForSeats, authMethod; no client IP or claims); every row is applied as that creator so the target guard re-checks per object; system principal refused (SYSTEM_CREATOR_REFUSED), no identity refused (CREATOR_IDENTITY_REQUIRED), job without snapshot rejects rows (CREATOR_IDENTITY_MISSING). Sinks in sangam-server bridge/ModuleBulkSinks registered by OpsWiring: resources.resource (ResourceCatalogue.upsert, guard checked even on an unchanged row), resources.rosterDuty (RosterBulkApply over public RosterService API, unassign+assign in one transaction on change), config.masterCode (JdbcConfigStore.put(Connection,MasterCode), tenant's own scope only, key fixed per external id). Hashes in FILE-CLAIMS RELEASE {ts}. Tests written, not run. Gate -pl sangam-ops,sangam-resources,sangam-config -am clean test-compile (target-w3l) EXIT 0; -pl ...,sangam-server -am stops upstream in sangam-programme ProgrammeRestAdapter.java:[537,28] duplicate currentConfig (W3-M, already BROKEN 12:29:24Z); javac pre-check of ModuleBulkSinks, OpsWiring, ModuleBulkSinksTest against the live sources EXIT 0 | integrator, W3-G, W3-M\n"
    f"{ts} | W3-L/B53 | INFO | M-130 registry parties PARTIAL: no sink coded. W3-E has not posted DONE on the RegistryPortBridges connection defect (last W3-E status 12:07:26Z: design ready, waiting for GRANT). Evidence: PartyService.create has no natural key (id = Db.newId, PK id only); REG_PARTY_IDENTIFIER (tenant_id, id_type, value_hash) is non-unique and only unique-configured identifier types are enforced (may hold for review); matching may auto-merge on create. Owner question W3L-1 in the W3-L report | integrator, W3-E\n"
)
open(h + '/FILE-CLAIMS.md', 'a', encoding='utf-8', newline='\n').write(claims)
open(h + '/WAVE3-SYNC-BOARD.md', 'a', encoding='utf-8', newline='\n').write(board)
print('ok')
