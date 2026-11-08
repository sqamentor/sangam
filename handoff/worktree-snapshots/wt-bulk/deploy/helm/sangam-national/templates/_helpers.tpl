{{/* SPDX-License-Identifier: Apache-2.0 */}}
{{- define "national.image" -}}
{{- $reg := .root.Values.global.imageRegistry | default .root.Values.image.registry -}}
{{- $tag := .root.Values.image.tag | default .root.Chart.AppVersion -}}
{{ printf "%s/%s:%s" $reg .repo $tag }}
{{- end -}}

{{- define "national.labels" -}}
app.kubernetes.io/part-of: sangam-national
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/name: {{ .name }}
{{- end -}}

{{- define "national.pod" -}}
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001
  seccompProfile: { type: RuntimeDefault }
{{- with .root.Values.global.imagePullSecrets }}
imagePullSecrets: {{ toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{- define "national.container" -}}
image: {{ include "national.image" (dict "root" .root "repo" .cfg.repository) }}
imagePullPolicy: {{ .root.Values.image.pullPolicy }}
ports:
  - { name: http, containerPort: {{ .cfg.port }} }
  - { name: mgmt, containerPort: {{ .cfg.managementPort }} }
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: mgmt }
  periodSeconds: 10
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: mgmt }
  initialDelaySeconds: 60
  periodSeconds: 20
resources: {{ toYaml .cfg.resources | nindent 2 }}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: [ALL] }
volumeMounts:
  - { name: tmp, mountPath: /tmp }
{{- end -}}
