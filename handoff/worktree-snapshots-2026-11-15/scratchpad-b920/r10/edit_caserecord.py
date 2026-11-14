import sys

path = sys.argv[1]
raw = open(path, 'rb').read().decode('utf-8')
crlf = '\r\n' in raw
t = raw.replace('\r\n', '\n')

R = []
R.append((''' *   <li>{@code LEGAL_HOLD} / {@code RELEASE_HOLD}: GP-40 court stay and R61 legal hold markers.</li>
 * </ul>
 */''', ''' *   <li>{@code LEGAL_HOLD} / {@code RELEASE_HOLD}: GP-40 court stay and R61 legal hold markers;</li>
 *   <li>{@code REVIEW} (decision 299, AU-03): after the record review task, writes {@code RECORD_REVIEWED} with the
 *       file, the decision {@code reviewDecision} (retain, transferNai or destroy; anything else is refused before
 *       the review routes, so no other value can fall through to destruction), the retention category, the legal
 *       hold and, when retained, the next review period {@code reReviewPeriod} (the next review date itself is the
 *       {@code SLA_STARTED} of the next review step);</li>
 *   <li>{@code NAI_TRANSFERRED} (decision 299, AU-03): writes {@code RECORD_TRANSFERRED_NAI} only for a transfer the
 *       core execution block settled as succeeded ({@code opStatus} COMPLETED: acknowledged by the connector, by the
 *       result message, or confirmed by a reconciliation resolution with evidence), in the transaction that settled
 *       it; anything else is refused, so a requested transfer is never recorded as made;</li>
 *   <li>{@code RTI_LINK} (decision 299, J-P2-E10): writes {@code RTI_REQUEST_LINKED} with the file and the RTI
 *       request reference {@code rtiRequestRef} of the {@code sangam.rtiLinked} message, before the legal hold the
 *       request places on the file.</li>
 * </ul>
 */'''))
R.append(('''            case "RELEASE_HOLD" -> {
                ex.setVariable("legalHold", false);
                Vars.audit(ex, "LEGAL_HOLD_RELEASED", Vars.map("order", Vars.str(ex, "releaseOrderRef")));
            }
''', '''            case "RELEASE_HOLD" -> {
                ex.setVariable("legalHold", false);
                Vars.audit(ex, "LEGAL_HOLD_RELEASED", Vars.map("order", Vars.str(ex, "releaseOrderRef")));
            }
            case "REVIEW" -> review(ex);
            case "NAI_TRANSFERRED" -> naiTransferred(ex);
            case "RTI_LINK" -> Vars.audit(ex, "RTI_REQUEST_LINKED", Vars.map("fileNo", Vars.str(ex, "fileNo"),
                    "rtiRequestRef", Vars.str(ex, "rtiRequestRef")));
'''))
R.append(('''    private void appeal(DelegateExecution ex) {''', '''    /** The decisions of the record review task (Public Records Rules 1997 r.9, records/review form). */
    static final List<String> REVIEW_DECISIONS = List.of("retain", "transferNai", "destroy");

    private void review(DelegateExecution ex) {
        String d = Vars.str(ex, "reviewDecision");
        if (d == null || !REVIEW_DECISIONS.contains(d)) {
            throw new ProcessEngineException("422 REVIEW_DECISION_REQUIRED: a record review decides one of "
                    + REVIEW_DECISIONS + ", not " + d);
        }
        Vars.audit(ex, "RECORD_REVIEWED", Vars.map("fileNo", Vars.str(ex, "fileNo"), "decision", d,
                "retentionCategory", Vars.str(ex, "retentionCategory"), "legalHold", Vars.bool(ex, "legalHold"),
                "nextReviewPeriod", "retain".equals(d) ? Vars.str(ex, "reReviewPeriod") : null));
    }

    private void naiTransferred(DelegateExecution ex) {
        String status = Vars.str(ex, Vars.OP_STATUS);
        if (!"COMPLETED".equals(status)) {
            throw new ProcessEngineException("NAI_TRANSFER_NOT_SETTLED: the National Archives transfer is " + status
                    + ", not settled as succeeded");
        }
        Vars.audit(ex, "RECORD_TRANSFERRED_NAI", Vars.map("fileNo", Vars.str(ex, "fileNo"),
                "retentionCategory", Vars.str(ex, "retentionCategory"), "connector", Vars.str(ex, "opConnector"),
                "naiRef", Vars.str(ex, "opExternalRef"), "resolutionEvidence", Vars.str(ex, "resolutionEvidence")));
    }

    private void appeal(DelegateExecution ex) {'''))

for old, new in R:
    n = t.count(old)
    if n != 1:
        raise SystemExit(f'marker found {n} times: {old[:80]!r}')
    t = t.replace(old, new)

if crlf:
    t = t.replace('\n', '\r\n')
open(path, 'wb').write(t.encode('utf-8'))
print('ok', path, 'crlf' if crlf else 'lf')
