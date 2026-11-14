import { readFileSync as r, writeFileSync as w } from 'node:fs';
const s=String.raw`C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad`;
const extra={en:{'task.tab.diagram':'Diagram','footer.shortcuts':'Shortcuts: Alt+Shift+T inbox, F filters, S start'},hi:{'task.tab.diagram':'आरेख','footer.shortcuts':'शॉर्टकट: Alt+Shift+T इनबॉक्स, F फ़िल्टर, S आरंभ'}};
for(const l of ['en','hi']){const p='src/i18n/locales/'+l+'.json';const j=JSON.parse(r(p,'utf8'));
 const m={...JSON.parse(r(s+'/i18n-a.'+l+'.json','utf8')),...JSON.parse(r(s+'/i18n-b.'+l+'.json','utf8')),...extra[l]};
 const conflicts=[];
 for(const[k,v]of Object.entries(m)){const ps=k.split('.');let c=j;let ok=true;for(const x of ps.slice(0,-1)){if(typeof c[x]==='string'){conflicts.push(k);ok=false;break}c=c[x]??=({});}if(ok)c[ps.at(-1)]=v;}
 if(conflicts.length)console.log('conflicts',conflicts);
 w(p,JSON.stringify(j,null,2)+'\n');}
