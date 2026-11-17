// SPDX-License-Identifier: Apache-2.0
import { lazy, Suspense, type ComponentType, type ReactNode } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthProvider';
import type { Role } from '../api/types';
import { config } from '../config';
import { Layout } from './Layout';
import { NAV } from './nav';
import { ROUTE_ACCESS, TEAM_READ, canAccess, type Access } from './access';
import { AuthCallback, SignIn } from '../areas/auth/SignIn';
import { IdleSession } from '../auth/IdleSession';
import { Loading, Notice, PageHeader } from '../components/ui';

/** Lazy-load a named export so heavy areas (bpmn-js, dmn-js, form-js) are separate chunks. */
function named<T>(loader: () => Promise<T>, name: keyof T) {
  return lazy(async () => ({ default: (await loader())[name] as unknown as ComponentType }));
}

const tl = () => import('../areas/tasklist/TaskInbox');
const td = () => import('../areas/tasklist/TaskDetail');
const sc = () => import('../areas/tasklist/StartAndCases');
const mw = () => import('../areas/tasklist/MyWork');
const od = () => import('../areas/ops/OpsDashboard');
const pr = () => import('../areas/ops/Processes');
const ins = () => import('../areas/ops/InstanceDetail');
const ir = () => import('../areas/ops/IncidentsRecon');
const bm = () => import('../areas/ops/BatchMigration');
const om = () => import('../areas/ops/OpsMisc');
const ad = () => import('../areas/admin/Admin');
const md = () => import('../areas/modeller/Modeller');
const dp = () => import('../areas/modeller/DataAndPages');
const an = () => import('../areas/analytics/Analytics');
const au = () => import('../areas/audit/Audit');
const po = () => import('../areas/portal/Portal');
const pv = () => import('../areas/portal/PortalPrivacy');
const hp = () => import('../areas/help/Help');
const mReg = () => import('../modules/registry');
const mRes = () => import('../modules/resources');
const mCase = () => import('../modules/casework');
const mFin = () => import('../modules/finance');
const mProg = () => import('../modules/programme');
const mFG = () => import('../modules/fieldgeo');
const mComp = () => import('../modules/compliance');
const mCfg = () => import('../modules/config');
const mCompat = () => import('../modules/compat');
const mOp = () => import('../modules/operaton');
const mBulk = () => import('../modules/bulk');
const RegistryPage = named(mReg, 'RegistryPage');
const PublicVerify = named(mReg, 'PublicVerify');
const ResourcesPage = named(mRes, 'ResourcesPage');
const QueuesPage = named(mRes, 'QueuesPage');
const QueueDisplay = named(mRes, 'QueueDisplay');
const RostersPage = named(mRes, 'RostersPage');
const CaseFilesPage = named(mCase, 'CaseFilesPage');
const CommitteesPage = named(mCase, 'CommitteesPage');
const ReferencesPage = named(mCase, 'ReferencesPage');
const LedgerPage = named(mFin, 'LedgerPage');
const BillingPage = named(mFin, 'BillingPage');
const ProgrammesPage = named(mProg, 'ProgrammesPage');
const FieldPage = named(mFG, 'FieldPage');
const GeoPage = named(mFG, 'GeoPage');
const CompliancePage = named(mComp, 'CompliancePage');
const ConfigPage = named(mCfg, 'ConfigPage');
const OnboardingWizard = named(mCfg, 'OnboardingWizard');
const PackLifecycle = named(mCfg, 'PackLifecycle');
const CustomFieldsPage = named(mCfg, 'CustomFieldsPage');
const BulkIngestPage = named(mBulk, 'BulkIngestPage');
const CompatibilityMatrix = named(mCompat, 'CompatibilityMatrix');
const OperatonOpsPage = named(mOp, 'OperatonOpsPage');
const TaskFiltersPage = named(mOp, 'TaskFiltersPage');
const EngineAdminPage = named(mOp, 'EngineAdminPage');
const Welcome = named(mOp, 'Welcome');
const Account = named(mOp, 'Account');
const mPl = () => import('../modules/platform');
const AiPage = named(mPl, 'AiPage');
const ChannelsPage = named(mPl, 'ChannelsPage');
const ModellerToolsPage = named(mPl, 'ModellerToolsPage');
const OpsToolsPage = named(mPl, 'OpsToolsPage');
const mOp2 = () => import('../modules/operaton2');
const DrdPage = named(mOp2, 'DrdPage');
const PluginsPage = named(mOp2, 'PluginsPage');
const pl = () => import('../plugins/host');
const PluginPage = named(pl, 'PluginPage');
const TeamWorkload = named(() => import('../areas/tasklist/TeamWorkload'), 'TeamWorkload');

