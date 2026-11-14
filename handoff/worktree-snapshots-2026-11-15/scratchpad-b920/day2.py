import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src')
p='modules/registryNumbering.test.ts'
sub(p, """    expect(body).toEqual({ seriesKey: 'TL', pattern: '{prefix}/{fy}/{seq:6}', reset: 'FISCAL_YEAR', fiscalStartMonth: '7' });""",
"""    // the contract types fiscalStartMonth as an integer (217C21BE); the select's text is sent as that integer
    expect(body).toEqual({ seriesKey: 'TL', pattern: '{prefix}/{fy}/{seq:6}', reset: 'FISCAL_YEAR', fiscalStartMonth: 7 });""")
sub(p, """  it('has a label for every option and field in both languages', () => {""", """  it('owner X1-03b: offers the optional fiscalStartDay (1..28) and sends it as an integer only when given', () => {
    const day = numberSeriesFields().find((x) => x.key === 'fiscalStartDay');
    expect(day).toMatchObject({ type: 'number', validate: { min: 1, max: 28 } });
    expect(day?.validate?.required).toBeUndefined();
    const withDay = buildRequest(numberSeriesFields(), { seriesKey: 'TL', pattern: '{fy}/{seq}', fiscalStartMonth: '4', fiscalStartDay: 6 }, '').body;
    expect(withDay).toEqual({ seriesKey: 'TL', pattern: '{fy}/{seq}', fiscalStartMonth: 4, fiscalStartDay: 6 });
    expect(buildRequest(numberSeriesFields(), { seriesKey: 'TL', pattern: '{seq}' }, '').body).not.toHaveProperty('fiscalStartDay');
  });

  // Q-06 / SL-147 (owner-accepted 2026-11-12): labels added after the Hindi freeze are English-only; every other label
  // still needs its Hindi string.
  const ENGLISH_ONLY = new Set(['fld.fiscalStartDay', 'fld.fiscalStartDayHint']);
  it('has a label for every option and field in English, and in Hindi for every label from before the Q-06 freeze', () => {""")
sub(p, """      expect(typeof lookup(hi as Tree, key), `hi ${key}`).toBe('string');""", """      if (!ENGLISH_ONLY.has(key)) expect(typeof lookup(hi as Tree, key), `hi ${key}`).toBe('string');""")
p='modules/programmeConfig.test.ts'
sub(p, """    // ProgrammeConfig.java:228 reads a number or a numeric string
    expect(body).toEqual({ fiscalYearStartMonth: '4', indicators: [{ key: 'km' }], programmeKinds: [{ kind: 'SCHEME' }], units: ['km'] });""", """    // sent as the integer the contract types (ProgrammePublishConfigBody, 217C21BE)
    expect(body).toEqual({ fiscalYearStartMonth: 4, indicators: [{ key: 'km' }], programmeKinds: [{ kind: 'SCHEME' }], units: ['km'] });
  });

  it('owner X1-03b: offers the optional fiscalStartDay (1..28, server default 1) and sends it only when given', () => {
    const day = programmeConfigFields().find((x) => x.key === 'fiscalStartDay');
    expect(day).toMatchObject({ type: 'number', validate: { min: 1, max: 28 } });
    expect(day?.validate?.required).toBeUndefined();
    expect(buildRequest(programmeConfigFields(), { fiscalYearStartMonth: '4', fiscalStartDay: 16, indicators: '[]' }, '').body).toEqual({ fiscalYearStartMonth: 4, fiscalStartDay: 16, indicators: [] });
    expect(buildRequest(programmeConfigFields(), { fiscalYearStartMonth: '1', indicators: '[]' }, '').body).not.toHaveProperty('fiscalStartDay');
    expect(validateForm({ components: programmeConfigFields() }, { fiscalYearStartMonth: '4', fiscalStartDay: 29, indicators: '[]' }, t).fiscalStartDay).toBe('form.max');""")
