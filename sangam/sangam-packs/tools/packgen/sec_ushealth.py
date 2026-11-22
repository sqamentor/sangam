# SPDX-License-Identifier: Apache-2.0
"""USA healthcare packs (US-HEALTH workstream): sec.ushealth.*.

Requirements are numbered US-HLT-nnn in ../../../../spike/results/US-HEALTH-WORKFLOWS.md. Labels are English with
Spanish as the second locale (Pack second='es'; packs.LOCALES['es']). Every regulatory time limit is a DMN output, so
a tenant overrides it with pk.override(...) or a tenant DMN deployment (GP-37) without touching the models. External
systems are reached through ConnectorGateway; the generic connectors are in sangam-connectors-health
(HealthConnectors.packBindings()). Values marked SYNTHETIC are pilot values the tenant replaces.
"""
from lib import C, q
from money import M  # noqa: F401  (amounts inside FEEL expressions, minor units)
from packs import Pack, LOCALES
from common import end_ok, checked_query, check_conditions, unconfirmed_hold
from sector import SIDE, clock, at_date, maybe
import cited
import forms

# --------------------------------------------------------------------------------------------- labels (en, es, type)
F = {
    'patientName': ('Patient name', 'Nombre del paciente', 'string'),
    'memberId': ('Member ID', 'Número de afiliado', 'string'),
    'payerId': ('Payer ID', 'Identificador del pagador', 'string'),
    'payerName': ('Payer', 'Pagador', 'string'),
    'payerProgram': ('Payer program (MA, MEDICAID, CHIP, QHP_FFE, COMMERCIAL, MEDICARE_FFS)',
                     'Programa del pagador (MA, MEDICAID, CHIP, QHP_FFE, COMERCIAL, MEDICARE_FFS)', 'select'),
    'providerNpi': ('Provider NPI', 'NPI del proveedor', 'string'),
    'serviceTypes': ('Service type codes (X12 EQ01)', 'Códigos de tipo de servicio (X12 EQ01)', 'string'),
    'visitType': ('Visit type', 'Tipo de visita', 'select'),
    'screeningNotes': ('Medical screening examination findings', 'Resultados del examen médico de detección', 'text'),
    'stabilised': ('Emergency condition stabilised', 'Condición de emergencia estabilizada', 'boolean'),
    'benefitSummary': ('Benefits (271)', 'Beneficios (271)', 'readonly'),
    'estimatedPatientShare': ('Estimated patient responsibility (USD)', 'Responsabilidad estimada del paciente (USD)',
                              'decimal'),
    'coverageResolution': ('Coverage resolution notes', 'Notas de resolución de cobertura', 'text'),
    'procedureCode': ('Procedure code (CPT / HCPCS)', 'Código de procedimiento (CPT / HCPCS)', 'string'),
    'diagnosisCode': ('Diagnosis code (ICD-10-CM)', 'Código de diagnóstico (CIE-10-MC)', 'string'),
    'priority': ('Priority', 'Prioridad', 'select'),
    'questionnaireResponse': ('Documentation questionnaire (DTR)', 'Cuestionario de documentación (DTR)', 'text'),
    'documents': ('Documents', 'Documentos', 'attachments'),
    'remarks': ('Remarks', 'Observaciones', 'text'),
    'denialReason': ('Specific denial reason', 'Motivo específico de la denegación', 'readonly'),
    'peerToPeerNotes': ('Peer-to-peer discussion notes', 'Notas de la conversación entre pares', 'text'),
    'appealGrounds': ('Grounds of appeal', 'Fundamentos de la apelación', 'text'),
    'appealOutcome': ('Appeal outcome', 'Resultado de la apelación', 'select'),
    'escalationNotes': ('Escalation to the payer / regulator', 'Escalamiento al pagador / regulador', 'text'),
    'patientControlNumber': ('Patient control number', 'Número de control del paciente', 'string'),
    'totalCharge': ('Total charge (USD)', 'Cargo total (USD)', 'decimal'),
    'priorAuthNumber': ('Prior authorization number', 'Número de autorización previa', 'string'),
    'serviceDate': ('Date of service', 'Fecha del servicio', 'date'),
    'claimType': ('Claim type (837P professional / 837I institutional)',
                  'Tipo de reclamación (837P profesional / 837I institucional)', 'select'),
    'codingNotes': ('Coding and scrub notes (NCCI, modifiers, NPI)', 'Notas de codificación y depuración (NCCI, modificadores, NPI)',
                    'text'),
    'rejectionFix': ('Correction of the front-end rejection', 'Corrección del rechazo inicial', 'text'),
    'remitSummary': ('Remittance (835)', 'Remesa (835)', 'readonly'),
    'adjustmentCodes': ('Adjustment reason codes (CARC / RARC)', 'Códigos de motivo de ajuste (CARC / RARC)', 'readonly'),
    'writeOffAmount': ('Write-off amount (USD)', 'Importe de cancelación (USD)', 'decimal'),
    'patientBalance': ('Patient balance (USD)', 'Saldo del paciente (USD)', 'decimal'),
    'serviceDescription': ('Items and services expected', 'Artículos y servicios previstos', 'text'),
    'expectedCharges': ('Expected charges in the good-faith estimate (USD)',
                        'Cargos previstos en el presupuesto de buena fe (USD)', 'decimal'),
    'scheduledDate': ('Scheduled date of service', 'Fecha programada del servicio', 'date'),
    'daysUntilService': ('Business days between scheduling and service (0 = on request)',
                         'Días hábiles entre la programación y el servicio (0 = a solicitud)', 'integer'),
    'coProviders': ('Co-providers and co-facilities', 'Coproveedores y coinstalaciones', 'text'),
    'billedAmount': ('Billed amount (USD)', 'Importe facturado (USD)', 'decimal'),
    'billDate': ('Date the bill was received', 'Fecha de recepción de la factura', 'date'),
    'reviewRequested': ('Individual requested a review of the denial', 'La persona solicitó la revisión de la denegación', 'boolean'),
    'determinationAmount': ('Amount determined by the SDR entity (USD)',
                            'Importe determinado por la entidad SDR (USD)', 'decimal'),
    'medicareBeneficiary': ('Medicare beneficiary', 'Beneficiario de Medicare', 'boolean'),
    'observationStatus': ('Outpatient in observation', 'Paciente ambulatorio en observación', 'boolean'),
    'dischargeRisk': ('Risk of adverse consequences without a plan', 'Riesgo de consecuencias adversas sin un plan',
                      'text'),
    'patientGoals': ('Goals and treatment preferences of the patient and caregiver',
                     'Objetivos y preferencias de tratamiento del paciente y el cuidador', 'text'),
    'postAcuteProvider': ('Post-acute provider chosen by the patient', 'Proveedor de atención posaguda elegido por el paciente',
                          'string'),
    'choiceListGiven': ('List of available HHA / SNF / IRF / LTCH with quality data given',
                        'Lista de HHA / SNF / IRF / LTCH disponibles con datos de calidad entregada', 'boolean'),
    'noticeSigned': ('Notice signed or refusal documented', 'Aviso firmado o negativa documentada', 'boolean'),
    'requesterName': ('Requester name', 'Nombre del solicitante', 'string'),
    'requestType': ('Request type (individual, personal representative, third-party direction)',
                    'Tipo de solicitud (individuo, representante personal, envío a terceros)', 'select'),
    'recordsRequested': ('Records requested (designated record set)', 'Registros solicitados (conjunto designado de registros)',
                         'text'),
    'formatRequested': ('Form and format requested', 'Forma y formato solicitados', 'select'),
    'thirdPartyRecipient': ('Third-party recipient', 'Destinatario tercero', 'string'),
    'identityEvidence': ('Identity and authority evidence', 'Prueba de identidad y autoridad', 'attachments'),
    'extensionReason': ('Reason for the 30-day extension and expected date', 'Motivo de la prórroga de 30 días y fecha prevista',
                        'text'),
    'denialBasis': ('Basis of the denial (45 CFR 164.524(a))', 'Fundamento de la denegación (45 CFR 164.524(a))', 'text'),
    'infoBlockingException': ('Information-blocking exception (45 CFR 171)', 'Excepción de bloqueo de información (45 CFR 171)',
                              'select'),
    'copyFee': ('Reasonable cost-based fee (USD)', 'Tarifa razonable basada en costos (USD)', 'decimal'),
    'incidentSummary': ('What happened', 'Qué ocurrió', 'text'),
    'discoveryDate': ('Date of discovery', 'Fecha de descubrimiento', 'date'),
    'affectedCount': ('Individuals affected', 'Personas afectadas', 'integer'),
    'maxStateCount': ('Largest number of affected residents of one state', 'Mayor número de residentes afectados de un estado',
                      'integer'),
    'phiTypes': ('Types of PHI involved', 'Tipos de PHI involucrada', 'text'),
    'reportedByBa': ('Reported by a business associate', 'Notificado por un socio comercial', 'boolean'),
    'riskFactors': ('Four-factor risk assessment (164.402)', 'Evaluación de riesgos de cuatro factores (164.402)', 'text'),
    'lawEnforcementDelay': ('Law-enforcement delay requested', 'Retraso solicitado por las fuerzas del orden', 'boolean'),
    'leDelayDays': ('Delay in days stated by law enforcement', 'Días de retraso indicados por las fuerzas del orden', 'integer'),
    'substituteNotice': ('Contact information out of date for 10 or more', 'Información de contacto desactualizada para 10 o más',
                         'boolean'),
    'annualLogYearEnd': ('End of the calendar year of discovery (31 Dec)', 'Fin del año calendario del descubrimiento (31 dic)',
                         'date'),
    'baName': ('Business associate', 'Socio comercial', 'string'),
    'phiScope': ('PHI created, received, maintained or transmitted', 'PHI creada, recibida, mantenida o transmitida', 'text'),
    'baaTerms': ('Required BAA terms present (164.504(e))', 'Términos obligatorios del BAA presentes (164.504(e))', 'boolean'),
    'subcontractors': ('Subcontractors with flow-down BAAs', 'Subcontratistas con BAA derivados', 'text'),
    'securityReview': ('Security assessment of the business associate', 'Evaluación de seguridad del socio comercial', 'text'),
    'baaTermEnd': ('BAA end date', 'Fecha de fin del BAA', 'date'),
    'cureNotes': ('Material breach: cure steps or termination', 'Incumplimiento material: medidas de subsanación o terminación',
                  'text'),
    'phiDisposition': ('PHI returned or destroyed (certificate)', 'PHI devuelta o destruida (certificado)', 'attachments'),
    # M-133 payer APIs (CMS-0057-F, decision 107)
    'previousPayerId': ('Previous payer ID', 'Identificador del pagador anterior', 'string'),
    'previousMemberId': ('Member ID at the previous payer', 'Número de afiliado en el pagador anterior', 'string'),
    'p2pOptIn': ('Patient opted in to the payer-to-payer exchange', 'El paciente aceptó el intercambio entre pagadores',
                 'boolean'),
    'requestingPayerId': ('Requesting payer ID', 'Identificador del pagador solicitante', 'string'),
    'optInAttested': ('Requesting payer attests the patient opted in', 'El pagador solicitante certifica la aceptación del '
                      'paciente', 'boolean'),
    'interopNotes': ('Interoperability follow-up notes', 'Notas de seguimiento de interoperabilidad', 'text'),
    # M-133 Patient Access API (US-HLT-061, decision 174)
    'itemCategory': ('Item category (ITEM_OR_SERVICE, DRUG)', 'Categoría del artículo (ITEM_OR_SERVICE, DRUG)', 'select'),
    'paStatus': ('Prior authorization status (as the payer recorded it)',
                 'Estado de la autorización previa (según el registro del pagador)', 'string'),
    'reportingPeriod': ('Reporting period', 'Período del informe', 'string'),
    'metricsReview': ('Review of the usage metrics report', 'Revisión del informe de métricas de uso', 'text'),
}

O = {
    'approve': ('Approve / sanction', 'Aprobar'), 'reject': ('Reject', 'Rechazar'),
    'retry': ('Retry', 'Reintentar'), 'selfPay': ('Self-pay / financial assistance', 'Pago propio / asistencia financiera'),
    'otherCoverage': ('Other coverage found', 'Otra cobertura encontrada'),
    'appeal': ('Appeal', 'Apelar'), 'peerToPeer': ('Peer-to-peer review', 'Revisión entre pares'),
    'acceptDenial': ('Accept the denial', 'Aceptar la denegación'),
    'overturned': ('Overturned (approved)', 'Revocada (aprobada)'), 'upheld': ('Upheld', 'Confirmada'),
    'resubmit': ('Correct and resubmit', 'Corregir y volver a enviar'), 'writeOff': ('Write off', 'Cancelar el saldo'),
    'verified': ('Identity and authority verified', 'Identidad y autoridad verificadas'),
    'notVerified': ('Ask for proof', 'Solicitar prueba'),
    'fulfil': ('Provide access', 'Proporcionar acceso'), 'extend': ('Extend by 30 days', 'Prorrogar 30 días'),
    'deny': ('Deny (with basis)', 'Denegar (con fundamento)'),
    'breach': ('Breach: notify', 'Violación: notificar'),
    'lowProbability': ('Low probability of compromise (documented)', 'Baja probabilidad de compromiso (documentada)'),
    'needsPlan': ('Discharge plan needed', 'Se necesita un plan de alta'), 'noPlan': ('No plan needed', 'No se necesita plan'),
    'renew': ('Renew', 'Renovar'), 'terminate': ('Terminate', 'Terminar'),
    'cured': ('Breach cured', 'Incumplimiento subsanado'),
    'closeUnanswered': ('Close: no data received', 'Cerrar: no se recibieron datos'),
    'submitReport': ('Submit the report', 'Enviar el informe'),
    'recompile': ('Compile the metrics again', 'Compilar las métricas de nuevo'),
}

LOCALES['es'] = ({k: v[1] for k, v in F.items()}, {k: v[1] for k, v in O.items()}, 'Detalles del caso')

for _k, (_en, _es, _t) in F.items():
    if _k not in forms.L:
        forms.L[_k] = (_en, _en, _t)   # the Hindi slot is unused by second='es' packs
for _k, (_en, _es) in O.items():
    if _k not in forms.OPT:
        forms.OPT[_k] = (_en, _en)

ES_SUMMARY = 'Detalles del caso'


def upack(slug, en, es, desc, ids, connectors):
    return Pack(f'sec.ushealth.{slug}', f'sec-ushealth-{slug}', en, es, 'Healthcare (USA)', desc, list(ids),
                connectors=list(connectors), depends=['sangam.core'], second='es', currency='USD')


def sform(p, s, sid, name, key, title, fields):
    st = s.start(sid, name, form=key)
    p.forms.append(dict(key=key, name=title, decision=None, fields=list(fields), doc=None))
    return st


def useeds(pk, office, seats, prefix):
    pk.seeds(offices=[{'id': office, 'parent': ''}],
             seats=[{'id': sid, 'role': role, 'office': office, 'reportsTo': up, 'name': {'en': en, 'es': es}}
                    for sid, role, up, en, es in seats],
             holdings=[{'seat': sid, 'person': prefix + '.' + sid.lower(), 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}
                       for sid, _, _, _, _ in seats],
             roles=[{'code': role, 'name': {'en': en, 'es': es}} for _, role, _, en, es in seats])


