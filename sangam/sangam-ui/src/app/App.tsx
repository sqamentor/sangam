// SPDX-License-Identifier: Apache-2.0
import { lazy, Suspense, type ComponentType, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthProvider';
import type { Role } from '../api/types';
import { config } from '../config';
import { Layout } from './Layout';
import { NAV } from './nav';
import { AuthCallback, SignIn } from '../areas/auth/SignIn';
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
const hp = () => import('../areas/help/Help');

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
const Accessibility = named(hp, 'Accessibility');
const About = named(hp, 'About');

function Guard({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { hasRole } = useAuth();
  const { t } = useTranslation();
  if (!hasRole(...roles))
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
  const first = NAV.find((a) => hasRole(...a.roles));
  return <Navigate to={first?.items[0]?.to ?? '/help/about'} replace />;
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
  return (
    <Layout>
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/tasks" element={g(WORK, <TaskInbox />)} />
          <Route path="/tasks/:id" element={g(WORK, <TaskDetail />)} />
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
          <Route path="/ops/debugger" element={g([...OPS, ...MODEL], <Debugger />)} />
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

          <Route path="/help/accessibility" element={<Accessibility />} />
          <Route path="/help/about" element={<About />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Suspense>
    </Layout>
  );
}

function NotFound() {
  const { t } = useTranslation();
  return <PageHeader title={t('error.notAvailable')} />;
}

function Gate() {
  const { status } = useAuth();
  const loc = useLocation();
  if (loc.pathname === config().oidc.redirectPath) return <AuthCallback />;
  if (status === 'loading') return <Loading />;
  if (status !== 'authenticated') return <SignIn />;
  return <AuthedRoutes />;
}

export function App() {
  return (
    <BrowserRouter>
      <Gate />
    </BrowserRouter>
  );
}
