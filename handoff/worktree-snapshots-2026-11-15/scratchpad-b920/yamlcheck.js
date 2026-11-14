const yaml = require(process.argv[2]);
const fs = require('fs'), path = require('path');
const dir = process.argv[3];
let bad = 0;
for (const f of fs.readdirSync(dir).filter(n => n.endsWith('.yml')).sort()) {
  try {
    const doc = yaml.load(fs.readFileSync(path.join(dir, f), 'utf8'));
    const jobs = Object.keys(doc.jobs || {});
    for (const [id, j] of Object.entries(doc.jobs || {})) {
      const needs = [].concat(j.needs || []);
      for (const n of needs) if (!jobs.includes(n)) { console.log(`${f}: job ${id} needs unknown ${n}`); bad++; }
    }
    console.log(`yaml ok ${f} (${jobs.length} jobs)`);
  } catch (e) { console.log(`YAML ERROR ${f}: ${e.message}`); bad++; }
}
process.exit(bad ? 1 : 0);