const TaskInbox = named(tl, 'TaskInbox');
const TaskDetail = named(td, 'TaskDetail');
const StartCatalog = named(sc, 'StartCatalog');
const CaseList = named(sc, 'CaseList');
const CaseWorkspace = named(sc, 'CaseWorkspace');
const MyDelegations = named(mw, 'MyDelegations');
const Notifications = named(mw, 'Notifications');
const FieldVisits = named(mw, 'FieldVisits');
const OpsDashboard = named(od, 'OpsDashboard');
const ProcessDefinitions = named(pr, 'ProcessDefinitions');
const DefinitionDetail = named(pr, 'DefinitionDetail');
const InstanceDetail = named(ins, 'InstanceDetail');
const Incidents = named(ir, 'Incidents');
const IncidentDetail = named(ir, 'IncidentDetail');
const Reconciliation = named(ir, 'Reconciliation');
const ReconciliationDetail = named(ir, 'ReconciliationDetail');
const Batches = named(bm, 'Batches');
const BatchDetail = named(bm, 'BatchDetail');
const Migration = named(bm, 'Migration');
const OpsCases = named(om, 'OpsCases');
const Decisions = named(om, 'Decisions');
const Jobs = named(om, 'Jobs');
const OutboxInbox = named(om, 'OutboxInbox');
const Workers = named(om, 'Workers');
const EventRegistry = named(om, 'EventRegistry');
const NotificationFailures = named(om, 'NotificationFailures');
const Deployments = named(om, 'Deployments');
const Cleanup = named(om, 'Cleanup');
const Debugger = named(om, 'Debugger');
const OperatorAudit = named(om, 'OperatorAudit');
const Tenants = named(ad, 'Tenants');
const OrgHierarchy = named(ad, 'OrgHierarchy');
const Seats = named(ad, 'Seats');
const Assignments = named(ad, 'Assignments');
const DelegationTables = named(ad, 'DelegationTables');
const DelegationTableEditor = named(ad, 'DelegationTableEditor');
const Policies = named(ad, 'Policies');
const SodExceptions = named(ad, 'SodExceptions');
const Users = named(ad, 'Users');
const Groups = named(ad, 'Groups');
const IdentityProviders = named(ad, 'IdentityProviders');
const ServicePrincipals = named(ad, 'ServicePrincipals');
const ServiceRegistry = named(ad, 'ServiceRegistry');
const SlaModels = named(ad, 'SlaModels');
const RoutingStrategies = named(ad, 'RoutingStrategies');
const Templates = named(ad, 'Templates');
const ConversationalChannels = named(ad, 'ConversationalChannels');
const AgentDefinitions = named(ad, 'AgentDefinitions');
const RetentionClasses = named(ad, 'RetentionClasses');
const ModuleSecurityDefaults = named(ad, 'ModuleSecurityDefaults');
const dc = () => import('../areas/dsc/DscCertificates');
const MyDscCertificates = named(dc, 'MyDscCertificates');
const DscCertificateQueue = named(dc, 'DscCertificateQueue');
const Packages = named(md, 'Packages');
const PackageWorkspace = named(md, 'PackageWorkspace');
const ReviewQueue = named(md, 'ReviewQueue');
const ImportAssessment = named(md, 'ImportAssessment');
const DataObjects = named(dp, 'DataObjects');
const PageBuilder = named(dp, 'PageBuilder');
const PageRuntime = named(dp, 'PageRuntime');
const AnalyticsDashboard = named(an, 'AnalyticsDashboard');
const ProcessMining = named(an, 'ProcessMining');
const Reports = named(an, 'Reports');
const Alerts = named(an, 'Alerts');
const AgentUsage = named(an, 'AgentUsage');
const Timeline = named(au, 'Timeline');
const AuditSearch = named(au, 'AuditSearch');
const AuditExports = named(au, 'AuditExports');
const Documents = named(au, 'Documents');
const PortalHome = named(po, 'PortalHome');
const PortalApply = named(po, 'PortalApply');
const PortalApplications = named(po, 'PortalApplications');
const PortalApplication = named(po, 'PortalApplication');
const PortalGrievances = named(po, 'PortalGrievances');
const PortalConsents = named(pv, 'PortalConsents');
const PortalNotices = named(pv, 'PortalNotices');
const PublicPrivacy = named(po, 'PublicPrivacy');
const Accessibility = named(hp, 'Accessibility');
const Glossary = named(hp, 'Glossary');
const Shortcuts = named(hp, 'Shortcuts');
const About = named(hp, 'About');