# ============================================================================================ eligibility (270/271)
def eligibility():
    pk = upack('eligibility', 'Eligibility and benefits verification', 'Verificación de elegibilidad y beneficios',
               'Registration with NPI check, real-time 270/271 eligibility through the clearinghouse (CAQH CORE 20 s), '
               'benefit review and estimated patient responsibility, coverage resolution (other coverage, self-pay, '
               'financial assistance). Emergency arrivals get the medical screening examination first: no payment or '
               'insurance inquiry may delay screening or stabilisation (EMTALA).',
               ['US-HLT-085', 'US-HLT-092', 'US-HLT-095', 'US-HLT-096', 'US-HLT-120', 'US-HLT-121'] + SIDE,
               [('npi.registry', 'a', '', 'NPPES NPI lookup'), ('x12.eligibility', 'a', '', '270/271 eligibility')])
    p = pk.proc('sangam-sec-ushealth-eligibility', 'Eligibility and benefits', '',
                start={'materialFields': 'patientName,memberId,payerId'})
    s = p.root
    st = sform(p, s, 'el_start', 'Patient registered', 'sec-ushealth-eligibility/register',
               'Patient registration | Registro del paciente',
               ['patientName*', 'memberId*', 'payerId*', 'providerNpi*', 'serviceTypes', 'visitType*'])
    d = s.setv('el_defaults', 'Configuration',
               defaults='patientName=@customerId;memberId=@customerId;payerId=SIMPAYER;providerNpi=1234567893;'
                        'serviceTypes=30;visitType=SCHEDULED;'
                        'edSeat=ED-PHYSICIAN;regSeat=PATIENT-ACCESS;finSeat=FINANCIAL-COUNSELOR')
    r = s.rule('el_cfg', 'Eligibility policy (DMN)', 'sec-ushealth-eligibility-config')
    s.seq(st, d, r)
    eg = s.xgw('el_erGw', 'Emergency arrival?')
    s.flow(r, eg)
    mse = s.user('el_mse', 'Medical screening examination and stabilisation (no payment inquiry first) | '
                 'Examen médico de detección y estabilización (sin consulta de pago previa)', '${edSeat}',
                 form='sec-ushealth-eligibility/mse', decision=None, fields=['screeningNotes*', 'stabilised*'],
                 priority='95')
    s.flow(eg, mse, "visitType == 'EMERGENCY'", 'emergency (EMTALA)')
    npi = s.conn('el_npi', 'Check provider NPI (NPPES)', 'npi.registry', 'lookup', 'query', 'providerNpi', 'npi')
    s.flow(eg, npi, None, 'scheduled', default=True)
    s.flow(mse, npi)
    # SL-115: coverage is verified only on a completed 271 that says ACTIVE (cleared before every inquiry; no default
    # stands in); a failed or empty answer goes to the financial counselor
    q271e, q271 = checked_query(s, 'el_270', 'Eligibility inquiry 270 / response 271', 'x12.eligibility', 'eligibility',
                                'memberId,payerId,providerNpi,serviceTypes,patientName', 'elig', ['eligEligibility'])
    # SL-115 (integrator decision): the NPI lookup is gated. No npi.registry adapter defines result keys yet, so the
    # gate is the answer itself: an NPI lookup that did not complete goes to patient access to correct the NPI, then
    # re-check; the 270 is never sent on an unconfirmed provider
    ng = s.xgw('el_npiGw', 'Provider NPI confirmed?')
    s.flow(npi, ng)
    s.flow(ng, unconfirmed_hold(p, s, 'el_npiUnconfirmed', 'Provider NPI not confirmed: correct it | '
                                'NPI del proveedor no confirmado: corregirlo', '${regSeat}', npi,
                                fields=('providerNpi*', 'remarks*')), "npiStatus != 'COMPLETED'", 'NPI not confirmed')
    s.flow(ng, q271e, None, 'confirmed', default=True)
    g = s.xgw('el_resultGw', 'Coverage?', policy='priority')  # ADR-D-25: conditions can both be true; explicit precedence
    s.flow(q271, g)
    rv = s.user('el_review', 'Review benefits and estimate patient responsibility | '
                'Revisar beneficios y estimar la responsabilidad del paciente', '${regSeat}',
                form='sec-ushealth-eligibility/review', decision=None,
                fields=['benefitSummary', 'estimatedPatientShare*', 'remarks'])
    elig_unconfirmed, elig_active = check_conditions([('elig', ['eligEligibility'])], "eligEligibility == 'ACTIVE'")
    s.flow(g, rv, elig_active, 'active', priority=1)
    s.flow(rv, end_ok(p, s, 'el_end', 'Coverage verified'))
    w = s.catch_timer('el_wait', 'Payer unavailable: retry later', duration='${eligRetryAfter}')
    s.flow(g, w, "eligStatus == 'UNKNOWN' || eligStatus == 'PENDING'", 'no answer', priority=2)
    s.flow(w, q271e)
    fc = s.user('el_resolve', 'Resolve coverage: correct data, other coverage, self-pay | '
                'Resolver la cobertura: corregir datos, otra cobertura, pago propio', '${finSeat}',
                form='sec-ushealth-eligibility/resolve', decision=('coverageDecision', ['retry', 'otherCoverage', 'selfPay']),
                fields=['memberId*', 'payerId', 'coverageResolution*'])
    s.flow(g, fc, elig_unconfirmed, 'coverage not confirmed', priority=3)
    s.flow(g, fc, None, 'inactive / rejected (AAA)', default=True)
    rg = s.xgw('el_resolveGw')
    s.flow(fc, rg)
    s.flow(rg, q271e, "coverageDecision != 'selfPay'", 're-check')
    s.flow(rg, end_ok(p, s, 'el_endSelf', 'Self-pay: good-faith estimate due', outcome='SELF_PAY'), None, 'self-pay',
           default=True)
    s.side_exits('el')
    pk.dmn('sec-ushealth-eligibility-config', 'Eligibility policy (SYNTHETIC)', 'FIRST',
           [('Visit type', 'visitType', 'string')],
           [('eligRetryAfter', 'Retry after (payer unavailable)', 'string')],
           [([q('EMERGENCY')], [q('PT5M')], 'never blocks care'), (['-'], [q('PT15M')], 'scheduled')])
    useeds(pk, 'HOSPITAL', [
        ('ED-PHYSICIAN', 'EDMD', 'REV-CYCLE-MGR', 'Emergency physician', 'Médico de urgencias'),
        ('PATIENT-ACCESS', 'ACCESS', 'REV-CYCLE-MGR', 'Patient access', 'Admisión de pacientes'),
        ('FINANCIAL-COUNSELOR', 'FINCOUNSEL', 'REV-CYCLE-MGR', 'Financial counselor', 'Asesor financiero'),
        ('REV-CYCLE-MGR', 'RCM', None, 'Revenue cycle manager', 'Gerente del ciclo de ingresos')], 'uel')


# ============================================================================================ prior authorisation
def prior_auth():
    pk = upack('prior-auth', 'Prior authorization (CRD, DTR, PAS) with CMS-0057-F clocks',
               'Autorización previa (CRD, DTR, PAS) con plazos de CMS-0057-F',
               'Coverage requirements discovery at order-sign (CDS Hooks CRD), documentation templates (DTR), '
               'submission through the Da Vinci PAS API (or X12 278), decision clocks by payer program from DMN '
               '(MA / Medicaid / CHIP: 72 hours expedited, 7 calendar days standard from 2026; QHP and commercial '
               'by their own rules), pended answers through a Subscription, specific denial reason, peer-to-peer and '
               'appeal, overdue-decision escalation and the public metrics event.',
               ['US-HLT-055', 'US-HLT-056', 'US-HLT-057', 'US-HLT-058', 'US-HLT-059', 'US-HLT-060', 'US-HLT-065',
                'US-HLT-070', 'US-HLT-071', 'US-HLT-072', 'US-HLT-073', 'US-HLT-087', 'US-HLT-104'] + SIDE,
               [('cdshooks.crd', 'a', '', 'CRD order-sign'), ('davinci.dtr', 'a', '', 'DTR questionnaire package'),
                ('davinci.pas', 'b', '', 'PAS Claim/$submit'), ('payer.appeal', 'b', '', 'Appeal / reconsideration')])
    p = pk.proc('sangam-sec-ushealth-prior-auth', 'Prior authorization', '',
                start={'materialFields': 'patientName,memberId,procedureCode,priority'})
    s = p.root
    st = sform(p, s, 'pa_start', 'Order signed', 'sec-ushealth-prior-auth/order', 'Service order | Orden de servicio',
               ['patientName*', 'memberId*', 'payerId*', 'payerProgram*', 'providerNpi*', 'procedureCode*',
                'diagnosisCode*', 'priority*'])
    d = s.setv('pa_defaults', 'Configuration',
               defaults='patientName=@customerId;memberId=@customerId;payerId=SIMPAYER;payerProgram=MA;'
                        'providerNpi=1234567893;procedureCode=72148;diagnosisCode=M54.5;priority=STANDARD;'
                        'pasReasonCode=null;pasPreAuthRef=null;'
                        'clinicianSeat=ORDERING-CLINICIAN;authSeat=AUTH-SPECIALIST;umSeat=UM-LEAD')
    # SL-115: only a completed CRD answer that says "not required" skips prior authorization (cleared before the call;
    # no default stands in); an unconfirmed answer means authorization is needed
    crde, crd = checked_query(s, 'pa_crd', 'Coverage requirements discovery (CDS Hooks order-sign)', 'cdshooks.crd',
                              'call', 'memberId,payerId,procedureCode,diagnosisCode', 'crd', ['crdPaRequired'])
    s.seq(st, d, crde)
    g = s.xgw('pa_needGw', 'Prior authorization needed?')
    s.flow(crd, g)
    crd_unconfirmed, crd_not_required = check_conditions([('crd', ['crdPaRequired'])], '!crdPaRequired')
    s.flow(g, end_ok(p, s, 'pa_endNoAuth', 'No prior authorization required', outcome='NOT_REQUIRED'),
           crd_not_required, 'no-auth')
    dtr = s.conn('pa_dtr', 'Documentation templates and rules (DTR package)', 'davinci.dtr', 'questionnairePackage',
                 'query', 'memberId,procedureCode', 'dtr')
    s.flow(g, dtr, crd_unconfirmed, 'not confirmed: authorization needed')
    s.flow(g, dtr, None, 'auth-needed', default=True)
    doc = s.user('pa_doc', 'Complete the documentation questionnaire | Completar el cuestionario de documentación',
                 '${clinicianSeat}', form='sec-ushealth-prior-auth/documentation', decision=None,
                 fields=['questionnaireResponse*', 'documents'])
    clk = s.rule('pa_clocks', 'Decision timeframe by payer program and priority (DMN)', 'sec-ushealth-pa-clocks')
    dl = clock(s, 'pa_deadline', 'paDecision', '${paDecisionSpec}')
    # SL-115: the payer decision comes only from the PAS answer or its update (cleared before the submit; no default
    # stands in); no decision yet waits like a pended one (the update or the deadline), it never authorizes
    sube, sub = checked_query(s, 'pa_submit', 'Submit prior authorization (PAS Claim/$submit or X12 278)', 'davinci.pas',
                              'submit', 'memberId,payerId,providerNpi,procedureCode,diagnosisCode,priority,'
                              'questionnaireResponse', 'pas', ['pasDecision'], mode='submit', step='pas-submit')
    s.seq(dtr, doc, clk, dl, sube)
    dg = s.xgw('pa_decGw', 'Payer decision?')
    s.flow(sub, dg)
    s.flow(dg, end_ok(p, s, 'pa_endApproved', 'Authorized', outcome='APPROVED', data=['pasPreAuthRef']),
           "pasDecision == 'APPROVED' || pasDecision == 'MODIFIED'", 'approved')
    w = s.egw('pa_wait', 'Pended: update or deadline')
    s.flow(dg, w, 'pasDecision == null', 'decision not confirmed')
    s.flow(dg, w, "pasDecision == 'PENDED' || pasDecision == 'UNKNOWN'", 'pended')
    upd = s.catch_msg('pa_update', 'Decision notification (Subscription)', 'sangam.ushealth.pasUpdate')
    late = s.catch_timer('pa_late', 'Decision timeframe passed', date='${paDecisionDueAt}')
    s.flow(w, upd)
    s.flow(w, late)
    s.flow(upd, dg)
    esc = s.user('pa_escalate', 'Payer decision overdue: escalate and record for metrics | '
                 'Decisión del pagador vencida: escalar y registrar para métricas', '${umSeat}',
                 form='sec-ushealth-prior-auth/overdue', decision=None, fields=['escalationNotes*'])
    s.flow(late, esc)
    s.flow(esc, w)
    rs = s.rule('pa_reason', 'Denial reason routing (DMN)', 'sec-ushealth-pa-denial-reasons')
    s.flow(dg, rs, None, 'denied', default=True)
    dn = s.user('pa_denied', 'Denied with a specific reason: peer-to-peer, appeal or accept | '
                'Denegada con un motivo específico: revisión entre pares, apelación o aceptación', '${authSeat}',
                form='sec-ushealth-prior-auth/denied', decision=('denialDecision', ['peerToPeer', 'appeal', 'acceptDenial']),
                fields=['denialReason', 'remarks'])
    s.flow(rs, dn)
    ng = s.xgw('pa_denGw')
    s.flow(dn, ng)
    pp = s.user('pa_p2p', 'Peer-to-peer review with the payer medical director | '
                'Revisión entre pares con el director médico del pagador', '${clinicianSeat}',
                form='sec-ushealth-prior-auth/p2p', decision=('p2pOutcome', ['overturned', 'upheld']),
                fields=['peerToPeerNotes*'])
    s.flow(ng, pp, "denialDecision == 'peerToPeer'", 'peer-to-peer')
    pg = s.xgw('pa_p2pGw')
    s.flow(pp, pg)
    s.flow(pg, end_ok(p, s, 'pa_endP2p', 'Authorized after peer-to-peer', outcome='APPROVED'),
           "p2pOutcome == 'overturned'", 'overturned')
    ap = s.user('pa_appeal', 'Prepare and file the appeal (reconsideration) | Preparar y presentar la apelación',
                '${authSeat}', form='sec-ushealth-prior-auth/appeal', decision=None,
                fields=['appealGrounds*', 'documents'])
    s.flow(pg, ap, None, 'upheld: appeal', default=True)
    s.flow(ng, ap, "denialDecision == 'appeal'", 'appeal')
    s.flow(ng, s.end('pa_endDenied', 'Denial accepted', listeners=[p.item_listener('REJECTED', reason_var='pasReasonCode')]),
           None, 'accept', default=True)
    afile = s.conn('pa_appealFile', 'File the appeal with the payer', 'payer.appeal', 'file', 'submit',
                   'memberId,payerId,appealGrounds,pasPreAuthRef', 'appeal', step='appeal')
    adl = clock(s, 'pa_appealDl', 'paAppeal', '${paAppealSpec}')
    s.seq(ap, afile, adl)
    aw = s.egw('pa_appealWait', 'Appeal decision or deadline')
    s.flow(adl, aw)
    ad = s.catch_msg('pa_appealDecision', 'Appeal decided', 'sangam.ushealth.appealDecision')
    at = s.catch_timer('pa_appealLate', 'Appeal timeframe passed', date='${paAppealDueAt}')
    s.flow(aw, ad)
    s.flow(aw, at)
    rec = s.user('pa_appealRecord', 'Record appeal outcome (external review if upheld) | '
                 'Registrar el resultado de la apelación (revisión externa si se confirma)', '${authSeat}',
                 form='sec-ushealth-prior-auth/appeal-outcome', decision=None, fields=['appealOutcome*', 'remarks'])
    s.flow(ad, rec)
    s.flow(at, rec)
    s.flow(rec, end_ok(p, s, 'pa_endAppeal', 'Appeal closed', outcome='APPEAL_CLOSED'))
    s.side_exits('pa')
    pk.dmn('sec-ushealth-pa-clocks', 'Prior authorization decision timeframes (CMS-0057-F; tenant overridable)', 'FIRST',
           [('Payer program', 'payerProgram', 'string'), ('Priority', 'priority', 'string')],
           [('paDecisionSpec', 'Decision due within', 'string'), ('paAppealSpec', 'Appeal decision due within', 'string')],
           [([q('QHP_FFE'), q('EXPEDITED')], [q('72H'), q('72H')], '45 CFR 147.136 urgent; 0057-F clocks do not apply'),
            ([q('QHP_FFE'), '-'], [q('15D'), q('30D')], '29 CFR 2560.503-1 pre-service (verify plan)'),
            ([q('COMMERCIAL'), q('EXPEDITED')], [q('72H'), q('72H')], 'ACA urgent'),
            ([q('COMMERCIAL'), '-'], [q('15D'), q('30D')], 'ACA pre-service'),
            (['-', q('EXPEDITED')], [q('72H'), q('72H')], 'MA / Medicaid / CHIP expedited (42 CFR 422.572, 438.210)'),
            (['-', '-'], [q('7D'), q('30D')], 'MA / Medicaid / CHIP standard 7 calendar days from 2026 (422.568)')],
           doc='US-HLT-055/056/057, US-HLT-103. Override per tenant or state law.')
    pk.dmn('sec-ushealth-pa-denial-reasons', 'Denial reason routing (SYNTHETIC mapping of payer reason codes)', 'FIRST',
           [('Reason code', 'pasReasonCode', 'string')],
           [('denialReason', 'Specific denial reason', 'string'), ('denialCategory', 'Category', 'string')],
           [([q('MED-NEC')], [q('Not medically necessary per the payer criteria'), q('CLINICAL')], ''),
            ([q('DOC')], [q('Documentation missing or incomplete'), q('DOCUMENTATION')], ''),
            ([q('NOT-COVERED')], [q('Service not a covered benefit'), q('BENEFIT')], ''),
            ([q('OON')], [q('Out-of-network provider'), q('NETWORK')], ''),
            (['-'], [q('Payer reason code (see ClaimResponse)'), q('OTHER')], 'payer must give a specific reason')])
    useeds(pk, 'PROVIDER', [
        ('ORDERING-CLINICIAN', 'CLINICIAN', 'UM-LEAD', 'Ordering clinician', 'Médico solicitante'),
        ('AUTH-SPECIALIST', 'AUTHSPEC', 'UM-LEAD', 'Prior authorization specialist', 'Especialista en autorizaciones'),
        ('UM-LEAD', 'UMLEAD', None, 'Utilization management lead', 'Líder de gestión de utilización')], 'upa')


