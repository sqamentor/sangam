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