/** Role guard; with `access`, a caller holding one of its endpoint permissions passes too (M-028, access.ts). */
function Guard({ roles, access, children }: { roles?: Role[]; access?: Access; children: ReactNode }) {
  const { hasRole, me } = useAuth();
  const { t } = useTranslation();
  if (!(access ? canAccess(me, access) : hasRole(...(roles ?? []))))
    return (
      <>
        <PageHeader title={t('error.notAvailable')} />
        <Notice>{t('error.notAvailableHint')}</Notice>
      </>
    );
  return <>{children}</>;
}

function Home() {
  const { hasRole } = useAuth();
  // Help is open to everyone; a caller with no job group at all lands on About.
  const first = NAV.find((a) => a.roles.length > 0 && hasRole(...a.roles));
  return <Navigate to={first ? '/welcome' : '/help/about'} replace />;
}

const WORK: Role[] = ['TASK_USER'];
const OPS: Role[] = ['OPERATOR', 'PLATFORM_OPERATOR'];
const ADMIN: Role[] = ['ADMIN', 'PLATFORM_ADMIN'];
const MODEL: Role[] = ['MODELLER', 'REVIEWER'];
const ANALYST: Role[] = ['ANALYST', 'OPERATOR'];
const AUDIT: Role[] = ['AUDITOR', 'OPERATOR', 'TASK_USER'];
const CITIZEN: Role[] = ['CITIZEN'];