# ============================================================================================ claim to cash
def claim():
    pk = upack('claim', 'Claim submission, status, remittance posting, denial and appeal',
               'Envío de reclamaciones, estado, contabilización de remesas, denegación y apelación',
               'Coding and scrub, timely-filing clock from DMN, 837P / 837I through the clearinghouse with the 999 '
               'acknowledgment, front-end rejection fix, 276/277 status follow-up, 835 remittance parsed and posted '
               '(CARC / RARC, patient responsibility, balancing), denial routing by adjustment code, corrected claim '
               '(frequency 7), appeal with its filing window, write-off with segregation of duties.',
               ['US-HLT-086', 'US-HLT-088', 'US-HLT-089', 'US-HLT-090', 'US-HLT-093', 'US-HLT-095', 'US-HLT-100',
                'US-HLT-101', 'US-HLT-102', 'US-HLT-103', 'US-HLT-104', 'US-HLT-105', 'US-HLT-106'] + SIDE,
               [('x12.claim', 'b', '', '837 submission'), ('x12.claimStatus', 'a', '', '276/277 status'),
                ('x12.remit', 'a', '', '835 decode'), ('payer.appeal', 'b', '', 'Appeal / redetermination'),
                ('ledger.post', 'a', '', 'Payment posting')])
    # NPI: no LUHN here, its check digit is computed with the 80840 prefix (validated by the npi.registry connector)
    pk.identifier_types = [('US', [
        {'typeKey': 'NPI', 'label': 'National Provider Identifier', 'pattern': '[0-9]{10}', 'normalise': ['DIGITS_ONLY'],
         'unique': True, 'matchWeight': 12},
        {'typeKey': 'EIN', 'label': 'Employer Identification Number', 'pattern': '[0-9]{9}', 'normalise': ['DIGITS_ONLY'],
         'unique': True, 'matchWeight': 12, 'appliesTo': 'ORGANISATION'},
        {'typeKey': 'MEMBER_ID', 'label': 'Health plan member id', 'normalise': ['UPPER', 'STRIP_SPACES'], 'sensitive': True,
         'matchWeight': 8, 'appliesTo': 'PERSON'},
        {'typeKey': 'MRN', 'label': 'Medical record number', 'normalise': ['UPPER', 'STRIP_SPACES'], 'sensitive': True,
         'matchWeight': 8, 'appliesTo': 'PERSON'}])]
    p = pk.proc('sangam-sec-ushealth-claim', 'Claim to cash', '',
                start={'materialFields': 'patientName,patientControlNumber,totalCharge'})
    s = p.root
    st = sform(p, s, 'cl_start', 'Encounter ready to bill', 'sec-ushealth-claim/encounter', 'Encounter | Encuentro',
               ['patientName*', 'memberId*', 'payerId*', 'payerProgram*', 'providerNpi*', 'patientControlNumber*',
                'totalCharge*', 'serviceDate*', 'claimType*', 'priorAuthNumber'])
    d = s.setv('cl_defaults', 'Configuration',
               defaults='patientName=@customerId;memberId=@customerId;payerId=SIMPAYER;payerProgram=COMMERCIAL;'
                        'providerNpi=1234567893;patientControlNumber=@customerId;totalCharge=100;claimType=837P;'
                        'frequency=1;coderSeat=CODER;billerSeat=BILLER;denialSeat=DENIAL-ANALYST;rcmSeat=REV-CYCLE-MGR-BILLING;'
                        'ledgerId=RECEIVABLES;line=CLAIM;period=OPEN;'
                        'receivableRaised=null;correctionNo=0;eraTraceNumber=no-trace')
    cfg = s.rule('cl_cfg', 'Timely filing, follow-up and appeal windows (DMN)', 'sec-ushealth-claim-clocks')
    tf = clock(s, 'cl_filing', 'filing', '${filingSpec}')
    s.seq(st, d, cfg, tf)
    scrub = s.user('cl_scrub', 'Code and scrub the claim | Codificar y depurar la reclamación', '${coderSeat}',
                   form='sec-ushealth-claim/scrub', decision=None, fields=['codingNotes*', 'priorAuthNumber', 'totalCharge'])
    s.flow(tf, scrub)
    # SL-115: the claim counts as accepted only on a completed submit that says so (cleared before every submit; no
    # default stands in); an unconfirmed acknowledgment goes to the biller like a front-end rejection
    sube, sub = checked_query(s, 'cl_submit', 'Submit claim 837P / 837I (999 acknowledgment)', 'x12.claim', 'submitClaim',
                              'memberId,payerId,providerNpi,patientControlNumber,totalCharge,serviceDate,claimType,'
                              'priorAuthNumber,frequency', 'clm', ['clmAccepted'], mode='submit',
                              # a corrected claim (frequency 7, cl_corrected) is a new submission with its own
                              # operationId, as its receivable adjustment below: the first claim's id with another
                              # payload is refused by the payer side (R41, IDEMPOTENCY_PAYLOAD_MISMATCH)
                              step='claim-submit:${correctionNo}')
    s.flow(scrub, sube)
    ag = s.xgw('cl_ackGw', 'Accepted at the front end?')
    s.flow(sub, ag)
    fix = s.user('cl_fix', 'Fix the front-end rejection (999 / 277CA) | Corregir el rechazo inicial (999 / 277CA)',
                 '${billerSeat}', form='sec-ushealth-claim/fix', decision=None, fields=['rejectionFix*'])
    clm_unconfirmed, clm_rejected = check_conditions([('clm', ['clmAccepted'])], '!clmAccepted')
    s.flow(ag, fix, clm_unconfirmed, 'acknowledgment not confirmed')
    s.flow(ag, fix, clm_rejected, 'rejected')
    s.flow(fix, scrub)
    w = s.egw('cl_wait', 'Remittance or follow-up')
    ra = s.setv('cl_receivableCfg', 'Receivable for the claim', values='holder=@patientControlNumber;amount=@totalCharge')
    al = s.conn('cl_receivable', 'Raise the claim receivable', 'ledger.allocate', 'allocate', 'submit',
                'ledgerId,line,period,holder,amount', 'recv', step='receivable')
    fg = s.xgw('cl_firstGw', 'Receivable raised already?')
    s.flow(ag, fg, None, 'accepted', default=True)
    s.flow(fg, ra, 'receivableRaised == null', 'first submission')
    s.flow(ra, al)
    dl = s.expr('cl_delta', 'Change in the charge (corrected claim)',
                "${execution.setVariable('receivableDelta', totalCharge - receivableRaised)}")
    s.flow(fg, dl, None, 'corrected claim', default=True)
    dg2 = s.xgw('cl_deltaGw', 'Charge changed?')
    s.flow(dl, dg2)
    s.flow(dg2, w, 'receivableDelta == 0', 'no')
    da = s.setv('cl_adjustCfg', 'Adjustment leg', values='holder=@patientControlNumber;amount=@receivableDelta')
    s.flow(dg2, da, None, 'yes', default=True)
    aj = s.conn('cl_adjust', 'Adjust the claim receivable', 'ledger.adjust', 'adjust', 'submit',
                'ledgerId,line,period,holder,amount', 'recv', step='receivable-adjust:${correctionNo}')
    s.flow(da, aj)
    rg0 = s.xgw('cl_recvGw', 'Receivable posted?')
    s.flow(al, rg0)
    s.flow(aj, rg0)
    rx = s.user('cl_recvException', 'Receivable not posted: resolve | Cuenta por cobrar no registrada: resolver', '${billerSeat}',
                form='sec-ushealth-claim/receivable-exception', decision=None, fields=['remarks*'])
    s.flow(rg0, rx, "recvStatus == 'FAILED'", 'refused')
    s.flow(rx, w)
    rok = s.setv('cl_recvOk', 'Receivable now stands at the charge', values='receivableRaised=@totalCharge')
    s.flow(rg0, rok, None, 'posted', default=True)
    s.flow(rok, w)
    remit = s.catch_msg('cl_remit', 'Remittance received (835)', 'sangam.ushealth.remit')
    fu = s.catch_timer('cl_followUp', 'No remittance yet', duration='${statusFollowUp}')
    s.flow(w, remit)
    s.flow(w, fu)
    cs = s.conn('cl_status', 'Claim status 276 / 277', 'x12.claimStatus', 'claimStatus', 'query',
                'memberId,payerId,providerNpi,patientControlNumber,totalCharge,serviceDate', 'cs')
    s.flow(fu, cs)
    s.flow(cs, w)
    # SL-115: the decode and the posting fail closed (each cleared before its call; no default stands in): a remittance
    # that is not confirmed decoded is never posted, an unconfirmed posting is never "clean"; both go to the US-HLT-106
    # work queue, and only a confirmed claim status reaches "Paid and posted"
    erae, era = checked_query(s, 'cl_parse', 'Decode this claim of the 835', 'x12.remit', 'parse',
                              'x12,eraTraceNumber,patientControlNumber', 'era',
                              ['eraClaimStatus', 'eraBalanced', 'eraPatientResponsibility'])
    poste, post = checked_query(s, 'cl_post', 'Post payment and adjustments', 'ledger.post', 'post',
                                'patientControlNumber,eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,'
                                'eraTraceNumber,ledgerId,line,period', 'post', ['postExceptionCount'], mode='submit',
                                step='posting:${eraTraceNumber}')
    s.seq(remit, erae)
    eg = s.xgw('cl_eraGw', 'Remittance decoded?')
    s.flow(era, eg)
    s.flow(eg, poste, None, 'decoded', default=True)
    pg = s.xgw('cl_paidGw', 'Paid or denied?')
    qg = s.xgw('cl_postGw', 'Posted cleanly?')
    s.flow(post, qg)
    pq = s.user('cl_postException', 'Resolve the posting exception | Resolver la excepción de contabilización', '${billerSeat}',
                form='sec-ushealth-claim/post-exception', decision=None, fields=['remitSummary', 'remarks*'])
    era_unconfirmed, _ = check_conditions([('era', ['eraClaimStatus', 'eraBalanced', 'eraPatientResponsibility'])])
    s.flow(eg, pq, era_unconfirmed, 'decode not confirmed: not posted')
    post_unconfirmed, _ = check_conditions([('post', ['postExceptionCount'])])
    s.flow(qg, pq, f"{post_unconfirmed} || postExceptionCount > 0 || !eraBalanced || eraClaimStatus == 'NOT_IN_FILE'",
           'US-HLT-106 work queue')
    s.flow(qg, pg, None, 'clean', default=True)
    s.flow(pq, pg)
    bal = s.user('cl_patientBill', 'Bill the patient balance | Facturar el saldo al paciente', '${billerSeat}',
                 form='sec-ushealth-claim/patient-bill', decision=None, fields=['patientBalance*', 'remarks'])
    g2, m2 = maybe(s, 'cl_balOpt', 'eraPatientResponsibility > 0', bal, bal, 'Patient balance?')
    s.flow(pg, g2, "eraClaimStatus != null && eraClaimStatus != 'DENIED'", 'paid')
    s.flow(m2, end_ok(p, s, 'cl_end', 'Paid and posted', outcome='PAID'))
    rt = s.rule('cl_denialRoute', 'Denial routing by adjustment group and CARC (DMN)', 'sec-ushealth-claim-denials')
    s.flow(pg, rt, None, 'denied', default=True)
    wk = s.user('cl_denial', 'Work the denial: correct, appeal or write off | Gestionar la denegación', '${denialSeat}',
                form='sec-ushealth-claim/denial', decision=('denialAction', ['resubmit', 'appeal', 'writeOff']),
                fields=['remitSummary', 'adjustmentCodes', 'remarks'])
    s.flow(pg, wk, 'eraClaimStatus == null', 'claim status not confirmed')
    s.flow(rt, wk)
    dg = s.xgw('cl_denialGw')
    s.flow(wk, dg)
    corr = s.setv('cl_corrected', 'Corrected claim (frequency 7)', values='frequency=7')
    cn = s.expr('cl_correctionNo', 'Count the correction', "${execution.setVariable('correctionNo', correctionNo + 1)}")
    s.flow(dg, corr, "denialAction == 'resubmit'", 'correct')
    s.seq(corr, cn, scrub)
    adl = clock(s, 'cl_appealWindow', 'appealFiling', '${appealFilingSpec}')
    ap = s.user('cl_appeal', 'File the appeal / redetermination | Presentar la apelación / redeterminación',
                '${denialSeat}', form='sec-ushealth-claim/appeal', decision=None, fields=['appealGrounds*', 'documents'],
                due='${appealFilingDueAt}')
    afile = s.conn('cl_appealFile', 'Send the appeal to the payer', 'payer.appeal', 'file', 'submit',
                   'patientControlNumber,appealGrounds', 'appeal', step='claim-appeal')
    dec = clock(s, 'cl_appealDecisionClock', 'appealDecision', '${appealDecisionSpec}')
    s.flow(dg, adl, "denialAction == 'appeal'", 'appeal')
    s.seq(adl, ap, afile, dec)
    aw = s.egw('cl_appealWait', 'Appeal decision')
    s.flow(dec, aw)
    adm = s.catch_msg('cl_appealDecided', 'Appeal decided', 'sangam.ushealth.appealDecision')
    adt = s.catch_timer('cl_appealLate', 'Appeal decision overdue', date='${appealDecisionDueAt}')
    s.flow(aw, adm)
    s.flow(aw, adt)
    ar = s.user('cl_appealOutcome', 'Record the appeal outcome | Registrar el resultado de la apelación',
                '${denialSeat}', form='sec-ushealth-claim/appeal-outcome', decision=None,
                fields=['appealOutcome*', 'remarks'])
    s.flow(adm, ar)
    s.flow(adt, ar)
    aog = s.xgw('cl_appealOutGw', 'Overturned?')
    s.flow(ar, aog)
    s.flow(aog, w, "appealOutcome == 'overturned'", 'overturned: await payment')
    wo = s.user('cl_writeOff', 'Approve the write-off (segregation of duties) | Aprobar la cancelación (separación de funciones)',
                '${rcmSeat}', form='sec-ushealth-claim/write-off', decision=('writeOffDecision', ['approve', 'reject']),
                fields=['writeOffAmount*', 'remarks*'], sod=True)
    s.flow(aog, wo, None, 'upheld', default=True)
    s.flow(dg, wo, None, 'write off', default=True)
    wg = s.xgw('cl_woGw')
    s.flow(wo, wg)
    s.flow(wg, wk, "writeOffDecision == 'reject'", 'rework')
    s.flow(wg, end_ok(p, s, 'cl_endWo', 'Written off', outcome='WRITTEN_OFF'), None, 'approved', default=True)
    s.side_exits('cl')
    pk.dmn('sec-ushealth-claim-clocks', 'Claim timelines by payer program (tenant overridable)', 'FIRST',
           [('Payer program', 'payerProgram', 'string')],
           [('filingSpec', 'Timely filing limit', 'string'), ('statusFollowUp', 'Status follow-up after', 'string'),
            ('appealFilingSpec', 'Appeal filing window', 'string'), ('appealDecisionSpec', 'Appeal decision within', 'string')],
           [([q('MEDICARE_FFS')], [q('365D'), q('P30D'), q('120D'), q('60D')],
             '42 CFR 424.44; clean claim 30 d; redetermination 405.942 / 405.950'),
            ([q('MA')], [q('365D'), q('P30D'), q('60D'), q('60D')], 'MA plan terms (verify contract)'),
            ([q('MEDICAID')], [q('365D'), q('P30D'), q('120D'), q('90D')], 'state Medicaid (verify state)'),
            (['-'], [q('180D'), q('P30D'), q('180D'), q('60D')], 'commercial: 45 CFR 147.136 (180 d to appeal, '
                                                                 'post-service 60 d); SYNTHETIC filing limit')],
           doc='US-HLT-100…103.')
    # later 835s (secondary payer, take-back) arrive after the claim case has ended: one case per (trace, PCN)
    rp = pk.proc('sangam-sec-ushealth-remittance', 'Later remittance (835)', '', start={'materialFields': 'x12'})
    sr = rp.root
    r0 = sr.start('rm_start', 'Remittance received (835)', msg='sangam.ushealth.remittance', initiator=None)
    r1 = sr.setv('rm_defaults', 'Configuration', defaults='ledgerId=RECEIVABLES;line=CLAIM;period=OPEN;billerSeat=BILLER;'
                 'eraTraceNumber=no-trace;patientControlNumber=')
    # SL-115: decode and posting fail closed as in the claim case (each cleared before its call; no default stands in)
    r2e, r2 = checked_query(sr, 'rm_parse', 'Decode this claim of the 835', 'x12.remit', 'parse',
                            'x12,eraTraceNumber,patientControlNumber', 'era', ['eraClaimStatus', 'eraBalanced'])
    r3 = sr.xgw('rm_traceGw', 'Trace number present?', policy='priority')  # ADR-D-25: both can hold; explicit precedence
    sr.seq(r0, r1, r2e)
    sr.flow(r2, r3)
    r4e, r4 = checked_query(sr, 'rm_post', 'Post the remittance', 'ledger.post', 'post',
                            'patientControlNumber,eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,'
                            'eraTraceNumber,ledgerId,line,period', 'post', ['postExceptionCount'], mode='submit',
                            step='posting:${eraTraceNumber}')
    rq = sr.user('rm_exception', 'Resolve the remittance exception | Resolver la excepción de la remesa', '${billerSeat}',
                 form='sec-ushealth-claim/remittance-exception', decision=None, fields=['remitSummary', 'remarks*'])
    era_unconfirmed, _ = check_conditions([('era', ['eraClaimStatus', 'eraBalanced'])])
    sr.flow(r3, rq, era_unconfirmed, 'decode not confirmed: not posted', priority=1)
    sr.flow(r3, rq, "eraTraceNumber == 'no-trace'", 'cannot be made idempotent', priority=2)
    sr.flow(r3, r4e, None, 'yes', default=True)
    r5 = sr.xgw('rm_postGw', 'Posted cleanly?')
    sr.flow(r4, r5)
    post_unconfirmed, _ = check_conditions([('post', ['postExceptionCount'])])
    sr.flow(r5, rq, f"{post_unconfirmed} || postExceptionCount > 0 || !eraBalanced || eraClaimStatus == 'DENIED'"
                    " || eraClaimStatus == 'NOT_IN_FILE'",
            'US-HLT-106 work queue')
    r6 = end_ok(rp, sr, 'rm_end', 'Posted', outcome='POSTED')
    sr.flow(r5, r6, None, 'clean', default=True)
    sr.flow(rq, r6)
    pk.dmn('sec-ushealth-claim-denials', 'Denial routing by CARC (SYNTHETIC)', 'FIRST',
           [('First adjustment reason code', 'eraFirstReasonCode', 'string')],
           [('denialRoute', 'Suggested route', 'string')],
           [([q('16')], [q('CORRECT_RESUBMIT')], 'missing information'),
            ([q('50')], [q('APPEAL')], 'not medically necessary'),
            ([q('197')], [q('APPEAL')], 'precertification absent'),
            ([q('29')], [q('WRITE_OFF')], 'timely filing expired'),
            (['-'], [q('REVIEW')], '')])
    useeds(pk, 'BILLING-OFFICE', [
        ('CODER', 'CODER', 'REV-CYCLE-MGR-BILLING', 'Medical coder', 'Codificador médico'),
        ('BILLER', 'BILLER', 'REV-CYCLE-MGR-BILLING', 'Biller', 'Facturador'),
        ('DENIAL-ANALYST', 'DENIALS', 'REV-CYCLE-MGR-BILLING', 'Denial analyst', 'Analista de denegaciones'),
        ('REV-CYCLE-MGR-BILLING', 'RCM', None, 'Revenue cycle manager', 'Gerente del ciclo de ingresos')], 'ucl')


