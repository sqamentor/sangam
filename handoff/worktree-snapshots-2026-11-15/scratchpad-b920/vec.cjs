const c = require('crypto');
const k = Buffer.from([...Array(32).keys()]);
const iv = Buffer.from('AAECAwQFBgcICQoL', 'base64');
const ci = c.createCipheriv('aes-256-gcm', k, iv);
ci.setAAD(Buffer.from('t1|d1|op-1|u1'));
const o = Buffer.concat([ci.update(Buffer.from('{"secret":"नोट"}', 'utf8')), ci.final(), ci.getAuthTag()]);
console.log(o.toString('base64'));