function AuthedRoutes() {
  const g = (roles: Role[], el: ReactNode) => <Guard roles={roles}>{el}</Guard>;
  const m = (path: keyof typeof ROUTE_ACCESS, el: ReactNode) => <Route path={path} element={<Guard access={ROUTE_ACCESS[path]}>{el}</Guard>} />;
  return (
    <Layout>
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/tasks" element={g(WORK, <TaskInbox />)} />
          {/* the menu calls the task list Inbox (nav.inbox): /inbox is the same screen */}
          <Route path="/inbox" element={<Navigate to="/tasks" replace />} />
          <Route path="/tasks/filters" element={g(['PLATFORM_OPERATOR', 'PLATFORM_ADMIN'], <TaskFiltersPage />)} />
          <Route path="/tasks/:id" element={g(WORK, <TaskDetail />)} />
          <Route path="/welcome" element={<Welcome />} />
          {m('/m/ai', <AiPage />)}
          {m('/m/channels', <ChannelsPage />)}
          <Route path="/modeller/tools" element={g(MODEL, <ModellerToolsPage />)} />
          <Route path="/ops/tools" element={g(OPS, <OpsToolsPage />)} />
          <Route path="/ops/drd/:id" element={g(['PLATFORM_OPERATOR'], <DrdPage />)} />
          <Route path="/admin/plugins" element={g(['PLATFORM_ADMIN'], <PluginsPage />)} />
          <Route path="/x/:pluginId/:id" element={<PluginPage />} />
          <Route path="/account" element={<Account />} />
          <Route path="/ops/engine" element={g(['PLATFORM_OPERATOR'], <OperatonOpsPage />)} />
          {/* decision 397: route words match the menu labels; the old addresses redirect */}
          {/* decision 401: the team report is a Sangam read limited to the caller's organisation (team.read) */}
          <Route path="/team" element={<Guard access={TEAM_READ}>{<TeamWorkload />}</Guard>} />
          <Route path="/ops/task-dashboard" element={<Navigate to="/team" replace />} />
          <Route path="/admin/engine" element={g(['PLATFORM_ADMIN'], <EngineAdminPage />)} />
          <Route path="/admin/config" element={g(ADMIN, <ConfigPage />)} />
          {m('/admin/onboarding', <OnboardingWizard />)}
          {m('/admin/packs', <PackLifecycle />)}
          {m('/admin/custom-fields', <CustomFieldsPage />)}
          {m('/admin/bulk', <BulkIngestPage />)}
          {m('/admin/security-defaults', <ModuleSecurityDefaults />)}
          {m('/admin/dsc-certificates', <DscCertificateQueue />)}
          {m('/my/dsc-certificates', <MyDscCertificates />)}
          <Route path="/privacy/:tenant?" element={<PublicPrivacy />} />
          <Route path="/modeller/compatibility" element={g(MODEL, <CompatibilityMatrix />)} />
          {m('/m/registry', <RegistryPage />)}
          {m('/m/resources', <ResourcesPage />)}
          {m('/m/queues', <QueuesPage />)}
          {m('/m/rosters', <RostersPage />)}
          {m('/m/case-files', <CaseFilesPage />)}
          {m('/m/committees', <CommitteesPage />)}
          {m('/m/references', <ReferencesPage />)}
          {m('/m/ledger', <LedgerPage />)}
          {m('/m/billing', <BillingPage />)}
          {m('/m/programmes', <ProgrammesPage />)}
          {m('/m/field', <FieldPage />)}
          {m('/m/geo', <GeoPage />)}
          {m('/m/compliance', <CompliancePage />)}
          <Route path="/start" element={g(WORK, <StartCatalog />)} />
          <Route path="/cases" element={g(WORK, <CaseList />)} />
          <Route path="/cases/:id" element={g([...WORK, ...OPS], <CaseWorkspace />)} />
          <Route path="/my/delegations" element={g(WORK, <MyDelegations />)} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="/field" element={g(WORK, <FieldVisits />)} />

          <Route path="/ops" element={g(OPS, <OpsDashboard />)} />
          <Route path="/ops/processes" element={g(OPS, <ProcessDefinitions />)} />
          <Route path="/ops/processes/:id" element={g(OPS, <DefinitionDetail />)} />
          <Route path="/ops/instances/:id" element={g(OPS, <InstanceDetail />)} />
          <Route path="/ops/cases" element={g(OPS, <OpsCases />)} />
          <Route path="/ops/decisions" element={g(OPS, <Decisions />)} />
          <Route path="/ops/incidents" element={g(OPS, <Incidents />)} />
          <Route path="/ops/incidents/:id" element={g(OPS, <IncidentDetail />)} />
          <Route path="/ops/reconciliation" element={g(OPS, <Reconciliation />)} />
          <Route path="/ops/reconciliation/:id" element={g(OPS, <ReconciliationDetail />)} />
          <Route path="/ops/batches" element={g(OPS, <Batches />)} />
          <Route path="/ops/batches/:id" element={g(OPS, <BatchDetail />)} />
          <Route path="/ops/migration" element={g(OPS, <Migration />)} />
          <Route path="/ops/jobs" element={g(OPS, <Jobs />)} />
          <Route path="/ops/outbox" element={g(OPS, <OutboxInbox />)} />
          <Route path="/ops/workers" element={g([...OPS, 'INTEGRATION'], <Workers />)} />
          <Route path="/ops/events" element={g([...OPS, 'INTEGRATION'], <EventRegistry />)} />
          <Route path="/ops/notifications" element={g(OPS, <NotificationFailures />)} />
          <Route path="/ops/deployments" element={g([...OPS, ...MODEL], <Deployments />)} />
          <Route path="/ops/cleanup" element={g(OPS, <Cleanup />)} />
          <Route path="/design/test" element={g([...OPS, ...MODEL], <Debugger />)} />
          <Route path="/ops/debugger" element={<Navigate to="/design/test" replace />} />
          <Route path="/ops/audit-log" element={g([...OPS, 'AUDITOR'], <OperatorAudit />)} />

          <Route path="/admin/tenants" element={g(ADMIN, <Tenants />)} />
          <Route path="/admin/org" element={g(ADMIN, <OrgHierarchy />)} />
          <Route path="/admin/seats" element={g(ADMIN, <Seats />)} />
          <Route path="/admin/assignments" element={g(ADMIN, <Assignments />)} />
          <Route path="/admin/delegations" element={g(ADMIN, <DelegationTables />)} />
          <Route path="/admin/delegations/:id" element={g(ADMIN, <DelegationTableEditor />)} />
          <Route path="/admin/policies" element={g(ADMIN, <Policies />)} />
          <Route path="/admin/sod" element={g(ADMIN, <SodExceptions />)} />
          <Route path="/admin/users" element={g(ADMIN, <Users />)} />
          <Route path="/admin/groups" element={g(ADMIN, <Groups />)} />
          <Route path="/admin/idp" element={g(ADMIN, <IdentityProviders />)} />
          <Route path="/admin/principals" element={g([...ADMIN, 'INTEGRATION'], <ServicePrincipals />)} />
          <Route path="/admin/services" element={g([...ADMIN, 'INTEGRATION'], <ServiceRegistry />)} />
          <Route path="/admin/sla" element={g(ADMIN, <SlaModels />)} />
          <Route path="/admin/routing" element={g(ADMIN, <RoutingStrategies />)} />
          <Route path="/admin/templates" element={g(ADMIN, <Templates />)} />
          <Route path="/admin/channels" element={g(ADMIN, <ConversationalChannels />)} />
          <Route path="/admin/agents" element={g(ADMIN, <AgentDefinitions />)} />
          <Route path="/admin/retention" element={g([...ADMIN, 'AUDITOR'], <RetentionClasses />)} />

          <Route path="/modeller" element={g(MODEL, <Packages />)} />
          <Route path="/modeller/review" element={g(MODEL, <ReviewQueue />)} />
          <Route path="/modeller/data-objects" element={g(MODEL, <DataObjects />)} />
          <Route path="/modeller/pages" element={g(MODEL, <PageBuilder />)} />
          <Route path="/modeller/assess" element={g(MODEL, <ImportAssessment />)} />
          <Route path="/modeller/:id" element={g(MODEL, <PackageWorkspace />)} />
          <Route path="/pages/:key" element={<PageRuntime />} />

          <Route path="/analytics" element={g(ANALYST, <AnalyticsDashboard />)} />
          <Route path="/analytics/mining" element={g(ANALYST, <ProcessMining />)} />
          <Route path="/analytics/reports" element={g(ANALYST, <Reports />)} />
          <Route path="/analytics/alerts" element={g(ANALYST, <Alerts />)} />
          <Route path="/analytics/agents" element={g(ANALYST, <AgentUsage />)} />

          <Route path="/audit" element={g(AUDIT, <AuditSearch />)} />
          <Route path="/audit/timeline/:businessKey" element={g(AUDIT, <Timeline />)} />
          <Route path="/audit/exports" element={g(AUDIT, <AuditExports />)} />
          <Route path="/documents" element={g(AUDIT, <Documents />)} />

          <Route path="/portal" element={g(CITIZEN, <PortalHome />)} />
          <Route path="/portal/apply/:serviceKey" element={g(CITIZEN, <PortalApply />)} />
          <Route path="/portal/applications" element={g(CITIZEN, <PortalApplications />)} />
          <Route path="/portal/applications/:id" element={g(CITIZEN, <PortalApplication />)} />
          <Route path="/portal/grievances" element={g(CITIZEN, <PortalGrievances />)} />
          <Route path="/portal/consents" element={g(CITIZEN, <PortalConsents />)} />
          <Route path="/portal/notices" element={g(CITIZEN, <PortalNotices />)} />

          <Route path="/help/accessibility" element={<Accessibility />} />
          <Route path="/help/about" element={<About />} />
          <Route path="/help/glossary" element={<Glossary />} />
          <Route path="/help/shortcuts" element={<Shortcuts />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Suspense>
    </Layout>
  );
}

/** An address no screen answers: said as such, never as a missing permission (error.notAvailable is for a guarded screen). */
function NotFound() {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title={t('error.pageNotFound')} />
      <Notice>
        {t('error.pageNotFoundHint')} <Link to="/">{t('error.goHome')}</Link>
      </Notice>
    </>
  );
}

