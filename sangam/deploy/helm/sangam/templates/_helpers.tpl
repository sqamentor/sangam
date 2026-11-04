{{/* SPDX-License-Identifier: Apache-2.0 */}}

{{- define "sangam.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sangam.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "sangam.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sangam.labels" -}}
helm.sh/chart: {{ include "sangam.chart" . }}
app.kubernetes.io/name: {{ include "sangam.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: sangam
{{- with .Values.global.labels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/* selector labels: call with (dict "ctx" $ "component" "server") */}}
{{- define "sangam.selectorLabels" -}}
app.kubernetes.io/name: {{ include "sangam.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "sangam.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "sangam.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* image reference: call with (dict "ctx" $ "image" .Values.server.image) */}}
{{- define "sangam.image" -}}
{{- $registry := default .image.registry .ctx.Values.global.imageRegistry -}}
{{- $tag := default .ctx.Chart.AppVersion .image.tag -}}
{{- $ref := ternary (printf "%s/%s" $registry .image.repository) .image.repository (ne $registry "") -}}
{{- if .image.digest -}}
{{- printf "%s@%s" $ref .image.digest -}}
{{- else -}}
{{- printf "%s:%s" $ref $tag -}}
{{- end -}}
{{- end -}}

{{/* plain third-party image (backup tools) with the global registry prefix for air-gapped installs */}}
{{- define "sangam.toolImage" -}}
{{- $img := .image -}}
{{- if and .ctx.Values.global.imageRegistry (not (hasPrefix .ctx.Values.global.imageRegistry $img)) -}}
{{- printf "%s/%s" .ctx.Values.global.imageRegistry $img -}}
{{- else -}}
{{- $img -}}
{{- end -}}
{{- end -}}

{{- define "sangam.imagePullSecrets" -}}
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
{{- range . }}
  - name: {{ . }}
{{- end }}
{{- end }}
{{- end -}}

{{/* ------------------------------------------------------------------ database */}}

{{- define "sangam.db.type" -}}
{{- $t := .Values.database.type -}}
{{- if not (has $t (list "postgres" "oracle" "sqlserver" "mysql")) -}}
{{- fail (printf "database.type must be one of postgres, oracle, sqlserver, mysql (got %q)" $t) -}}
{{- end -}}
{{- $t -}}
{{- end -}}

{{- define "sangam.db.port" -}}
{{- $defaults := dict "postgres" 5432 "oracle" 1521 "sqlserver" 1433 "mysql" 3306 -}}
{{- if .Values.database.port -}}
{{- .Values.database.port -}}
{{- else -}}
{{- get $defaults (include "sangam.db.type" .) -}}
{{- end -}}
{{- end -}}

{{- define "sangam.db.jdbcUrl" -}}
{{- $d := .Values.database -}}
{{- if $d.jdbcUrl -}}
{{- $d.jdbcUrl -}}
{{- else -}}
{{- $host := required "database.host (or database.jdbcUrl) is required" $d.host -}}
{{- $port := include "sangam.db.port" . -}}
{{- $type := include "sangam.db.type" . -}}
{{- if eq $type "postgres" -}}
{{- printf "jdbc:postgresql://%s:%s/%s?ApplicationName=sangam-server%s" $host $port $d.name (ternary (printf "&%s" $d.params) "" (ne $d.params "")) -}}
{{- else if eq $type "oracle" -}}
{{- printf "jdbc:oracle:thin:@//%s:%s/%s%s" $host $port $d.name (ternary (printf "?%s" $d.params) "" (ne $d.params "")) -}}
{{- else if eq $type "sqlserver" -}}
{{- printf "jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=%t;trustServerCertificate=%t;sendTimeAsDatetime=false;applicationName=sangam-server%s" $host $port $d.name $d.sqlserver.encrypt $d.sqlserver.trustServerCertificate (ternary (printf ";%s" $d.params) "" (ne $d.params "")) -}}
{{- else -}}
{{- printf "jdbc:mariadb://%s:%s/%s?permitMysqlScheme=true&useServerPrepStmts=true&sessionVariables=transaction_isolation='READ-COMMITTED',time_zone='+00:00'%s" $host $port $d.name (ternary (printf "&%s" $d.params) "" (ne $d.params "")) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "sangam.db.secret" -}}
{{- required "database.existingSecret is required (a Secret with the database username and password)" .Values.database.existingSecret -}}
{{- end -}}

{{/* Environment shared by the server pods and the migration job. */}}
{{- define "sangam.server.env" -}}
- name: SPRING_DATASOURCE_URL
  value: {{ include "sangam.db.jdbcUrl" . | quote }}
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "sangam.db.secret" . }}
      key: {{ .Values.database.usernameKey }}
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "sangam.db.secret" . }}
      key: {{ .Values.database.passwordKey }}
{{- if eq (include "sangam.db.type" .) "mysql" }}
- name: SPRING_DATASOURCE_DRIVER_CLASS_NAME
  value: org.mariadb.jdbc.Driver
{{- end }}
- name: SPRING_PROFILES_ACTIVE
  value: {{ trimSuffix "," (printf "%s,%s" (include "sangam.db.type" .) .Values.server.springProfiles) | quote }}
- name: JAVA_TOOL_OPTIONS
  value: {{ printf "%s -Djava.io.tmpdir=/tmp" .Values.server.javaToolOptions | quote }}
{{- end -}}

{{/* Driver volume + init container (bring-your-own driver). */}}
{{- define "sangam.drivers.volume" -}}
- name: drivers
{{- if .Values.drivers.existingClaim }}
  persistentVolumeClaim:
    claimName: {{ .Values.drivers.existingClaim }}
    readOnly: true
{{- else }}
  emptyDir:
    sizeLimit: 256Mi
{{- end }}
{{- end -}}

{{- define "sangam.drivers.initContainer" -}}
{{- if and .Values.drivers.initImage (not .Values.drivers.existingClaim) }}
- name: copy-drivers
  image: {{ include "sangam.toolImage" (dict "ctx" . "image" .Values.drivers.initImage) }}
  imagePullPolicy: {{ .Values.drivers.initImagePullPolicy }}
  command: ["/bin/sh", "-c", "cp -v {{ .Values.drivers.initImageDir }}/*.jar /opt/sangam/drivers/"]
  securityContext:
    {{- toYaml .Values.server.securityContext | nindent 4 }}
  resources:
    requests: { cpu: 10m, memory: 32Mi }
    limits: { memory: 64Mi }
  volumeMounts:
    - name: drivers
      mountPath: /opt/sangam/drivers
{{- end }}
{{- end -}}

{{- define "sangam.drivers.check" -}}
{{- if and (eq (include "sangam.db.type" .) "oracle") (not .Values.drivers.existingClaim) (not .Values.drivers.initImage) -}}
{{- fail "database.type=oracle needs a customer-supplied Oracle JDBC driver: set drivers.existingClaim or drivers.initImage (LIC-2)" -}}
{{- end -}}
{{- end -}}

{{- define "sangam.liquibase.serverMode" -}}
{{- if eq .Values.liquibase.mode "startup" -}}startup{{- else if has .Values.liquibase.mode (list "job" "none") -}}validate{{- else -}}{{- fail "liquibase.mode must be job, startup or none" -}}{{- end -}}
{{- end -}}

{{- define "sangam.backup.secret" -}}
{{- default (include "sangam.db.secret" .) .Values.backup.existingSecret -}}
{{- end -}}

{{- define "sangam.backup.claim" -}}
{{- if .Values.backup.persistence.existingClaim -}}
{{- .Values.backup.persistence.existingClaim -}}
{{- else -}}
{{- printf "%s-backup" (include "sangam.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/* ------------------------------------------------------------------ network policy fragments */}}

{{/* Egress rules shared by every pod that talks to the database (server, migration job, backup jobs). */}}
{{- define "sangam.np.dnsEgress" -}}
- to:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: {{ .Values.networkPolicy.dns.namespace }}
      podSelector:
        matchLabels:
          {{- toYaml .Values.networkPolicy.dns.podLabels | nindent 10 }}
  ports:
    - { protocol: UDP, port: 53 }
    - { protocol: TCP, port: 53 }
{{- end -}}
{{- define "sangam.np.dbEgress" -}}
{{- $np := .Values.networkPolicy -}}
{{- $port := include "sangam.db.port" . -}}
{{- if or $np.database.cidrs $np.database.podSelector $np.database.namespaceSelector }}
- to:
    {{- range $np.database.cidrs }}
    - ipBlock:
        cidr: {{ . }}
    {{- end }}
    {{- if or $np.database.podSelector $np.database.namespaceSelector }}
    - {{- with $np.database.namespaceSelector }}
      namespaceSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $np.database.podSelector }}
      podSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    {{- end }}
  ports:
    - { protocol: TCP, port: {{ $port }} }
{{- else }}
# networkPolicy.database is empty: database egress allowed to any destination on the DB port only.
- ports:
    - { protocol: TCP, port: {{ $port }} }
{{- end }}
{{- end -}}

{{/* ------------------------------------------------------------------ backups */}}

{{- define "sangam.backup.env" -}}
- name: DB_HOST
  value: {{ required "database.host is required for backups" .Values.database.host | quote }}
- name: DB_PORT
  value: {{ include "sangam.db.port" . | quote }}
- name: DB_NAME
  value: {{ .Values.database.name | quote }}
- name: DB_USER
  valueFrom:
    secretKeyRef: { name: {{ include "sangam.backup.secret" . }}, key: {{ .Values.database.usernameKey }} }
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef: { name: {{ include "sangam.backup.secret" . }}, key: {{ .Values.database.passwordKey }} }
- name: BACKUP_DIR
  value: /backup
- name: RETENTION_DAYS
  value: {{ .Values.backup.retentionDays | quote }}
- name: ORACLE_CONNECT_AS
  value: {{ .Values.backup.oracle.connectAs | quote }}
- name: DEST
  value: {{ .Values.backup.sqlserver.destination | quote }}
- name: DEST_TYPE
  value: {{ .Values.backup.sqlserver.destinationType | quote }}
- name: URL_CRED
  value: {{ ternary (printf ", CREDENTIAL = N'%s'" .Values.backup.sqlserver.urlCredential) "" (ne .Values.backup.sqlserver.urlCredential "") | quote }}
{{- end -}}

{{/* call with (dict "ctx" $ "name" "pg-full" "schedule" "..." "image" "..." "command" "sh /scripts/x.sh" ["runAsUser" 10001]) */}}
{{- define "sangam.backup.cronjob" -}}
{{- $ctx := .ctx -}}
{{- $b := $ctx.Values.backup }}
---
apiVersion: batch/v1
kind: CronJob
metadata:
  name: {{ include "sangam.fullname" $ctx }}-{{ .name }}
  labels:
    {{- include "sangam.labels" $ctx | nindent 4 }}
    app.kubernetes.io/component: backup
spec:
  schedule: {{ .schedule | quote }}
  timeZone: Etc/UTC
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 240
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 5
  jobTemplate:
    spec:
      backoffLimit: 1
      activeDeadlineSeconds: 21600
      template:
        metadata:
          labels:
            {{- include "sangam.selectorLabels" (dict "ctx" $ctx "component" "backup") | nindent 12 }}
            sangam.io/backup: {{ .name }}
        spec:
          restartPolicy: Never
          {{- include "sangam.imagePullSecrets" $ctx | nindent 10 }}
          automountServiceAccountToken: false
          securityContext:
            {{- $psc := deepCopy $b.podSecurityContext }}
            {{- if .runAsUser }}
            {{- $_ := set $psc "runAsUser" .runAsUser }}
            {{- $_ := set $psc "runAsGroup" .runAsUser }}
            {{- $_ := set $psc "fsGroup" .runAsUser }}
            {{- end }}
            {{- toYaml $psc | nindent 12 }}
          containers:
            - name: backup
              image: {{ include "sangam.toolImage" (dict "ctx" $ctx "image" .image) | quote }}
              command: ["/bin/sh", "-c", {{ .command | quote }}]
              env:
                {{- include "sangam.backup.env" $ctx | nindent 16 }}
              securityContext:
                allowPrivilegeEscalation: false
                readOnlyRootFilesystem: true
                capabilities: { drop: [ALL] }
              resources:
                {{- toYaml $b.resources | nindent 16 }}
              volumeMounts:
                - { name: scripts, mountPath: /scripts, readOnly: true }
                - { name: backup, mountPath: /backup }
                - { name: tmp, mountPath: /tmp }
          volumes:
            - name: scripts
              configMap: { name: {{ include "sangam.fullname" $ctx }}-backup-scripts }
            - name: backup
              persistentVolumeClaim: { claimName: {{ include "sangam.backup.claim" $ctx }} }
            - name: tmp
              emptyDir: { sizeLimit: 256Mi }
{{- end -}}
