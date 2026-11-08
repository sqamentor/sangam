# SPDX-License-Identifier: Apache-2.0
"""EU sector packs (SL-33): GDPR data-subject rights.

Source: Regulation (EU) 2016/679 (GDPR), OJ L 119, 4.5.2016: Art. 12(3) (answer without undue delay and at the latest
within one month of receipt; extendable by two further months where necessary, taking into account complexity and
number, with the data subject told of the extension and the reasons within the first month), Art. 12(4) (no action:
reasons and the possibility of a complaint to a supervisory authority and a judicial remedy, within one month),
Art. 12(5) (manifestly unfounded or excessive: reasonable fee or refusal, burden on the controller), Art. 12(6)
(additional information to confirm identity), Art. 15-18 and 20-21 (access, rectification, erasure, restriction,
portability, objection), Art. 17(3) (erasure exceptions), Art. 19 (notify recipients of rectification, erasure,
restriction). The periods are DMN data (tenant overridable); member-state derogations (Art. 23) are not modelled
and are marked unverified per tenant.
"""
from lib import C, q
from money import M  # noqa: F401
from common import end_ok
from gov2 import simple_seeds
from sector import spack, start_form, clock

import forms

forms.L.update({k: v for k, v in {
    'dsrRight': ('Right exercised (GDPR Art. 15-22)', 'प्रयुक्त अधिकार (जीडीपीआर अनु. 15-22)', 'select'),
    'dataSubjectName': ('Data subject', 'डेटा विषय', 'string'),
    'identityEvidence2': ('Additional identity information (Art. 12(6))', 'अतिरिक्त पहचान जानकारी (अनु. 12(6))', 'text'),
    'extensionReason': ('Reason for the extension (complexity / number of requests)', 'विस्तार का कारण', 'text'),
    'erasureException': ('Art. 17(3) exception relied on (if any)', 'अनु. 17(3) अपवाद (यदि कोई)', 'text'),
    'refusalReasons': ('Reasons, supervisory-authority complaint and judicial remedy (Art. 12(4))',
                       'कारण, पर्यवेक्षी प्राधिकरण शिकायत एवं न्यायिक उपचार (अनु. 12(4))', 'text'),
    'responseText': ('Response to the data subject', 'डेटा विषय को उत्तर', 'text'),
}.items() if k not in forms.L})
forms.OPT.update({k: v for k, v in {
    'fulfil': ('Fulfil', 'पूर्ण करें'), 'extend': ('Extend (two further months)', 'विस्तार (दो और माह)'),
    'noAction': ('No action (reasons given)', 'कोई कार्रवाई नहीं (कारण सहित)'),
    'verified': ('Verified', 'सत्यापित'), 'notVerified': ('Not verified', 'सत्यापित नहीं'),
}.items() if k not in forms.OPT})