function Gate() {
  const { status } = useAuth();
  const loc = useLocation();
  if (loc.pathname === config().oidc.redirectPath) return <AuthCallback />;
  // Public page: certificate verification (QR).
  if (loc.pathname.startsWith('/verify')) return <Suspense fallback={<Loading />}><Routes><Route path="/verify/:tenant?" element={<PublicVerify />} /></Routes></Suspense>;
  if (status === 'loading') return <Loading />;
  // M-033: the privacy policy is public (SecurityConfig.java:80-81); signed-in callers see it inside the app chrome.
  if (status !== 'authenticated' && loc.pathname.startsWith('/privacy')) return <Suspense fallback={<Loading />}><Routes><Route path="/privacy/:tenant?" element={<PublicPrivacy />} /></Routes></Suspense>;
  if (status !== 'authenticated') return <SignIn />;
  // Hall display: signed-in kiosk account, no navigation chrome. Nobody gives input to a hall display, so the idle
  // timeout (decision 349) is not applied on this route; it applies again on every other page.
  if (loc.pathname.startsWith('/display/queue/')) return <Suspense fallback={<Loading />}><Routes><Route path="/display/queue/:servicePointId" element={<QueueDisplay />} /></Routes></Suspense>;
  return (
    <>
      <IdleSession />
      <AuthedRoutes />
    </>
  );
}

export function App() {
  return (
    <BrowserRouter>
      <Gate />
    </BrowserRouter>
  );
}
