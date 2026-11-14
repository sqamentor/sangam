import sys
sys.path.insert(0, sys.argv[1])
import packs
packs.check_reason_codes('x', '<operaton:string>rejectionReasonCodes=FOO,EXECUTION_FAILED;a=b</operaton:string> {"rejectionReasonCodes": "BAR"} rejectionReasonCodes=@eligibilityReasonCode<x rejectionReasonCodes=@zzz<')
print(sorted(packs.MISSING))