# ============================================================================================ GFE and PPDR
def gfe():
    pk = upack('gfe-ppdr', 'Good-faith estimate and patient-provider dispute resolution (No Surprises Act)',
               'Presupuesto de buena fe y resolución de disputas entre paciente y proveedor (Ley No Surprises)',
               'Good-faith estimate for uninsured or self-pay patients within 1 or 3 business days by scheduling lead '
               '(DMN), co-provider items, bill compared with the estimate, eligibility for patient-provider dispute '
               'resolution when the bill is at least the DMN threshold above the estimate, 120-day initiation window, '
               'collections hold while the dispute is open, SDR entity determination applied to the bill.',
               ['US-HLT-110', 'US-HLT-111', 'US-HLT-112', 'US-HLT-113'] + SIDE,
               [('ppdr.sdr', 'b', '', 'Selected dispute-resolution entity (via HHS portal)'),
                ('notify.mail', 'b', '', 'Patient notice')])
    p = pk.proc('sangam-sec-ushealth-gfe-ppdr', 'Good-faith estimate and dispute', '',
                start={'materialFields': 'patientName,scheduledDate,expectedCharges'})
    s = p.root
    st = sform(p, s, 'gf_start', 'Service scheduled or estimate requested', 'sec-ushealth-gfe-ppdr/schedule',
               'Scheduling | Programación', ['patientName*', 'serviceDescription*', 'scheduledDate', 'daysUntilService*'])
    d = s.setv('gf_defaults', 'Configuration',
               defaults='patientName=@customerId;daysUntilService=10;expectedCharges=0;billedAmount=0;'
                        'gfeSeat=FINANCIAL-COUNSELOR-GFE;billingSeat=PATIENT-BILLING;complianceSeat=COMPLIANCE')
    r = s.rule('gf_cfg', 'GFE timeframe and dispute threshold (DMN)', 'sec-ushealth-gfe-config')
    s.seq(st, d, r)
    ge, gx = s.sla_task('gf_estimate', 'Prepare and deliver the good-faith estimate (with co-providers) | '
                        'Preparar y entregar el presupuesto de buena fe (con coproveedores)', 'gfeSeat',
                        form='sec-ushealth-gfe-ppdr/estimate', decision=None,
                        fields=['serviceDescription*', 'coProviders', 'expectedCharges*', 'documents'],
                        spec='${gfeSpec}', on_breach='ESCALATE')
    s.flow(r, ge)
    bw = s.catch_msg('gf_bill', 'Bill issued', 'sangam.ushealth.billIssued')
    s.flow(gx, bw)
    cmp_ = s.user('gf_compare', 'Compare the bill with the estimate | Comparar la factura con el presupuesto',
                  '${billingSeat}', form='sec-ushealth-gfe-ppdr/compare', decision=None, fields=['billedAmount*', 'billDate*'])
    var = s.expr('gf_variance', 'Bill minus estimate', "${execution.setVariable('billVariance', billedAmount - expectedCharges)}")
    s.seq(bw, cmp_, var)
    eg = s.xgw('gf_eligGw', 'Dispute eligible?')
    s.flow(var, eg)
    s.flow(eg, end_ok(p, s, 'gf_endOk', 'Bill within the estimate'), 'billVariance < ppdrThreshold', 'within threshold')
    nt = s.notify('gf_notice', 'Tell the patient about the dispute process', 'ushealth-ppdr-rights', 'email', 'patientName')
    win = at_date(s, 'gf_window', 'ppdrInitiation', 'billDate', '${ppdrWindowLead}')
    s.flow(eg, nt, None, 'eligible', default=True)
    s.flow(nt, win)
    iw = s.egw('gf_initWait', 'Dispute initiated or window closed')
    s.flow(win, iw)
    ini = s.catch_msg('gf_initiated', 'Patient initiated the dispute', 'sangam.ushealth.ppdrInitiated')
    lap = s.catch_timer('gf_lapsed', 'Initiation window closed', date='${ppdrInitiationDueAt}')
    s.flow(iw, ini)
    s.flow(iw, lap)
    s.flow(lap, end_ok(p, s, 'gf_endLapsed', 'No dispute initiated', outcome='CLOSED'))
    hold = s.setv('gf_hold', 'Collections hold while the dispute is open', values='collectionsHold=true')
    sdr = s.conn('gf_sdr', 'Provide the estimate and bill to the SDR entity', 'ppdr.sdr', 'respond', 'submit',
                 'patientName,expectedCharges,billedAmount', 'sdr', step='sdr')
    s.seq(ini, hold, sdr)
    dw = s.catch_msg('gf_determination', 'SDR determination received', 'sangam.ushealth.ppdrDetermination')
    s.flow(sdr, dw)
    ap = s.user('gf_apply', 'Apply the determination, adjust the bill, release the hold | '
                'Aplicar la determinación, ajustar la factura y liberar la retención', '${billingSeat}',
                form='sec-ushealth-gfe-ppdr/apply', decision=None, fields=['determinationAmount*', 'remarks'])
    rel = s.setv('gf_release', 'Release the collections hold', values='collectionsHold=false')
    s.seq(dw, ap, rel, end_ok(p, s, 'gf_end', 'Dispute resolved', outcome='RESOLVED'))
    s.side_exits('gf')
    pk.dmn('sec-ushealth-gfe-config', 'GFE timeframes and PPDR threshold (45 CFR 149.610 / 149.620; tenant overridable)',
           'FIRST', [('Business days until service', 'daysUntilService', 'integer')],
           [('gfeSpec', 'GFE due within', 'string'), ('ppdrThreshold', 'Dispute threshold (USD)', 'money'),
            ('ppdrWindowLead', 'Initiation window from the bill date', 'string')],
           [(['0'], [q('3WD'), '400', q('-P120D')], 'on request: 3 business days'),
            (['>= 10'], [q('3WD'), '400', q('-P120D')], 'scheduled 10+ business days ahead: 3 business days'),
            (['>= 3'], [q('1WD'), '400', q('-P120D')], 'scheduled 3-9 business days ahead: 1 business day'),
            (['-'], [q('1WD'), '400', q('-P120D')], 'under 3 days: no GFE duty; SYNTHETIC courtesy')],
           doc='US-HLT-110, US-HLT-112. $400 and 120 days: verify current CMS figures.')
    useeds(pk, 'PROVIDER', [
        ('FINANCIAL-COUNSELOR-GFE', 'FINCOUNSEL', 'COMPLIANCE', 'Financial counselor', 'Asesor financiero'),
        ('PATIENT-BILLING', 'PATBILL', 'COMPLIANCE', 'Patient billing', 'Facturación al paciente'),
        ('COMPLIANCE', 'COMPLIANCE', None, 'Compliance officer', 'Oficial de cumplimiento')], 'ugf')


