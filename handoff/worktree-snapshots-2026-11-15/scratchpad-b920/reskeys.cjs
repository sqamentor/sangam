const fs = require('fs');
const ui = 'D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui';
const sp = 'C:/Users/LOKEND~2/AppData/Local/Temp/claude/b920001b93f0ae3b/6e98c2a7-cdac-4783-9098-832546572812/scratchpad';
const flat = (o, p = '', out = {}) => { for (const [k, v] of Object.entries(o)) { const key = p ? `${p}.${k}` : k; if (v && typeof v === 'object') flat(v, key, out); else out[key] = v; } return out; };
const en = flat(JSON.parse(fs.readFileSync(ui + '/src/i18n/locales/en.json', 'utf8')));
const k1 = flat(JSON.parse(fs.readFileSync(sp + '/keys-m051.json', 'utf8')));
const s = fs.readFileSync(ui + '/src/modules/resources.tsx', 'utf8');
const used = new Set();
for (const m of s.matchAll(/\bf\.(?:text|num|money|date|dt|area|json|bool)\('([\w.]+)'(?:\)|, undefined)/g)) used.add('fld.' + m[1]);
for (const m of s.matchAll(/\bc\('(\w+)'\)/g)) used.add('fld.' + m[1]);
for (const m of s.matchAll(/\bf\.\w+\('[\w.]+', '([a-z]+\.[\w.]+)'/g)) used.add(m[1]);
for (const m of s.matchAll(/\bf\.select\('([\w.]+)', \[([^\]]*)\](, '([\w.]+)')?/g)) { if (m[4]) used.add(m[4]); else used.add('fld.' + m[1]); for (const v of m[2].matchAll(/'([^']+)'/g)) used.add('opt.' + v[1]); }
const missing = [...used].filter((k) => !(k in en) && !(k in k1)).sort();
console.log(missing.join('\n'));
