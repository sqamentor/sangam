import sys, io

p = sys.argv[1]
raw = open(p, 'rb').read().decode('utf-8')
crlf = '\r\n' in raw
t = raw.replace('\r\n', '\n')

old_metrics = """metrics:
  serviceMonitor:
    enabled: false
    interval: 30s
    labels: {}
"""
new_metrics = """metrics:
  serviceMonitor:
    enabled: false
    interval: 30s
    labels: {}
  # M-091: PrometheusRule (Prometheus Operator CRD) with the Sangam alert rules. The same rules, with these defaults,
  # are in deploy/compose/observability/rules/sangam-alerts.yml (PrometheusAlertRulesTraceTest keeps them equal).
  prometheusRule:
    enabled: false
    # Extra labels, e.g. the ruleSelector of the Prometheus instance (release: kube-prometheus-stack).
    labels: {}
    thresholds:
      # Oldest undelivered outbox event (sangam_outbox_oldest_age_seconds): relay stalled, destination down or an
      # ordered delivery PARKED (VI.2.3; PARKED rows count as undelivered).
      outboxOldestAgeSeconds: 300
      # Worst root-lock wait per process definition (sangam_engine_root_lock_wait_seconds_max): half of the 2 s
      # lock_timeout (R3, VI.1 hot roots).
      rootLockWaitSeconds: 1
      # Open Operaton incidents (sangam_incidents_open) above this count.
      incidentsOpen: 0
      # OPEN reconciliation items (sangam_reconciliation_items{state="OPEN"}) above this count.
      reconciliationOpen: 0
      # Days before a cert-manager Certificate in the release namespace expires.
      certExpiryDays: 14
    # Certificate expiry from cert-manager's certmanager_certificate_expiration_timestamp_seconds (deploy README:
    # TLS is renewed by cert-manager). Without cert-manager the rule has no series and never fires.
    certManager:
      enabled: true
"""
assert t.count(old_metrics) == 1, 'metrics block'
t = t.replace(old_metrics, new_metrics)

old_ingress = """ingress:
  enabled: false
  className: ""
  annotations: {}
  host: sangam.example.gov.in
  tls:
    enabled: true
    secretName: ""
  # UI at /, API at /api and /engine-rest (server). With ui.enabled=false everything goes to the server.
"""
new_ingress = """ingress:
  enabled: false
  # The public ingress class. With private.enabled=true it must stay empty: the schema and the template refuse it.
  className: ""
  annotations: {}
  host: sangam.example.gov.in
  tls:
    enabled: true
    secretName: ""
  # UI at /, API at /api and /engine-rest (server). With ui.enabled=false everything goes to the server.
  # SG-39 (owner decision Q-09): private ingress. With private.enabled=true (and enabled=true) the Ingress uses the
  # internal class and the internal-load-balancer annotations of the chosen preset; ingress.className is refused.
  private:
    enabled: false
    # Internal IngressClass. Empty = the preset's className. Required with preset "" (e.g. an internal nginx class).
    className: ""
    # "" (no cloud preset), aws, azure or gcp.
    preset: ""
    # Schema-validated: each preset's internal-load-balancer annotation is fixed to its internal value, and the
    # preset annotations replace the same keys in ingress.annotations.
    presets:
      aws:
        # AWS Load Balancer Controller: an internal ALB (scheme internal is its default; set explicitly).
        className: alb
        annotations:
          alb.ingress.kubernetes.io/scheme: internal
      azure:
        # Application Gateway Ingress Controller: listen on the gateway's private IP.
        className: azure-application-gateway
        annotations:
          appgw.ingress.kubernetes.io/use-private-ip: "true"
      gcp:
        # GKE Ingress takes its class only from this annotation (not spec.ingressClassName); gce-internal is the
        # internal Application Load Balancer. An Ingress without it is external.
        className: ""
        annotations:
          kubernetes.io/ingress.class: gce-internal
"""
assert t.count(old_ingress) == 1, 'ingress block'
t = t.replace(old_ingress, new_ingress)

if crlf:
    t = t.replace('\n', '\r\n')
open(p, 'wb').write(t.encode('utf-8'))
print('ok crlf=%s' % crlf)