# ============================================================================================ discharge planning
def discharge():
    pk = upack('discharge', 'Discharge planning (CMS Conditions of Participation)',
               'Planificación del alta (Condiciones de Participación de CMS)',
               'Early screening for patients at risk without a plan, Important Message from Medicare within 2 days of '
               'admission and a follow-up copy no more than 2 days before discharge, MOON within 36 hours of '
               'observation, evaluation with patient and caregiver goals, patient choice of post-acute provider with '
               'quality data, transfer of the necessary medical information and the ADT event notification.',
               ['US-HLT-080', 'US-HLT-125', 'US-HLT-126', 'US-HLT-127', 'US-HLT-128', 'US-HLT-129', 'US-HLT-130',
                'US-HLT-131'] + SIDE,
               [('fhir.transition', 'a', '', 'Transition-of-care document (FHIR)'),
                ('fhir.adt', 'a', '', 'ADT event notification')])
    p = pk.proc('sangam-sec-ushealth-discharge', 'Discharge planning', '',
                start={'materialFields': 'patientName,medicareBeneficiary'})
    s = p.root
    st = sform(p, s, 'dc_start', 'Patient admitted or placed in observation', 'sec-ushealth-discharge/admit',
               'Admission | Ingreso', ['patientName*', 'medicareBeneficiary*', 'observationStatus*'])
    d = s.setv('dc_defaults', 'Configuration',
               defaults='patientName=@customerId;medicareBeneficiary=true;observationStatus=false;'
                        'cmSeat=CASE-MANAGER;nurseSeat=CHARGE-NURSE;swSeat=SOCIAL-WORKER')
    r = s.rule('dc_cfg', 'Discharge notice timeframes (DMN)', 'sec-ushealth-discharge-clocks')
    s.seq(st, d, r)
    og = s.xgw('dc_obsGw', 'Observation?')
    s.flow(r, og)
    me, mx = s.sla_task('dc_moon', 'Deliver the MOON notice (observation) | Entregar el aviso MOON (observación)',
                        'nurseSeat', form='sec-ushealth-discharge/moon', decision=None, fields=['noticeSigned*'],
                        spec='${moonSpec}')
    s.flow(og, me, 'observationStatus', 'observation')
    s.flow(mx, end_ok(p, s, 'dc_endObs', 'Observation notice given', outcome='OBSERVATION'))
    ig = s.xgw('dc_imGw', 'Medicare beneficiary?')
    s.flow(og, ig, None, 'inpatient', default=True)
    ie, ix = s.sla_task('dc_im', 'Deliver the Important Message from Medicare | Entregar el Mensaje Importante de Medicare',
                        'nurseSeat', form='sec-ushealth-discharge/im', decision=None, fields=['noticeSigned*'],
                        spec='${imAdmissionSpec}')
    sm = s.xgw('dc_screenMerge')
    s.flow(ig, ie, 'medicareBeneficiary', 'Medicare')
    s.flow(ig, sm, None, 'other payer', default=True)
    s.flow(ix, sm)
    se, sx = s.sla_task('dc_screen', 'Discharge planning screen | Evaluación inicial de planificación del alta', 'cmSeat',
                        form='sec-ushealth-discharge/screen', decision=('screenDecision', ['needsPlan', 'noPlan']),
                        fields=['dischargeRisk*'], spec='${screenSpec}')
    s.flow(sm, se)
    pg = s.xgw('dc_planGw')
    s.flow(sx, pg)
    ev = s.user('dc_eval', 'Discharge planning evaluation with patient and caregiver | '
                'Evaluación de planificación del alta con el paciente y el cuidador', '${cmSeat}',
                form='sec-ushealth-discharge/evaluation', decision=None, fields=['patientGoals*', 'documents'])
    ch = s.user('dc_choice', 'Patient choice of post-acute provider (list with quality data) | '
                'Elección del proveedor de atención posaguda (lista con datos de calidad)', '${swSeat}',
                form='sec-ushealth-discharge/choice', decision=None, fields=['choiceListGiven*', 'postAcuteProvider'])
    om = s.xgw('dc_orderMerge')
    s.flow(pg, ev, "screenDecision == 'needsPlan'", 'plan needed')
    s.seq(ev, ch, om)
    s.flow(pg, om, None, 'no plan needed', default=True)
    order = s.catch_msg('dc_order', 'Discharge order written', 'sangam.ushealth.dischargeOrder')
    s.flow(om, order)
    fe = s.user('dc_imFollow', 'Follow-up copy of the Important Message (no more than 2 days before discharge) | '
                'Copia de seguimiento del Mensaje Importante', '${nurseSeat}', form='sec-ushealth-discharge/im-followup',
                decision=None, fields=['noticeSigned*'])
    g2, m2 = maybe(s, 'dc_followOpt', 'medicareBeneficiary', fe, fe, 'Medicare follow-up copy?')
    s.flow(order, g2)
    tr = s.conn('dc_transfer', 'Send the necessary medical information to the next provider', 'fhir.transition',
                'transaction', 'submit', 'patientName,postAcuteProvider', 'toc', step='transition')
    adt = s.conn('dc_adt', 'ADT discharge notification to the PCP and post-acute provider', 'fhir.adt', 'create',
                 'submit', 'patientName,postAcuteProvider', 'adt', step='adt')
    s.seq(m2, tr, adt, end_ok(p, s, 'dc_end', 'Discharged with plan', outcome='DISCHARGED'))
    s.side_exits('dc')
    pk.dmn('sec-ushealth-discharge-clocks', 'Discharge notice timeframes (42 CFR 405.1205, 489.20(y); tenant overridable)',
           'FIRST', [('Medicare beneficiary', 'medicareBeneficiary', 'boolean')],
           [('imAdmissionSpec', 'IM within', 'string'), ('moonSpec', 'MOON within', 'string'),
            ('screenSpec', 'Screen within (SYNTHETIC policy)', 'string')],
           [(['-'], [q('2D'), q('36H'), q('24H')], 'IM 2 calendar days; MOON 36 hours')], doc='US-HLT-125…131.')
    useeds(pk, 'HOSPITAL', [
        ('CASE-MANAGER', 'CM', 'DIR-CARE-MGMT', 'Case manager', 'Gestor de casos'),
        ('CHARGE-NURSE', 'RN', 'DIR-CARE-MGMT', 'Charge nurse', 'Enfermera a cargo'),
        ('SOCIAL-WORKER', 'SW', 'DIR-CARE-MGMT', 'Social worker', 'Trabajador social'),
        ('DIR-CARE-MGMT', 'DCM', None, 'Director of care management', 'Director de gestión de la atención')], 'udc')


# ============================================================================================ HIPAA right of access
def access():
    pk = upack('access-request', 'HIPAA right of access (30 days, one 30-day extension)',
               'Derecho de acceso HIPAA (30 días, una prórroga de 30 días)',
               'Request logged, identity and authority verified, records assembled from the designated record set '
               'within the DMN timeframe (30 days), one extension of up to 30 days with a written notice inside the '
               'first period, reasonable cost-based fee, copy in the requested form and format or to the directed '
               'third party, written denial with basis and review by a licensed professional not involved in the '
               'denial; information-blocking exception recorded and the 10-business-day infeasibility response.',
               ['US-HLT-002', 'US-HLT-003', 'US-HLT-004', 'US-HLT-005', 'US-HLT-006', 'US-HLT-009', 'US-HLT-047',
                'US-HLT-048', 'US-HLT-051'] + SIDE,
               [('fhir.ehiExport', 'a', '', 'EHI export (FHIR)'), ('notify.mail', 'b', '', 'Notice to the requester')])
    p = pk.proc('sangam-sec-ushealth-access-request', 'Right of access', '',
                start={'materialFields': 'requesterName,recordsRequested,formatRequested'})
    s = p.root
    st = sform(p, s, 'ar_start', 'Access request received', 'sec-ushealth-access-request/request',
               'Access request | Solicitud de acceso',
               ['requesterName*', 'requestType*', 'recordsRequested*', 'formatRequested*', 'thirdPartyRecipient'])
    d = s.setv('ar_defaults', 'Configuration',
               defaults='requesterName=@customerId;requestType=INDIVIDUAL;formatRequested=ELECTRONIC;'
                        'privacySeat=PRIVACY-OFFICE;himSeat=HIM-SPECIALIST;reviewSeat=REVIEWING-PROFESSIONAL')
    r = s.rule('ar_cfg', 'Access timeframes (DMN)', 'sec-ushealth-access-clocks')
    dl = clock(s, 'ar_deadline', 'access', '${accessSpec}')
    s.seq(st, d, r, dl)
    vf = s.user('ar_verify', 'Verify identity and authority | Verificar identidad y autoridad', '${privacySeat}',
                form='sec-ushealth-access-request/verify', decision=('verifyDecision', ['verified', 'notVerified']),
                fields=['identityEvidence*', 'remarks'], due='${accessDueAt}')
    s.flow(dl, vf)
    vg = s.xgw('ar_verifyGw')
    s.flow(vf, vg)
    pr = s.notify('ar_proof', 'Ask the requester for proof', 'ushealth-access-proof', 'email', 'requesterName')
    s.flow(vg, pr, "verifyDecision == 'notVerified'", 'ask for proof')
    s.flow(pr, vf)
    asm = s.user('ar_assemble', 'Assemble the records and decide | Reunir los registros y decidir', '${himSeat}',
                 form='sec-ushealth-access-request/assemble', decision=('accessDecision', ['fulfil', 'extend', 'deny']),
                 fields=['recordsRequested', 'remarks'], due='${accessDueAt}')
    s.flow(vg, asm, None, 'verified', default=True)
    ag = s.xgw('ar_decGw')
    s.flow(asm, ag)
    ext = s.user('ar_extend', 'Send the extension notice (reason and expected date) | Enviar el aviso de prórroga',
                 '${privacySeat}', form='sec-ushealth-access-request/extend', decision=None, fields=['extensionReason*'],
                 due='${accessDueAt}')
    ext2 = s.svc('ar_extClock', 'Extend the deadline once', C['SLA'],
                 {'op': 'DEADLINE', 'clock': 'access', 'spec': '${accessExtensionSpec}'})
    ext3 = s.setv('ar_extended', 'Extension used', values='accessExtended=true')
    s.flow(ag, ext, "accessDecision == 'extend' && !accessExtended", 'extend (once)')
    s.seq(ext, ext2, ext3, asm)
    s.flow(ag, asm, "accessDecision == 'extend' && accessExtended", 'already extended: decide')
    fee = s.user('ar_fee', 'Reasonable cost-based fee | Tarifa razonable basada en costos', '${himSeat}',
                 form='sec-ushealth-access-request/fee', decision=None, fields=['copyFee*'])
    exp = s.conn('ar_export', 'Produce the copy (EHI export in the requested format)', 'fhir.ehiExport', 'operation',
                 'submit', 'requesterName,recordsRequested,formatRequested,thirdPartyRecipient', 'ehi', step='export')
    dlv = s.user('ar_deliver', 'Deliver the copy or send it to the directed third party | Entregar la copia',
                 '${himSeat}', form='sec-ushealth-access-request/deliver', decision=None, fields=['documents*'],
                 due='${accessDueAt}')
    s.flow(ag, fee, "accessDecision == 'fulfil'", 'provide access')
    s.seq(fee, exp, dlv, end_ok(p, s, 'ar_end', 'Access provided', outcome='FULFILLED'))
    inf = s.svc('ar_infClock', 'Infeasibility response clock', C['SLA'],
                {'op': 'DEADLINE', 'clock': 'infeasibility', 'spec': '${infeasibilitySpec}'})
    dn = s.user('ar_deny', 'Written denial with basis, review rights and complaint route | Denegación por escrito',
                '${privacySeat}', form='sec-ushealth-access-request/deny', decision=None,
                fields=['denialBasis*', 'infoBlockingException*', 'reviewRequested'], due='${infeasibilityDueAt}')
    s.flow(ag, inf, None, 'deny', default=True)
    s.flow(inf, dn)
    rvg = s.xgw('ar_reviewGw', 'Review requested?')
    s.flow(dn, rvg)
    rv = s.user('ar_review', 'Review of the denial by a licensed professional not involved | Revisión de la denegación',
                '${reviewSeat}', form='sec-ushealth-access-request/review', decision=('reviewDecision', ['approve', 'reject']),
                fields=['remarks*'], sod=True, sod_steps='ar_deny')
    s.flow(rvg, rv, 'reviewRequested', 'review requested')
    s.flow(rvg, s.end('ar_endDenied', 'Denied', listeners=[p.item_listener('REJECTED')]), None, 'no review',
           default=True)
    rg = s.xgw('ar_reviewOutGw')
    s.flow(rv, rg)
    s.flow(rg, fee, "reviewDecision == 'reject'", 'denial reversed')
    s.flow(rg, s.end('ar_endUpheld', 'Denial upheld', listeners=[p.item_listener('REJECTED')]), None, 'upheld',
           default=True)
    s.side_exits('ar')
    pk.dmn('sec-ushealth-access-clocks', 'Right-of-access timeframes (45 CFR 164.524(b)(2), 171.204(b); tenant overridable)',
           'FIRST', [('Request type', 'requestType', 'string')],
           [('accessSpec', 'Act within', 'string'), ('accessExtensionSpec', 'Extension (from the extension notice)', 'string'),
            ('infeasibilitySpec', 'Infeasibility response within', 'string'), ('accessExtended', 'Extension used', 'boolean'),
            ('reviewRequested', 'Review requested (set by the form)', 'boolean')],
           [(['-'], [q('30D'), q('30D'), q('10WD'), 'false', 'false'],
             '30 + 30 days; state law may be shorter (override); 15+15 was only proposed (2020 NPRM)')],
           doc='US-HLT-002, US-HLT-048.')
    useeds(pk, 'COVERED-ENTITY', [
        ('PRIVACY-OFFICE', 'PRIVACY', None, 'Privacy officer', 'Oficial de privacidad'),
        ('HIM-SPECIALIST', 'HIM', 'PRIVACY-OFFICE', 'Health information management', 'Gestión de información de salud'),
        ('REVIEWING-PROFESSIONAL', 'REVIEWER', 'PRIVACY-OFFICE', 'Reviewing licensed professional',
         'Profesional autorizado revisor')], 'uar')


