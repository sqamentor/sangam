import { createHash } from 'node:crypto';
const s = '{"answers":{"q1":true,"q2":"2.5","q3":"नमस्ते \\"x\\"\\n","q5":["1",null,"b"]},"appVersion":"0.1.0","assignmentId":"a-1","baseVersion":"3","deviceId":"dev-1","deviceSeq":"1","deviceTime":"2026-11-12T10:00:00Z","fix":{"accuracyM":"12","lat":"26.9","lon":"75.8","mockLocation":false},"media":[],"operationId":"op-1","packetId":"op-1","payload":{},"type":"VISIT_SUBMIT"}';
console.log(s);
console.log(createHash('sha256').update(s, 'utf8').digest('hex'));
