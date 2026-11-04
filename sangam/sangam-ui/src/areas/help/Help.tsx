// SPDX-License-Identifier: Apache-2.0
/** GIGW footer pages: accessibility statement and about / licences (LIC-8 watermark statement). */
import { useTranslation } from 'react-i18next';
import { PageHeader, Section } from '../../components/ui';

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