# ============================================================================================ breach notification
def breach():
    pk = upack('breach', 'HIPAA breach notification', 'Notificación de violaciones de HIPAA',
               'Incident logged with the date of discovery, four-factor risk assessment (breach presumed unless low '
               'probability is documented), law-enforcement delay, notices to individuals within 60 days of discovery '
               '(substitute notice when contacts are out of date), media notice when more than 500 residents of a '
               'state are affected, HHS notice with the individual notices for 500 or more, or in the annual log '
               'within 60 days after the calendar year end for fewer; business-associate reports; documentation kept '
               '6 years. All limits in DMN (state laws override).',
               ['US-HLT-020', 'US-HLT-021', 'US-HLT-022', 'US-HLT-023', 'US-HLT-024', 'US-HLT-025', 'US-HLT-026',
                'US-HLT-027', 'US-HLT-028', 'US-HLT-029', 'US-HLT-030', 'US-HLT-031', 'US-HLT-009', 'US-HLT-041'] + SIDE,
               [('notify.mail', 'b', '', 'Individual notices'), ('hhs.breachPortal', 'b', '', 'HHS OCR breach portal'),
                ('media.release', 'c', '', 'Media notice')])
    p = pk.proc('sangam-sec-ushealth-breach', 'Breach notification', '',
                start={'materialFields': 'incidentSummary,discoveryDate,affectedCount'})
    s = p.root
    st = sform(p, s, 'bn_start', 'Incident reported', 'sec-ushealth-breach/incident', 'Incident | Incidente',
               ['incidentSummary*', 'discoveryDate*', 'affectedCount*', 'maxStateCount*', 'phiTypes*', 'reportedByBa'])
    d = s.setv('bn_defaults', 'Configuration',
               defaults='incidentSummary=@incidentSummary;affectedCount=1;maxStateCount=1;lawEnforcementDelay=false;'
                        'leDelayDays=0;substituteNotice=false;reportedByBa=false;'
                        'privacySeat=PRIVACY-OFFICE;securitySeat=SECURITY-OFFICE;counselSeat=COUNSEL')
    r = s.rule('bn_cfg', 'Notification rules (DMN)', 'sec-ushealth-breach-clocks')
    ind = at_date(s, 'bn_indClock', 'individual', 'discoveryDate', '${individualLead}')
    s.seq(st, d, r, ind)
    ra = s.user('bn_risk', 'Four-factor risk assessment | Evaluación de riesgos de cuatro factores', '${securitySeat}',
                form='sec-ushealth-breach/risk', decision=('riskDecision', ['breach', 'lowProbability']),
                fields=['riskFactors*', 'phiTypes', 'documents'], due='${individualDueAt}')
    s.flow(ind, ra)
    rg = s.xgw('bn_riskGw')
    s.flow(ra, rg)
    lp = s.user('bn_lowProb', 'Approve the low-probability determination (kept 6 years) | Aprobar la determinación',
                '${counselSeat}', form='sec-ushealth-breach/low-probability', decision=('lowDecision', ['approve', 'reject']),
                fields=['remarks*'], sod=True, sod_steps='bn_risk')
    s.flow(rg, lp, "riskDecision == 'lowProbability'", 'low probability')
    lg = s.xgw('bn_lowGw')
    s.flow(lp, lg)
    s.flow(lg, end_ok(p, s, 'bn_endNoBreach', 'Not a reportable breach (documented)', outcome='NOT_BREACH'),
           "lowDecision == 'approve'", 'approved')
    le = s.user('bn_le', 'Law-enforcement delay statement | Declaración de retraso de las fuerzas del orden', '${counselSeat}',
                form='sec-ushealth-breach/law-enforcement', decision=None,
                fields=['lawEnforcementDelay*', 'leDelayDays', 'documents'])
    s.flow(lg, le, None, 'not approved: breach', default=True)
    s.flow(rg, le, None, 'breach', default=True)
    sh = s.svc('bn_leShift', 'Delay the notice clocks by the stated period', C['SLA'],
               {'op': 'SHIFT', 'dateVar': 'individualDueAt', 'daysVar': 'leDelayDays'})
    g0, m0 = maybe(s, 'bn_leOpt', 'lawEnforcementDelay', sh, sh, 'Delay requested?')
    s.flow(le, g0)
    fk = s.pgw('bn_fork', 'Notices in parallel')
    jn = s.pgw('bn_join')
    s.flow(m0, fk)
    # individuals
    ni = s.user('bn_individuals', 'Notify affected individuals (content per 164.404(c)) | Notificar a las personas afectadas',
                '${privacySeat}', form='sec-ushealth-breach/individuals', decision=None,
                fields=['substituteNotice*', 'documents'], due='${individualDueAt}')
    mail = s.conn('bn_mail', 'Send the individual notices', 'notify.mail', 'send', 'submit', 'incidentSummary,affectedCount',
                  'mail', step='individual-notice')
    sub = s.user('bn_substitute', 'Substitute notice: website 90 days or media, toll-free number 90 days | Aviso sustitutivo',
                 '${privacySeat}', form='sec-ushealth-breach/substitute', decision=None, fields=['documents*'])
    gs, ms = maybe(s, 'bn_subOpt', 'substituteNotice', sub, sub, 'Substitute notice needed?')
    s.flow(fk, ni)
    s.seq(ni, mail, gs)
    s.flow(ms, jn)
    # media
    md = s.user('bn_media', 'Notify prominent media in each affected state | Notificar a los medios', '${counselSeat}',
                form='sec-ushealth-breach/media', decision=None, fields=['documents*'], due='${individualDueAt}')
    gm, mm = maybe(s, 'bn_mediaOpt', 'mediaRequired', md, md, 'More than 500 in a state?')
    s.flow(fk, gm)
    s.flow(mm, jn)
    # HHS
    hg = s.xgw('bn_hhsGw', '500 or more?')
    hm = s.xgw('bn_hhsMerge')
    s.flow(fk, hg)
    hhs = s.conn('bn_hhs', 'Report to HHS (breach portal) with the individual notices', 'hhs.breachPortal', 'report',
                 'submit', 'incidentSummary,affectedCount,discoveryDate', 'hhs', step='hhs-immediate')
    s.flow(hg, hhs, "hhsMode == 'IMMEDIATE'", '500 or more')
    s.flow(hhs, hm)
    log = s.user('bn_log', 'Enter in the annual breach log | Registrar en el registro anual', '${privacySeat}',
                 form='sec-ushealth-breach/log', decision=None, fields=['annualLogYearEnd*'])
    ac = at_date(s, 'bn_annualClock', 'hhsAnnual', 'annualLogYearEnd', '${annualLead}')
    aw = s.catch_timer('bn_annualWait', 'Annual report due', date='${hhsAnnualDueAt}')
    ah = s.conn('bn_hhsAnnual', 'Annual report to HHS (fewer than 500)', 'hhs.breachPortal', 'report', 'submit',
                'incidentSummary,affectedCount,discoveryDate', 'hhsa', step='hhs-annual')
    s.flow(hg, log, None, 'fewer than 500', default=True)
    s.seq(log, ac, aw, ah, hm)
    s.flow(hm, jn)
    doc = s.user('bn_close', 'Retain the documentation (6 years) and close | Conservar la documentación y cerrar',
                 '${privacySeat}', form='sec-ushealth-breach/close', decision=None, fields=['documents*', 'remarks'])
    s.seq(jn, doc, end_ok(p, s, 'bn_end', 'Breach notifications complete', outcome='NOTIFIED'))
    s.side_exits('bn')
    pk.dmn('sec-ushealth-breach-clocks', 'Breach notification rules (45 CFR 164.404-408; state law overrides)', 'FIRST',
           [('Individuals affected', 'affectedCount', 'integer'), ('Largest count in one state', 'maxStateCount', 'integer')],
           [('individualLead', 'Individuals: from discovery (negative lead = after)', 'string'),
            ('hhsMode', 'HHS notice', 'string'), ('mediaRequired', 'Media notice', 'boolean'),
            ('annualLead', 'Annual HHS report: after year end', 'string')],
           [(['>= 500', '> 500'], [q('-P60D'), q('IMMEDIATE'), 'true', q('-P60D')], '500+ and >500 in a state'),
            (['>= 500', '-'], [q('-P60D'), q('IMMEDIATE'), 'false', q('-P60D')], '500+ spread across states'),
            (['-', '-'], [q('-P60D'), q('ANNUAL'), 'false', q('-P60D')], 'fewer than 500: annual log')],
           doc='US-HLT-022, US-HLT-026, US-HLT-027. Add tenant overrides for shorter state deadlines (US-HLT-031).')
    useeds(pk, 'COVERED-ENTITY', [
        ('PRIVACY-OFFICE', 'PRIVACY', None, 'Privacy officer', 'Oficial de privacidad'),
        ('SECURITY-OFFICE', 'SECURITY', 'PRIVACY-OFFICE', 'Security officer', 'Oficial de seguridad'),
        ('COUNSEL', 'COUNSEL', 'PRIVACY-OFFICE', 'Legal counsel', 'Asesor jurídico')], 'ubn')


# ============================================================================================ BAA management
def baa():
    pk = upack('baa', 'Business associate agreement management', 'Gestión de acuerdos de socio comercial (BAA)',
               'Business associate onboarding with PHI scope, required BAA terms and subcontractor flow-down, security '
               'assessment, legal approval with segregation of duties, e-signature, periodic review before the end '
               'date (DMN), breach reports from the associate (60 days) routed to breach notification, cure or '
               'terminate on a material breach, return or destruction of PHI at termination.',
               ['US-HLT-035', 'US-HLT-036', 'US-HLT-037', 'US-HLT-038', 'US-HLT-028', 'US-HLT-040', 'US-HLT-009'] + SIDE,
               [('esign.baa', 'b', '', 'BAA e-signature')])
    p = pk.proc('sangam-sec-ushealth-baa', 'Business associate agreement', '',
                start={'materialFields': 'baName,phiScope'})
    s = p.root
    st = sform(p, s, 'ba_start', 'Business associate proposed', 'sec-ushealth-baa/propose',
               'Business associate | Socio comercial', ['baName*', 'phiScope*', 'subcontractors'])
    d = s.setv('ba_defaults', 'Configuration',
               defaults='baName=@supplierName;baaTerms=false;privacySeat=PRIVACY-OFFICE;securitySeat=SECURITY-OFFICE;'
                        'counselSeat=COUNSEL')
    r = s.rule('ba_cfg', 'BAA review policy (DMN)', 'sec-ushealth-baa-config')
    s.seq(st, d, r)
    sec = s.user('ba_security', 'Security assessment of the business associate | Evaluación de seguridad', '${securitySeat}',
                 form='sec-ushealth-baa/security', decision=None, fields=['securityReview*', 'documents'])
    terms = s.user('ba_terms', 'Check the required BAA terms and flow-down | Verificar los términos obligatorios',
                   '${privacySeat}', form='sec-ushealth-baa/terms', decision=None,
                   fields=['baaTerms*', 'subcontractors', 'baaTermEnd*'])
    s.seq(r, sec, terms)
    tg = s.xgw('ba_termsGw', 'Terms complete?')
    s.flow(terms, tg)
    s.flow(tg, terms, '!baaTerms', 'missing terms')
    ap = s.user('ba_approve', 'Legal approval of the BAA | Aprobación jurídica del BAA', '${counselSeat}',
                form='sec-ushealth-baa/approve', decision=('baaDecision', ['approve', 'reject']), fields=['remarks*'],
                sod=True, sod_steps='ba_terms')
    s.flow(tg, ap, None, 'complete', default=True)
    agw = s.xgw('ba_apGw')
    s.flow(ap, agw)
    s.flow(agw, s.end('ba_endRejected', 'Not engaged', listeners=[p.item_listener('REJECTED')]),
           "baaDecision == 'reject'", 'rejected')
    sg = s.conn('ba_sign', 'Sign the BAA (e-signature)', 'esign.baa', 'sign', 'submit', 'baName,phiScope', 'sign',
                step='sign')
    s.flow(agw, sg, None, 'approved', default=True)
    rvc = at_date(s, 'ba_reviewClock', 'baaReview', 'baaTermEnd', '${reviewLead}')
    s.flow(sg, rvc)
    w = s.egw('ba_life', 'Review due, breach report or termination')
    s.flow(rvc, w)
    rt = s.catch_timer('ba_reviewDue', 'Periodic review due', date='${baaReviewDueAt}')
    br = s.catch_msg('ba_breach', 'Breach reported by the associate', 'sangam.ushealth.baBreach')
    tm = s.catch_msg('ba_terminate', 'Termination ordered', 'sangam.ushealth.baTerminate')
    for x in (rt, br, tm):
        s.flow(w, x)
    rv = s.user('ba_review', 'Periodic review: renew or terminate | Revisión periódica: renovar o terminar', '${privacySeat}',
                form='sec-ushealth-baa/review', decision=('reviewDecision', ['renew', 'terminate']),
                fields=['securityReview*', 'baaTermEnd*'])
    s.flow(rt, rv)
    rg = s.xgw('ba_reviewGw')
    s.flow(rv, rg)
    s.flow(rg, rvc, "reviewDecision == 'renew'", 'renew')
    bstart = s.svc('ba_startBreach', 'Open breach notification', C['LINK'],
                   {'processKey': 'sangam-sec-ushealth-breach', 'linkType': 'RELATED', 'businessKeySuffix': '/breach', 'inherit': 'incidentSummary,phiTypes'})
    cure = s.user('ba_cure', 'Material breach: cure or terminate | Incumplimiento material: subsanar o terminar',
                  '${counselSeat}', form='sec-ushealth-baa/cure', decision=('cureDecision', ['cured', 'terminate']),
                  fields=['cureNotes*'])
    s.seq(br, bstart, cure)
    cg = s.xgw('ba_cureGw')
    s.flow(cure, cg)
    s.flow(cg, w, "cureDecision == 'cured'", 'cured')
    dispo = s.user('ba_dispose', 'Return or destroy PHI and keep the certificate (6 years) | Devolver o destruir la PHI',
                   '${privacySeat}', form='sec-ushealth-baa/dispose', decision=None, fields=['phiDisposition*'])
    tjoin = s.xgw('ba_termJoin')
    s.flow(rg, tjoin, None, 'terminate', default=True)
    s.flow(cg, tjoin, None, 'terminate', default=True)
    s.flow(tm, tjoin)
    s.seq(tjoin, dispo, end_ok(p, s, 'ba_end', 'BAA terminated and PHI disposed', outcome='TERMINATED'))
    s.side_exits('ba')
    pk.dmn('sec-ushealth-baa-config', 'BAA review policy (SYNTHETIC; tenant overridable)', 'FIRST',
           [('PHI scope', 'phiScope', 'string')], [('reviewLead', 'Review before the end date', 'string')],
           [(['-'], [q('P60D')], 'review 60 days before the BAA end date; annual review recommended')],
           doc='US-HLT-038.')
    useeds(pk, 'COVERED-ENTITY', [
        ('PRIVACY-OFFICE', 'PRIVACY', None, 'Privacy officer', 'Oficial de privacidad'),
        ('SECURITY-OFFICE', 'SECURITY', 'PRIVACY-OFFICE', 'Security officer', 'Oficial de seguridad'),
        ('COUNSEL', 'COUNSEL', 'PRIVACY-OFFICE', 'Legal counsel', 'Asesor jurídico')], 'uba')


# ============================================================================================ payer APIs (CMS-0057-F)
# M-133, owner decision 107: the CMS-0057-F payer APIs are in 0.1.0 (the No Surprises Act items stay traceability
# only). These packs are the payer-side workflows behind the FHIR APIs: the API itself is the payer's FHIR server,
# reached through ConnectorGateway (sangam-connectors-health FhirConnector, Bulk Data through its operation call).
# The 1-business-day response time (US-HLT-064) and the 5-year look-back (US-HLT-063) are DMN outputs, so a tenant
# overrides them without touching the models; US-HLT-064 is marked (verify) in the source.

