import os
import sys

os.chdir(sys.argv[1])
p = 'sangam-persistence/README.md'
lines = open(p, encoding='utf-8').read().split('\n')
i = [k for k, l in enumerate(lines) if l.startswith('| `AI_KB_CHUNK.embedding_vec` (PostgreSQL only, optional)')]
j = [k for k, l in enumerate(lines) if l.startswith('| `src/main/resources/db/changelog/sangam-history-partitioning.xml`')]
assert len(i) == 1 and len(j) == 1
lines.insert(j[0] + 1, '| `src/main/resources/db/changelog/sangam-oracle-vector.xml`, `oracle-vector/oracle.sql` (generated) | owner decision 247 (optional, context `oracle-vector`, not in the master) |')
lines.insert(i[0] + 1, '| `AI_KB_CHUNK.embedding_vec` (Oracle only, optional) | sangam-ai Oracle vector index (changeset `0045-10-ai_kb_chunk-oracle-vector`, context `oracle-vector`) | owner decision 247: nullable `VECTOR` column (any dimension and format, no vector index) in `db/changelog/oracle-vector/oracle.sql`, included only by `sangam-oracle-vector.xml` (Oracle-only precondition) and never by the master, because Liquibase runs context-tagged changesets when no context is given and Oracle 19c and 21c have no `VECTOR`; applied by `SangamSchema.applyOracleVector` only where `sangam.ai.kb.vector-index=oracle` on Oracle AI Database 23ai or later (the server checks the version) |')
open(p, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
print('ok')
