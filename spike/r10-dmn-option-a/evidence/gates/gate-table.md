| Gate | Criterion | Mandatory | Source of the expected result | Expected | Operaton 2.2.0 + SangamFeel | Drools 10.1.0 |
|---|---|---|---|---|---|---|
| DEC-1 | exact decimals | yes | DMN 1.5 (formal/24-01-01) 10.3.2.3.1 decimal128; SG-33 | 0.3 | PASS | PASS |
| DEC-2 | exact decimals | yes | DMN 1.5 (formal/24-01-01) 10.3.2.3.1 (34 significant digits) | 0.3333333333333333333333333333333333 | PASS | PASS |
| DEC-3 | exact decimals | yes | SG-33 BigDecimal input unchanged | 3000.30 | PASS | PASS |
| DEC-4 | exact decimals | yes | DMN 1.5 (formal/24-01-01) 10.3.2.3.1 (19 digits exact) | 1234567890123456789 | PASS | PASS |
| DEC-5 | exact decimals | yes | DMN 1.5 (formal/24-01-01) 10.3.4.5 decimal(n, scale) rounds half even | [2, 4, 0.33] | PASS | PASS |
| DEC-6 | exact decimals | yes | SG-33 never Double | 2.5 | PASS | PASS |
| DAT-1 | dates | yes | SG-35 / OMG DMN13-35: '=' on date-time is the same instant | true | FAIL | PASS |
| DAT-2 | dates | yes | DMN 1.5 (formal/24-01-01) 10.3.2.3.5 date - date is a days-time duration | true | PASS | PASS |
| DAT-3 | dates | yes | DMN 1.5 (formal/24-01-01) 10.3.4.4 years and months duration example | true | PASS | PASS |
| DAT-4 | dates | yes | SG-34 invalid literal is refused (spec: null) | ERROR | PASS | PASS |
| DAT-5 | dates | yes | SG-34 requirement: invalid string into a built-in fails (Operaton residual documented) | ERROR | PASS | PASS |
| DAT-6 | dates | yes | DMN 1.5 (formal/24-01-01) 10.3.2.3.4 IANA zone id; 10:00 Asia/Kolkata = 04:30Z | true | PASS | PASS |
| NUL-1 | null and errors | yes | SG-34 missing variable fails | ERROR | PASS | PASS |
| NUL-2 | null and errors | yes | SG-34 null operand fails | ERROR | PASS | FAIL |
| NUL-3 | null and errors | yes | SG-34 a genuine null is a value; spec 10.3.2.2 | true | PASS | PASS |
| NUL-4 | null and errors | yes | SG-34 mistyped operand fails | ERROR | PASS | FAIL |
| NUL-5 | null and errors | yes | SG-34 division by zero fails | ERROR | PASS | PASS |
| NUL-6 | null and errors | yes | SG-34 unknown function fails (deploy or evaluation) | ERROR | PASS | PASS |
| NUL-7 | null and errors | yes | SG-34 SANGAM-DMN-NULL-INPUT: '-' never catches null | ERROR | PASS | FAIL |
| NUL-8 | null and errors | yes | SG-34 explicit null rule matches null input | n | PASS | PASS |
| HIT-1 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 UNIQUE: overlap is an error | ERROR | PASS | PASS |
| HIT-2 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 ANY: different outputs is an error | ERROR | PASS | PASS |
| HIT-3 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 ANY: equal outputs | a | PASS | PASS |
| HIT-4 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 FIRST | a | PASS | PASS |
| HIT-5 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 PRIORITY by output values order | high | PASS | PASS |
| HIT-6 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 OUTPUT ORDER | [high, low] | PASS | PASS |
| HIT-7 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 RULE ORDER | [low, high] | PASS | PASS |
| HIT-8 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 COLLECT SUM, exact | 0.3 | FAIL | PASS |
| HIT-9 | hit policies | yes | DMN 1.5 (formal/24-01-01) 8.2.11 COLLECT COUNT | 2 | PASS | PASS |
| GAP-1 | gaps | yes | DMN 1.5 (formal/24-01-01) 8.2.11 no matching rule, single hit: null | null | PASS | PASS |
| GAP-2 | gaps | yes | DMN 1.5 (formal/24-01-01) 8.3.2 default output entry when no rule matches | z | FAIL | PASS |
| SEC-1 | security | yes | OWASP XXE: a DOCTYPE / external entity is refused, no file content | ERROR | PASS | FAIL |
| SEC-2 | security | yes | Sangam D-46/R6: no in-engine code execution; external Java function refused | ERROR | PASS | FAIL |
| BOX-1 | DMN features | no (capability) | DMN 1.5 (formal/24-01-01) 6.3.3 BKM + 10.? invocation | 42 | FAIL | PASS |
| BOX-2 | DMN features | no (capability) | DMN 1.5 (formal/24-01-01) 10.2.1.4 boxed context | 6 | FAIL | PASS |
| BOX-3 | DMN features | no (capability) | DMN 1.5 (formal/24-01-01) 10.2.1.6 boxed relation | [{k=1}, {k=2}] | FAIL | PASS |
| BOX-4 | DMN features | no (capability) | DMN 1.5 (formal/24-01-01) 10.2.1.? boxed list / filter (FEEL) | [2, 3] | PASS | PASS |
