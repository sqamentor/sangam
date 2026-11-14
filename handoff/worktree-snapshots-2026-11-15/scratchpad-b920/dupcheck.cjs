// Checks en.json parses and that mod.modeller2 has no duplicate keys.
const s = require('fs').readFileSync(process.argv[2], 'utf8');
JSON.parse(s);
const start = s.indexOf('"modeller2": {');
const end = s.indexOf('\n    }', start);
const block = s.slice(start, end);
const keys = [...block.matchAll(/^ {6}"(\w+)":/gm)].map((x) => x[1]);
console.log('keys', keys.length, 'dups', keys.filter((x, i) => keys.indexOf(x) !== i));
