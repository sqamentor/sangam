import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
def rep(old, new):
    global s
    assert s.count(old) == 1, old[:90]
    s = s.replace(old, new)
rep(""" *   <li>{@code LDG_ACCOUNTING}: accounting entries produced by the event mapping, for export to the GL connector.</li>
 * </ul>""", """ *   <li>{@code LDG_ACCOUNTING}: accounting entries produced by the event mapping, for export to the GL connector;</li>
 *   <li>{@code BILL_UNVERIFIED_PAYMENT} (sangam-billing, RM-03, owner decision 141): payments accepted without their
 *       channel's confirmation. One row per (tenant, channel, payment reference), the unique index refusing a second
 *       acceptance; the accepted columns are never updated, a resolution fills its own columns; {@code version} guards
 *       every transition; {@code receipt_no} is set when a VERIFIED payment is allocated.</li>
 * </ul>""")
rep("""            "CREATE INDEX LDG_ACCOUNTING_EXPORT ON LDG_ACCOUNTING (tenant_id, exported_at)");""",
"""            "CREATE INDEX LDG_ACCOUNTING_EXPORT ON LDG_ACCOUNTING (tenant_id, exported_at)",
            \"\"\"
            CREATE TABLE BILL_UNVERIFIED_PAYMENT (
              tenant_id    {str64} NOT NULL,
              payment_id   {str64} NOT NULL,
              ledger_id    {str64} NOT NULL,
              consumer     {str} NOT NULL,
              office_id    {str64} NOT NULL,
              channel      {str64} NOT NULL,
              payment_ref  {str} NOT NULL,
              amount       {money} NOT NULL,
              currency     {str64} NOT NULL,
              paid_at      {ts} NOT NULL,
              payer_name   {str},
              instrument   {str64},
              details_json {text} NOT NULL,
              reason       {text} NOT NULL,
              evidence_ref {str} NOT NULL,
              policy_version {str64} NOT NULL,
              downstream_json {text} NOT NULL,
              accepted_by  {str} NOT NULL,
              accepted_at  {ts} NOT NULL,
              state        {str64} NOT NULL,
              version      {long} NOT NULL,
              outcome_reason {text},
              verification_ref {str},
              resolved_by  {str},
              resolved_at  {ts},
              receipt_no   {str64},
              PRIMARY KEY (tenant_id, payment_id)
            )\"\"\",
            "CREATE UNIQUE INDEX BILL_UNVERIFIED_REF ON BILL_UNVERIFIED_PAYMENT (tenant_id, channel, payment_ref)",
            "CREATE INDEX BILL_UNVERIFIED_STATE ON BILL_UNVERIFIED_PAYMENT (tenant_id, state, accepted_at)");""")
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