def p2p_config(pk, key):
    pk.dmn(key, 'Payer API timeframes (CMS-0057-F; tenant overridable)', 'FIRST',
           [('Payer program', 'payerProgram', 'string')],
           [('p2pLookback', 'Look-back period of the data exchanged', 'string'),
            ('p2pResponseSpec', 'Response due within', 'string'),
            ('p2pRetryAfter', 'Retry after (other payer unavailable)', 'string')],
           [(['-'], [q('P5Y'), q('1WD'), q('PT4H')], 'US-HLT-063 five years of data; US-HLT-064 one business day '
                                                     '(verify in the rule text)')],
           doc='US-HLT-063, US-HLT-064 (verify). Override per tenant or payer program.')


def payer_to_payer():
    pk = upack('payer-to-payer', 'Payer-to-Payer data exchange (CMS-0057-F)',
               'Intercambio de datos entre pagadores (CMS-0057-F)',
               'New payer: the patient opt-in is recorded first and nothing is requested without it; the member is '
               'matched at the previous payer, the data of the look-back period (DMN, five years) is requested with the '
               'response clock from DMN and stored in the member record; an unanswered request is chased or closed by '
               'the interoperability analyst. Previous payer: a request is answered only with the requesting payer\'s '
               'opt-in attestation and a confirmed member match, within the response clock.',
               ['US-HLT-063', 'US-HLT-064', 'US-HLT-067'] + SIDE,
               [('payer.memberMatch', 'a', '', 'Member match at the other payer ($member-match)'),
                ('payer.p2pData', 'b', '', 'Payer-to-Payer data request and response (FHIR Bulk Data)'),
                ('payer.memberRecord', 'b', '', 'Member record of this payer (FHIR)')])
    p = pk.proc('sangam-sec-ushealth-p2p-request', 'Payer-to-Payer: request data from the previous payer', '',
                start={'materialFields': 'memberId,previousPayerId,p2pOptIn'})
    s = p.root
    st = sform(p, s, 'pq_start', 'New member enrolled', 'sec-ushealth-payer-to-payer/enrol',
               'New member | Nuevo afiliado',
               ['patientName*', 'memberId*', 'payerProgram*', 'previousPayerId*', 'previousMemberId', 'p2pOptIn*'])
    d = s.setv('pq_defaults', 'Configuration',
               defaults='patientName=@customerId;memberId=@customerId;payerProgram=MA;previousPayerId=SIMPAYER;'
                        'p2pOptIn=false;interopSeat=INTEROP-ANALYST;memberSeat=MEMBER-SERVICES')
    r = s.rule('pq_cfg', 'Payer API timeframes (DMN)', 'sec-ushealth-p2p-config')
    s.seq(st, d, r)
    og = s.xgw('pq_optGw', 'Patient opted in?')
    s.flow(r, og)
    # no exchange without the patient's opt-in (US-HLT-063); the default false makes a missing answer a refusal
    s.flow(og, end_ok(p, s, 'pq_endNoOptIn', 'No opt-in: nothing requested', outcome='NOT_OPTED_IN'), None,
           'not opted in', default=True)
    me, m = checked_query(s, 'pq_match', 'Member match at the previous payer ($member-match)', 'payer.memberMatch',
                          'match', 'memberId,previousPayerId,previousMemberId,patientName', 'match', ['matchPatientId'])
    s.flow(og, me, 'p2pOptIn == true', 'opted in')
    mg = s.xgw('pq_matchGw', 'Member matched?')
    s.flow(m, mg)
    match_unconfirmed, _ = check_conditions([('match', ['matchPatientId'])])
    s.flow(mg, unconfirmed_hold(p, s, 'pq_matchHold', 'No confirmed member match: correct the member data | '
                                'Sin coincidencia confirmada: corregir los datos del afiliado', '${memberSeat}', me,
                                fields=('previousPayerId*', 'previousMemberId', 'remarks*')),
           match_unconfirmed, 'not matched')
    dl = clock(s, 'pq_deadline', 'p2pResponse', '${p2pResponseSpec}')
    s.flow(mg, dl, None, 'matched', default=True)
    re_, rq = checked_query(s, 'pq_request', 'Request the member data of the look-back period (Bulk Data)',
                            'payer.p2pData', 'request', 'matchPatientId,previousPayerId,p2pLookback', 'p2pData',
                            ['p2pDataBundleRef'])
    s.flow(dl, re_)
    rg = s.xgw('pq_dataGw', 'Data received?')
    s.flow(rq, rg)
    data_unconfirmed, _ = check_conditions([('p2pData', ['p2pDataBundleRef'])])
    w = s.egw('pq_wait', 'Data ready, or the response time passed')
    s.flow(rg, w, data_unconfirmed, 'not received yet')
    ready = s.catch_msg('pq_ready', 'Data ready at the previous payer', 'sangam.ushealth.p2pDataReady')
    late = s.catch_timer('pq_late', 'Response time passed', date='${p2pResponseDueAt}')
    s.flow(w, ready)
    s.flow(w, late)
    s.flow(ready, re_)
    chase = s.user('pq_chase', 'Previous payer did not answer in time: chase or close | '
                   'El pagador anterior no respondió a tiempo: insistir o cerrar', '${interopSeat}',
                   form='sec-ushealth-payer-to-payer/chase', decision=('chaseDecision', ['retry', 'closeUnanswered']),
                   fields=['interopNotes*'])
    s.flow(late, chase)
    cg = s.xgw('pq_chaseGw')
    s.flow(chase, cg)
    s.flow(cg, re_, "chaseDecision == 'retry'", 'retry')
    s.flow(cg, end_ok(p, s, 'pq_endUnanswered', 'Closed: no data received', outcome='NOT_RECEIVED'), None, 'close',
           default=True)
    ie, ing = checked_query(s, 'pq_store', 'Store the received data in the member record', 'payer.memberRecord',
                            'ingest', 'memberId,p2pDataBundleRef', 'store', ['storeRecordRef'], mode='submit',
                            step='p2p-store')
    s.flow(rg, ie, None, 'received', default=True)
    sg = s.xgw('pq_storeGw', 'Stored?')
    s.flow(ing, sg)
    store_unconfirmed, _ = check_conditions([('store', ['storeRecordRef'])])
    s.flow(sg, unconfirmed_hold(p, s, 'pq_storeHold', 'Storing the received data not confirmed: check the member record | '
                                'Almacenamiento no confirmado: revisar el registro del afiliado', '${interopSeat}', ie,
                                fields=('interopNotes*',)), store_unconfirmed, 'not confirmed')
    s.flow(sg, end_ok(p, s, 'pq_end', 'Data received and stored', outcome='RECEIVED', data=['storeRecordRef']), None,
           'stored', default=True)
    s.side_exits('pq')

    p = pk.proc('sangam-sec-ushealth-p2p-respond', 'Payer-to-Payer: answer a request from the new payer', '',
                start={'materialFields': 'memberId,requestingPayerId,optInAttested'})
    s = p.root
    st = sform(p, s, 'pr_start', 'Request received from the new payer', 'sec-ushealth-payer-to-payer/inbound',
               'Request from the new payer | Solicitud del nuevo pagador',
               ['patientName*', 'memberId*', 'payerProgram*', 'requestingPayerId*', 'optInAttested*'])
    d = s.setv('pr_defaults', 'Configuration',
               defaults='patientName=@customerId;memberId=@customerId;payerProgram=MA;requestingPayerId=SIMPAYER;'
                        'optInAttested=false;interopSeat=INTEROP-ANALYST;memberSeat=MEMBER-SERVICES')
    r = s.rule('pr_cfg', 'Payer API timeframes (DMN)', 'sec-ushealth-p2p-config')
    dl = clock(s, 'pr_deadline', 'p2pResponse', '${p2pResponseSpec}')
    s.seq(st, d, r, dl)
    og = s.xgw('pr_optGw', 'Opt-in attested?')
    s.flow(dl, og)
    s.flow(og, s.end('pr_endNoOptIn', 'Refused: no opt-in attestation',
                     listeners=[p.item_listener('REJECTED')]), None, 'no attestation', default=True)
    me, m = checked_query(s, 'pr_match', 'Match the member in this payer\'s records', 'payer.memberRecord', 'match',
                          'memberId,patientName', 'local', ['localPatientId'])
    s.flow(og, me, 'optInAttested == true', 'attested')
    mg = s.xgw('pr_matchGw', 'Member matched?')
    s.flow(m, mg)
    local_unconfirmed, _ = check_conditions([('local', ['localPatientId'])])
    s.flow(mg, unconfirmed_hold(p, s, 'pr_matchHold', 'No confirmed member match: establish it before any data leaves | '
                                'Sin coincidencia confirmada: establecerla antes de enviar datos', '${memberSeat}', me,
                                fields=('remarks*',)), local_unconfirmed, 'not matched')
    xe, x = checked_query(s, 'pr_export', 'Export the member data of the look-back period (Bulk Data)',
                          'payer.memberRecord', 'export', 'localPatientId,p2pLookback', 'export', ['exportBundleRef'])
    s.flow(mg, xe, None, 'matched', default=True)
    xg = s.xgw('pr_exportGw', 'Export ready?')
    s.flow(x, xg)
    export_unconfirmed, _ = check_conditions([('export', ['exportBundleRef'])])
    s.flow(xg, unconfirmed_hold(p, s, 'pr_exportHold', 'Export not confirmed: check the member record | '
                                'Exportación no confirmada: revisar el registro del afiliado', '${interopSeat}', xe,
                                fields=('interopNotes*',)), export_unconfirmed, 'not confirmed')
    se, sd = checked_query(s, 'pr_send', 'Send the data to the new payer', 'payer.p2pData', 'respond',
                           'requestingPayerId,memberId,exportBundleRef', 'sent', ['sentReceiptRef'], mode='submit',
                           step='p2p-send')
    s.flow(xg, se, None, 'ready', default=True)
    sg = s.xgw('pr_sendGw', 'Sent?')
    s.flow(sd, sg)
    sent_unconfirmed, _ = check_conditions([('sent', ['sentReceiptRef'])])
    s.flow(sg, unconfirmed_hold(p, s, 'pr_sendHold', 'Sending not confirmed: check with the new payer | '
                                'Envío no confirmado: verificar con el nuevo pagador', '${interopSeat}', se,
                                fields=('interopNotes*',)), sent_unconfirmed, 'not confirmed')
    s.flow(sg, end_ok(p, s, 'pr_end', 'Data sent', outcome='SENT', data=['sentReceiptRef']), None, 'sent', default=True)
    s.side_exits('pr')
    p2p_config(pk, 'sec-ushealth-p2p-config')
    useeds(pk, 'PAYER', [
        ('INTEROP-ANALYST', 'INTEROP', 'MEMBER-SERVICES', 'Interoperability analyst', 'Analista de interoperabilidad'),
        ('MEMBER-SERVICES', 'MEMBERSVC', None, 'Member services', 'Servicios al afiliado')], 'upp')


def provider_access():
    pk = upack('provider-access', 'Provider Access request (CMS-0057-F)', 'Solicitud de acceso del proveedor (CMS-0057-F)',
               'An in-network provider asks for a patient\'s data: the treatment relationship (attribution) and the '
               'patient\'s opt-out are both checked at their source before anything is exported; an unconfirmed check '
               'holds the request for a person and never releases data; the answer is due within the response clock '
               'from DMN (one business day, to verify).',
               ['US-HLT-062', 'US-HLT-064', 'US-HLT-067'] + SIDE,
               [('payer.attribution', 'a', '', 'Provider attribution (treatment relationship)'),
                ('payer.memberRecord', 'a', '', 'Member record and opt-out of this payer (FHIR)'),
                ('payer.providerAccess', 'b', '', 'Provider Access answer (FHIR Bulk Data)')])
    p = pk.proc('sangam-sec-ushealth-provider-access', 'Provider Access request', '',
                start={'materialFields': 'memberId,providerNpi'})
    s = p.root
    st = sform(p, s, 'pv_start', 'Provider request received', 'sec-ushealth-provider-access/request',
               'Provider request | Solicitud del proveedor', ['patientName*', 'memberId*', 'payerProgram*', 'providerNpi*'])
    d = s.setv('pv_defaults', 'Configuration',
               defaults='patientName=@customerId;memberId=@customerId;payerProgram=MA;providerNpi=1234567893;'
                        'interopSeat=INTEROP-ANALYST;memberSeat=MEMBER-SERVICES')
    r = s.rule('pv_cfg', 'Payer API timeframes (DMN)', 'sec-ushealth-provider-access-config')
    dl = clock(s, 'pv_deadline', 'p2pResponse', '${p2pResponseSpec}')
    s.seq(st, d, r, dl)
    ae, at = checked_query(s, 'pv_attr', 'Check the treatment relationship (attribution)', 'payer.attribution', 'check',
                           'memberId,providerNpi', 'attr', ['attrAttributed'])
    oe, oo = checked_query(s, 'pv_optOut', 'Check the patient\'s opt-out', 'payer.memberRecord', 'optOut', 'memberId',
                           'optout', ['optoutOptedOut'])
    s.flow(dl, ae)
    s.flow(at, oe)
    g = s.xgw('pv_checkGw', 'Data may be shared?')
    s.flow(oo, g)
    unconfirmed, adverse = check_conditions([('attr', ['attrAttributed']), ('optout', ['optoutOptedOut'])],
                                            '!attrAttributed || optoutOptedOut')
    s.flow(g, unconfirmed_hold(p, s, 'pv_checkHold', 'Attribution or opt-out not confirmed: confirm at the source | '
                               'Atribución o exclusión no confirmada: confirmar en la fuente', '${memberSeat}', ae,
                               fields=('remarks*',)), unconfirmed, 'not confirmed')
    s.flow(g, s.end('pv_endRefused', 'Refused: no treatment relationship, or the patient opted out',
                    listeners=[p.item_listener('REJECTED')]), adverse, 'not attributed / opted out')
    xe, x = checked_query(s, 'pv_export', 'Export the permitted data (Bulk Data)', 'payer.memberRecord', 'export',
                          'memberId,providerNpi', 'export', ['exportBundleRef'])
    s.flow(g, xe, None, 'may share', default=True)
    xg = s.xgw('pv_exportGw', 'Export ready?')
    s.flow(x, xg)
    export_unconfirmed, _ = check_conditions([('export', ['exportBundleRef'])])
    s.flow(xg, unconfirmed_hold(p, s, 'pv_exportHold', 'Export not confirmed: check the member record | '
                                'Exportación no confirmada: revisar el registro del afiliado', '${interopSeat}', xe,
                                fields=('interopNotes*',)), export_unconfirmed, 'not confirmed')
    se, sd = checked_query(s, 'pv_send', 'Answer the provider', 'payer.providerAccess', 'respond',
                           'providerNpi,memberId,exportBundleRef', 'sent', ['sentReceiptRef'], mode='submit',
                           step='provider-send')
    s.flow(xg, se, None, 'ready', default=True)
    sg = s.xgw('pv_sendGw', 'Answered?')
    s.flow(sd, sg)
    sent_unconfirmed, _ = check_conditions([('sent', ['sentReceiptRef'])])
    s.flow(sg, unconfirmed_hold(p, s, 'pv_sendHold', 'Answer not confirmed: check with the provider | '
                                'Respuesta no confirmada: verificar con el proveedor', '${interopSeat}', se,
                                fields=('interopNotes*',)), sent_unconfirmed, 'not confirmed')
    s.flow(sg, end_ok(p, s, 'pv_end', 'Provider answered', outcome='SENT', data=['sentReceiptRef']), None, 'answered',
           default=True)
    s.side_exits('pv')
    p2p_config(pk, 'sec-ushealth-provider-access-config')
    useeds(pk, 'PAYER', [
        ('INTEROP-ANALYST', 'INTEROP', 'MEMBER-SERVICES', 'Interoperability analyst', 'Analista de interoperabilidad'),
        ('MEMBER-SERVICES', 'MEMBERSVC', None, 'Member services', 'Servicios al afiliado')], 'upv')


