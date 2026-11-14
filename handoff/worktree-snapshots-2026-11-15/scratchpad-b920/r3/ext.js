const y=require(process.argv[2]);const fs=require('fs');const d=y.load(fs.readFileSync(process.argv[3],'utf8'));
const run=d.jobs.e2e.steps.find(s=>s.name==='End-to-end tests').run;
const inner=run.slice(run.indexOf("bash -c '")+9, run.lastIndexOf("'"));
fs.writeFileSync(process.argv[4]+'\\inner.sh',inner);
const py=inner.split('<<"PY"\n')[1].split('\nPY')[0];fs.writeFileSync(process.argv[4]+'\\e2e_check.py',py);
console.log('inner last line: ['+inner.split('\n').pop()+']; single quotes inside inner: '+(inner.match(/'/g)||[]).length);
