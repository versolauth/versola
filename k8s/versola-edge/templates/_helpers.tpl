{{- define "versola-edge.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "versola-edge.fullname" -}}
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

{{- define "versola-edge.selectorLabels" -}}
app.kubernetes.io/name: {{ include "versola-edge.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: edge
{{- end -}}

{{- define "versola-edge.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{ include "versola-edge.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
The secret vars env.conf references for edge: what the versola chart injects for its edge, and what
scripts/gen-env.scala writes to edge.generated-secrets.env (.github/scripts/check-chart-secret-vars.sh
holds both to it). Key names follow the versola chart: kebab-case, prefixed `edge-` unless already.
*/}}
{{- define "versola-edge.secretVars" -}}
{{- toYaml (list "EDGE_PRIVATE_KEY" "EDGE_KEY_ID" "EDGE_TOKEN_ENC_KEY" "EDGE_SESSIONS_SECRET" "EDGE_INTERNAL_SECRET" "EDGE_DPOP_NONCE_SALT" "EDGE_NATIVE_BLOB_KEY" "POSTGRES_PASSWORD") -}}
{{- end -}}

{{- define "versola-edge.secretEnv" -}}
{{- $name := required "secrets.existingSecret is required: the Secret holding the ${VAR} values env.conf references" .Values.secrets.existingSecret -}}
{{- range $var := (include "versola-edge.secretVars" . | fromYamlArray) -}}
{{- $kebab := kebabcase $var }}
- name: {{ $var }}
  valueFrom:
    secretKeyRef:
      name: {{ $name }}
      key: {{ ternary $kebab (printf "edge-%s" $kebab) (hasPrefix "edge-" $kebab) }}
{{ end -}}
{{- end -}}

{{/* The Secret holding the one-time enrollment token. */}}
{{- define "versola-edge.tokenSecret" -}}
{{- if .Values.central.enrollment.existingSecret -}}
{{- .Values.central.enrollment.existingSecret -}}
{{- else -}}
{{- printf "%s-enrollment" (include "versola-edge.fullname" .) -}}
{{- end -}}
{{- end -}}