# ============================================================================================ Patient Access API (US-HLT-061)
# M-133, owner decision 174 (US-only, decision 107): the payer-side workflows behind the CMS-0057-F additions to the
# Patient Access API. The API is the payer's FHIR server (CMS-9115-F); Sangam makes each prior authorization change
# available through it and runs the usage-metrics reporting cycle. The only local source is the requirement summary
# US-HLT-061 (spike/results/US-HEALTH-WORKFLOWS.md:102); the rule texts CMS-0057-F and CMS-9115-F (42 CFR 422.119) were
# cited there but not read, so every row is UNVERIFIED + SYNTHETIC_DEMO_ONLY (decision 288) and both tables are pack
# placeholders (readiness blocks go-live until the tenant deploys confirmed overrides). Values the summary does not
# state (the availability timeframe, the retention, the report due date) are SYNTHETIC demonstration values.

SRC_061 = (cited.CITED_NOT_READ + 'CMS-0057-F; CMS-9115-F (42 CFR 422.119); requirement summary US-HLT-061 '
           '(spike/results/US-HEALTH-WORKFLOWS.md:102, read)')
PA_SCOPE_NOTE = ('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decision 288): the requirement summary US-HLT-061 says the Patient '
                 'Access API gains the prior authorization information, excluding drugs, from 1 Jan 2027; the primary '
                 'rule text was not read')
PA_TIMES_NOTE = (PA_SCOPE_NOTE + '. 1WD (available within one business day) and P1Y (kept at least one year after the '
                 'last status change) are SYNTHETIC demonstration values: no local source states the availability '
                 'timeframe or the retention (open point); deploy the confirmed values as a tenant override')
METRICS_NOTE = ('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decision 288): the requirement summary US-HLT-061 says usage metrics '
                'are reported from 1 Jan 2026 and gives no frequency, due date, recipient or content; P90D is a '
                'SYNTHETIC demonstration value (open point); deploy the confirmed rule as a tenant override')
FAIL_CLOSED_SOURCE = 'none: pack fail-closed rule (no regulatory content)'


def patient_access():
    pk = upack('patient-access', 'Patient Access API: prior authorization information and usage metrics (CMS-0057-F)',
               'API de acceso del paciente: información de autorizaciones previas y métricas de uso (CMS-0057-F)',
               'Every prior authorization request or status change of an item or service is made available to the '
               'patient through the Patient Access API within the timeframe from DMN, with the retention from DMN; a '
               'drug is not part of it (US-HLT-061); an item category or status the table does not know is neither '
               'published nor excluded but held for a person, and an unconfirmed publication is held, never closed '
               'as done. The usage-metrics cycle compiles the metrics of the reporting period, a compliance officer '
               'reviews them and the report is submitted with a confirmed receipt. Every rule row is UNVERIFIED '
               '(decision 288): readiness blocks go-live until confirmed tenant overrides.',
               ['US-HLT-061'] + SIDE,
               [('payer.patientAccess', 'b', '', 'Patient Access API of this payer (FHIR): prior authorization '
                                                 'information made available to the patient'),
                ('payer.apiMetrics', 'a', '', 'Patient Access API usage metrics of the reporting period'),
                ('payer.metricsReport', 'b', '', 'Submit the usage metrics report (recipient configured by the '
                                                 'tenant)')])

    # ------------------------------------------------------------------ prior authorization information (2027-01-01)
    p = pk.proc('sangam-sec-ushealth-patient-access-pa', 'Patient Access API: prior authorization information', '',
                start={'materialFields': 'memberId,priorAuthNumber,itemCategory'})
    s = p.root
    st = sform(p, s, 'pt_start', 'Prior authorization request or status change received',
               'sec-ushealth-patient-access/pa-change',
               'Prior authorization change | Cambio en una autorización previa',
               ['patientName*', 'memberId*', 'payerProgram*', 'priorAuthNumber*', 'itemCategory*', 'paStatus*'])
    # a missing category or status is a sentinel the scope gateway holds for a person (never published or excluded)
    d = s.setv('pt_defaults', 'Configuration',
               defaults='patientName=@customerId;memberId=@customerId;payerProgram=MA;priorAuthNumber=@customerId;'
                        'itemCategory=UNCLASSIFIED;paStatus=NOT_STATED;interopSeat=INTEROP-ANALYST;'
                        'memberSeat=MEMBER-SERVICES')
    r = s.rule('pt_cfg', 'Patient Access API scope and timeframes (DMN)', 'sec-ushealth-patient-access-config')
    s.seq(st, d, r)
    g = s.xgw('pt_scopeGw', 'Part of the prior authorization information?')
    s.flow(r, g)
    s.flow(g, end_ok(p, s, 'pt_endExcluded', 'Drug: not part of the prior authorization information',
                     outcome='NOT_IN_SCOPE'), "paScope == 'EXCLUDED_DRUG'", 'drug')
    dl = clock(s, 'pt_deadline', 'paPublish', '${paPublishSpec}')
    s.flow(g, dl, "paScope == 'IN_SCOPE' && paStatus != 'NOT_STATED'", 'item or service')
    s.flow(g, unconfirmed_hold(p, s, 'pt_classifyHold', 'Item category or status not stated: classify before anything '
                               'is published | Categoría o estado no indicados: clasificar antes de publicar',
                               '${memberSeat}', r, fields=('itemCategory*', 'paStatus*', 'remarks*')),
           None, 'not classified', default=True)
    pe, pc = checked_query(s, 'pt_publish', 'Make the prior authorization information available (Patient Access API)',
                           'payer.patientAccess', 'publish', 'memberId,priorAuthNumber,paStatus,paRetentionSpec',
                           'publish', ['publishRecordRef'], mode='submit', step='pa-publish')
    s.flow(dl, pe)
    pg = s.xgw('pt_publishGw', 'Available to the patient?')
    s.flow(pc, pg)
    publish_unconfirmed, _ = check_conditions([('publish', ['publishRecordRef'])])
    s.flow(pg, unconfirmed_hold(p, s, 'pt_publishHold', 'Publication not confirmed: check the Patient Access API | '
                                'Publicación no confirmada: revisar la API de acceso del paciente', '${interopSeat}', pe,
                                fields=('interopNotes*',)), publish_unconfirmed, 'not confirmed')
    s.flow(pg, end_ok(p, s, 'pt_end', 'Prior authorization information available to the patient', outcome='PUBLISHED',
                      data=['publishRecordRef']), None, 'available', default=True)
    s.side_exits('pt')

    # ------------------------------------------------------------------------------------ usage metrics (2026-01-01)
    p = pk.proc('sangam-sec-ushealth-patient-access-metrics', 'Patient Access API: usage metrics report', '',
                start={'materialFields': 'payerProgram,reportingPeriod'})
    s = p.root
    st = sform(p, s, 'pm_start', 'Usage metrics reporting cycle opened', 'sec-ushealth-patient-access/metrics-cycle',
               'Usage metrics report | Informe de métricas de uso', ['payerProgram*', 'reportingPeriod*'])
    d = s.setv('pm_defaults', 'Configuration',
               defaults='payerProgram=MA;reportingPeriod=NOT_STATED;interopSeat=INTEROP-ANALYST;'
                        'complianceSeat=COMPLIANCE-OFFICER-PAYER')
    pgw = s.xgw('pm_periodGw', 'Reporting period stated?')
    s.seq(st, d, pgw)
    s.flow(pgw, unconfirmed_hold(p, s, 'pm_periodHold', 'Reporting period not stated: state it before anything is '
                                 'compiled | Período no indicado: indicarlo antes de compilar', '${interopSeat}', pgw,
                                 fields=('reportingPeriod*', 'remarks*')), "reportingPeriod == 'NOT_STATED'",
           'not stated')
    r = s.rule('pm_cfg', 'Usage metrics report rule (DMN)', 'sec-ushealth-patient-access-metrics-config')
    dl = clock(s, 'pm_deadline', 'metricsReport', '${metricsReportSpec}')
    s.flow(pgw, r, None, 'stated', default=True)
    s.flow(r, dl)
    ce, cc = checked_query(s, 'pm_collect', 'Compile the usage metrics of the reporting period', 'payer.apiMetrics',
                           'collect', 'payerProgram,reportingPeriod', 'metrics', ['metricsReportRef'])
    s.flow(dl, ce)
    cg = s.xgw('pm_collectGw', 'Metrics compiled?')
    s.flow(cc, cg)
    collect_unconfirmed, _ = check_conditions([('metrics', ['metricsReportRef'])])
    s.flow(cg, unconfirmed_hold(p, s, 'pm_collectHold', 'Metrics not compiled: check the API usage records | '
                                'Métricas no compiladas: revisar los registros de uso de la API', '${interopSeat}', ce,
                                fields=('interopNotes*',)), collect_unconfirmed, 'not compiled')
    rv = s.user('pm_review', 'Review the usage metrics report | Revisar el informe de métricas de uso',
                '${complianceSeat}', form='sec-ushealth-patient-access/metrics-review',
                decision=('metricsDecision', ['submitReport', 'recompile']), fields=['reportingPeriod', 'metricsReview*'],
                due='${metricsReportDueAt}')
    s.flow(cg, rv, None, 'compiled', default=True)
    rg = s.xgw('pm_reviewGw', 'Submit?')
    s.flow(rv, rg)
    s.flow(rg, ce, "metricsDecision == 'recompile'", 'compile again')
    se, sc = checked_query(s, 'pm_submit', 'Submit the usage metrics report', 'payer.metricsReport', 'submit',
                           'payerProgram,reportingPeriod,metricsReportRef', 'mreport', ['mreportReceiptRef'],
                           mode='submit', step='metrics-report')
    s.flow(rg, se, None, 'submit', default=True)
    sg = s.xgw('pm_submitGw', 'Receipt confirmed?')
    s.flow(sc, sg)
    submit_unconfirmed, _ = check_conditions([('mreport', ['mreportReceiptRef'])])
    s.flow(sg, unconfirmed_hold(p, s, 'pm_submitHold', 'Submission not confirmed: confirm the receipt and resend | '
                                'Envío no confirmado: confirmar el acuse y reenviar', '${interopSeat}', se,
                                fields=('interopNotes*',)), submit_unconfirmed, 'not confirmed')
    s.flow(sg, end_ok(p, s, 'pm_end', 'Usage metrics report submitted', outcome='REPORTED', data=['mreportReceiptRef']),
           None, 'submitted', default=True)
    s.side_exits('pm')

    cited.table(pk, 'sec-ushealth-patient-access-config', 'Patient Access API: scope, availability and retention of the '
                'prior authorization information (UNVERIFIED; tenant overridable)', 'FIRST',
                [('Item category', 'itemCategory', 'string')],
                [('paScope', 'Part of the prior authorization information', 'string'),
                 ('paPublishSpec', 'Available in the Patient Access API within', 'string'),
                 ('paRetentionSpec', 'Kept available after the last status change for at least', 'string')],
                [cited.row([q('DRUG')], [q('EXCLUDED_DRUG'), q(''), q('')], cited.UNVERIFIED, cited.DEMO_ONLY,
                           PA_SCOPE_NOTE, SRC_061, annotation='US-HLT-061 excluding drugs'),
                 cited.row([q('ITEM_OR_SERVICE')], [q('IN_SCOPE'), q('1WD'), q('P1Y')], cited.UNVERIFIED,
                           cited.DEMO_ONLY, PA_TIMES_NOTE, SRC_061,
                           annotation='US-HLT-061 items and services from 1 Jan 2027; 1WD and P1Y SYNTHETIC'),
                 cited.row(['-'], [q('UNCLASSIFIED'), q(''), q('')], cited.UNVERIFIED, cited.DEMO_ONLY,
                           'fail closed: a category the table does not know is neither published nor excluded; a '
                           'person classifies it', FAIL_CLOSED_SOURCE, annotation='unknown category: held')],
                doc='US-HLT-061 (requirement summary read; CMS-0057-F and 42 CFR 422.119 cited, not read). Override per '
                    'tenant with the confirmed rule (GP-37).',
                placeholder=('UNVERIFIED (use SYNTHETIC_DEMO_ONLY) Patient Access API prior authorization scope '
                             '(excluding drugs, from 1 Jan 2027) with SYNTHETIC availability 1WD and retention P1Y',
                             'CMS-0057-F and 42 CFR 422.119 were not read (decision 288); the availability timeframe '
                             'and the retention are in no local source; deploy the confirmed rule as a tenant '
                             'override'))
    cited.table(pk, 'sec-ushealth-patient-access-metrics-config', 'Patient Access API usage metrics report (UNVERIFIED; '
                'tenant overridable)', 'FIRST',
                [('Payer program', 'payerProgram', 'string')],
                [('metricsReportSpec', 'Report due within', 'string')],
                [cited.row(['-'], [q('P90D')], cited.UNVERIFIED, cited.DEMO_ONLY, METRICS_NOTE, SRC_061,
                           annotation='US-HLT-061 usage metrics from 1 Jan 2026; P90D SYNTHETIC')],
                doc='US-HLT-061 (requirement summary read; CMS-0057-F cited, not read). Override per tenant or payer '
                    'program with the confirmed rule (GP-37).',
                placeholder=('UNVERIFIED (use SYNTHETIC_DEMO_ONLY) Patient Access API usage metrics report due P90D '
                             '(SYNTHETIC)',
                             'CMS-0057-F was not read (decision 288); the frequency, due date, recipient and content of '
                             'the usage metrics report are in no local source; deploy the confirmed rule as a tenant '
                             'override'))
    useeds(pk, 'PAYER', [
        ('INTEROP-ANALYST', 'INTEROP', 'MEMBER-SERVICES', 'Interoperability analyst', 'Analista de interoperabilidad'),
        ('MEMBER-SERVICES', 'MEMBERSVC', None, 'Member services', 'Servicios al afiliado'),
        ('COMPLIANCE-OFFICER-PAYER', 'PAYERCOMPLIANCE', None, 'Compliance officer', 'Oficial de cumplimiento')], 'upa')


def build():
    eligibility()
    prior_auth()
    claim()
    gfe()
    discharge()
    access()
    breach()
    baa()
    payer_to_payer()
    provider_access()
    patient_access()