def gdpr_dsr():
    pk = spack('eu', 'gdpr-dsr', 'GDPR data subject rights request (Art. 12, 15-22)',
               'जीडीपीआर डेटा विषय अधिकार अनुरोध (अनु. 12, 15-22)', 'Legal / privacy (EU)',
               'Request received and logged; identity confirmed (Art. 12(6)); one-month clock from receipt (Art. '
               '12(3)), one extension of two further months with the reasons told inside the first month; the right '
               'is routed (access copy, rectification, erasure with the Art. 17(3) check, restriction, portability, '
               'objection); recipients notified (Art. 19); no action only with reasons and the complaint and judicial '
               'remedy route (Art. 12(4)).',
               ['SL-33', 'GDPR-12-3', 'GDPR-12-4', 'GDPR-12-6', 'GDPR-15', 'GDPR-16', 'GDPR-17', 'GDPR-18', 'GDPR-19',
                'GDPR-20', 'GDPR-21', 'GP-35'],
               [('records.summary', 'a', '', 'Copy of the personal data (Art. 15(3))'),
                ('records.rectify', 'a', '', 'Rectification'), ('records.erase', 'a', '', 'Erasure'),
                ('records.restrict', 'a', '', 'Restriction of processing'),
                ('records.export', 'a', '', 'Portable export (Art. 20)'),
                ('recipients.notify', 'b', '', 'Notice to recipients (Art. 19)')])
    p = pk.proc('sangam-sec-eu-gdpr-dsr', 'Data subject rights request', '',
                start={'materialFields': 'dataSubjectName,dsrRight'})
    s = p.root
    st = start_form(p, s, 'gd_start', 'Request received', 'sec-eu-gdpr-dsr/request',
                    'Data subject request | डेटा विषय अनुरोध', ['dataSubjectName*', 'dsrRight*', 'description'])
    d = s.setv('gd_defaults', 'Configuration', defaults='dsrRight=ACCESS;dataSubjectName=@customerId;dpoSeat=DPO-EU;'
               'gdprExtended=false;applicantRef=@customerId')
    r = s.rule('gd_cfg', 'Response periods (DMN, Art. 12(3))', 'sec-eu-gdpr-periods')
    ck = clock(s, 'gd_clock', 'gdpr', '${responseSpec}')
    s.seq(st, d, r, ck)
    vf = s.user('gd_verify', 'Confirm identity (Art. 12(6)) | पहचान की पुष्टि', '${dpoSeat}', form='sec-eu-gdpr-dsr/verify',
                decision=('verifyDecision', ['verified', 'notVerified']), fields=['identityEvidence2', 'remarks'],
                due='${gdprDueAt}')
    s.flow(ck, vf)
    vg = s.xgw('gd_verifyGw')
    s.flow(vf, vg)
    dec = s.user('gd_decide', 'Decide the request | अनुरोध पर निर्णय', '${dpoSeat}', form='sec-eu-gdpr-dsr/decide',
                 decision=('dsrDecision', ['fulfil', 'extend', 'noAction']), fields=['remarks'], due='${gdprDueAt}')
    s.flow(vg, dec, None, 'verified', default=True)
    no = s.user('gd_noAction', 'No action: reasons, complaint and remedy (Art. 12(4)) | कोई कार्रवाई नहीं',
                '${dpoSeat}', form='sec-eu-gdpr-dsr/no-action', decision=None, fields=['refusalReasons*'],
                due='${gdprDueAt}')
    s.flow(vg, no, "verifyDecision == 'notVerified'", 'identity not confirmed')
    dg = s.xgw('gd_decGw')
    s.flow(dec, dg)
    ext = s.user('gd_extend', 'Tell the data subject of the extension and why | विस्तार की सूचना', '${dpoSeat}',
                 form='sec-eu-gdpr-dsr/extend', decision=None, fields=['extensionReason*'], due='${gdprDueAt}')
    e2 = s.svc('gd_extClock', 'Extend by two further months (once)', C['SLA'],
               {'op': 'DEADLINE', 'clock': 'gdpr', 'spec': '${extensionSpec}'})
    e3 = s.setv('gd_extended', 'Extension used', values='gdprExtended=true')
    s.flow(dg, ext, "dsrDecision == 'extend' && !gdprExtended", 'extend (once)')
    s.seq(ext, e2, e3, dec)
    s.flow(dg, no, "dsrDecision == 'noAction'", 'no action')
    tg = s.xgw('gd_rightGw', 'Which right?')
    s.flow(dg, tg, None, 'fulfil', default=True)
    j = s.xgw('gd_join')
    for right, conn, op, step, notify in (('ACCESS', 'records.summary', 'summary', 'access', False),
                                          ('RECTIFICATION', 'records.rectify', 'rectify', 'rectify', True),
                                          ('RESTRICTION', 'records.restrict', 'restrict', 'restrict', True),
                                          ('PORTABILITY', 'records.export', 'export', 'export', False),
                                          ('OBJECTION', 'records.restrict', 'object', 'object', False)):
        c = s.conn('gd_' + step, 'Art. right: ' + right.lower(), conn, op, 'submit', 'customerId,dsrRight', step,
                   step=step)
        s.flow(tg, c, "dsrRight == '%s'" % right, right.lower())
        if notify:
            n = s.conn('gd_' + step + 'Rcp', 'Notify recipients (Art. 19)', 'recipients.notify', 'notify', 'submit',
                       'customerId,dsrRight', step + 'Rcp', step=step + '-recipients')
            s.seq(c, n, j)
        else:
            s.flow(c, j)
    er = s.user('gd_erasure', 'Erasure: check the Art. 17(3) exceptions | मिटाना: अनु. 17(3) अपवाद', '${dpoSeat}',
                form='sec-eu-gdpr-dsr/erasure', decision=('eraseDecision', ['erase', 'retain']),
                fields=['erasureException', 'remarks*'], due='${gdprDueAt}')
    s.flow(tg, er, None, 'erasure', default=True)
    eg = s.xgw('gd_eraseGw')
    s.flow(er, eg)
    e1 = s.conn('gd_erase', 'Erase', 'records.erase', 'erase', 'submit', 'customerId', 'erase', step='erase')
    en = s.conn('gd_eraseRcp', 'Notify recipients (Art. 19)', 'recipients.notify', 'notify', 'submit',
                'customerId,dsrRight', 'eraseRcp', step='erase-recipients')
    s.flow(eg, e1, "eraseDecision == 'erase'", 'erase')
    s.seq(e1, en, j)
    s.flow(eg, no, None, 'exception applies: reasons', default=True)
    rp = s.user('gd_respond', 'Respond to the data subject | डेटा विषय को उत्तर', '${dpoSeat}',
                form='sec-eu-gdpr-dsr/respond', decision=None, fields=['responseText*'], due='${gdprDueAt}')
    s.flow(j, rp)
    s.flow(rp, end_ok(p, s, 'gd_end', 'Answered'))
    s.flow(no, s.end('gd_endNoAction', 'No action (reasons and remedies given)'))
    s.side_exits('gd')
    pk.dmn('sec-eu-gdpr-periods', 'GDPR response periods (Regulation (EU) 2016/679 Art. 12(3); tenant overridable)',
           'FIRST', [('Right', 'dsrRight', 'string')],
           [('responseSpec', 'Respond within (from receipt)', 'string'),
            ('extensionSpec', 'Extended deadline (from the extension notice, two further months)', 'string')],
           [(['-'], [q('P1M'), q('P2M')], 'Art. 12(3): one month, extendable by two further months')],
           doc='SL-33. Art. 23 member-state restrictions: unverified per tenant (override this table).')
    simple_seeds(pk, 'PRIVACY-EU', [('DPO-EU', 'DPO', None, 'Data protection officer', 'डेटा संरक्षण अधिकारी')], 'gdp')
    pk.accept('SEC-EU-GDPR-1', 'Erasure request answered with recipients notified', ['GDPR-17', 'GDPR-19'],
              'an erasure request from a verified data subject', 'the DPO erases (no Art. 17(3) exception)',
              'the data is erased, recipients are notified and the request is answered',
              vars={'dsrRight': 'ERASURE'})


def build():
    gdpr_dsr()
