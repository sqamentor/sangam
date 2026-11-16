// SPDX-License-Identifier: Apache-2.0
/** GIGW footer pages: accessibility statement and about / licences (LIC-8 watermark statement). */
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { PageHeader, Section } from '../../components/ui';
import { useShortcuts } from '../../app/Layout';
import { GLOSSARY, GLOSSARY_TECHNICAL } from './glossary';

export function Accessibility() {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title={t('help.a11yTitle')} />
      <Section title={t('help.a11yStandard')}>
        <p>{t('help.a11yBody')}</p>
        <ul>
          <li>{t('help.a11yKeyboard')}</li>
          <li>{t('help.a11yScreenReader')}</li>
          <li>{t('help.a11yContrast')}</li>
          <li>{t('help.a11yLanguage')}</li>
          <li>{t('help.a11yDiagrams')}</li>
        </ul>
        <p>{t('help.a11yContact')}</p>
      </Section>
    </>
  );
}

export function About() {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title={t('help.aboutTitle')} />
      <Section title={t('app.name')}>
        <p>{t('help.aboutBody')}</p>
        <p>{t('help.watermark')}</p>
        <p>{t('help.fonts')}</p>
      </Section>
    </>
  );
}

/** UX-SPEC section 5: every plain word with its meaning and, in brackets, the technical term it replaces. */
export function Glossary() {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title={t('menu.help.glossary.label')} description={t('glossary.intro')} />
      <Section title={t('menu.help.glossary.label')}>
        <dl className="glossary">
          {GLOSSARY.map((id) => (
            <div key={id} id={`term-${id}`}>
              <dt>{t(`glossary.${id}.term`)}</dt>
              <dd>
                {t(`glossary.${id}.meaning`)}
                {GLOSSARY_TECHNICAL.has(id) && <span className="muted"> {t('glossary.technical', { term: t(`glossary.${id}.technical`) })}</span>}
              </dd>
            </div>
          ))}
        </dl>
      </Section>
    </>
  );
}

/** UX-SPEC 6.7: the shortcuts this caller can use, and nothing they cannot open. */
export function Shortcuts() {
  const { t } = useTranslation();
  const keys = useShortcuts();
  return (
    <>
      <PageHeader title={t('menu.help.shortcuts.label')} description={t('shortcuts.intro')} />
      <Section title={t('menu.help.shortcuts.label')}>
        {keys.length === 0 ? (
          <p>{t('shortcuts.none')}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th scope="col">{t('shortcuts.keys')}</th>
                <th scope="col">{t('shortcuts.goesTo')}</th>
              </tr>
            </thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.key}>
                  <td>
                    <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>{k.key}</kbd>
                  </td>
                  <td>
                    <Link to={k.to}>{t(k.label)}</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </>
  );
}
