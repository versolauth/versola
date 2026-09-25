{{/*
Base name of the chart.
*/}}
{{- define "loadgen.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully-qualified release name, e.g. "myrelease-loadgen".
*/}}
{{- define "loadgen.fullname" -}}
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

{{/*
Labels common to every resource in this chart.
*/}}
{{- define "loadgen.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{ include "loadgen.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Selector labels common to every resource in this chart.
*/}}
{{- define "loadgen.selectorLabels" -}}
app.kubernetes.io/name: {{ include "loadgen.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Per-component labels. Expects a dict: {component: <name>, context: $}.
*/}}
{{- define "loadgen.componentLabels" -}}
{{ include "loadgen.labels" .context }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
Per-component selector labels. Expects a dict: {component: <name>, context: $}.
*/}}
{{- define "loadgen.componentSelectorLabels" -}}
{{ include "loadgen.selectorLabels" .context }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
The coordinator's standby spread: the podAntiAffinity it defaults to when
replicaCount > 1, as YAML for `fromYaml`. Expects the root context.

Preferred, not required: a standby on the same node as the active replica
still survives the process dying, which is the failure §12 describes, and a
hard constraint would leave the standby Pending on a single-node cluster -- a
campaign that cannot start at all instead of one that is merely less
redundant than intended.
*/}}
{{- define "loadgen.coordinatorStandbySpread" -}}
preferredDuringSchedulingIgnoredDuringExecution:
  - weight: 100
    podAffinityTerm:
      topologyKey: kubernetes.io/hostname
      labelSelector:
        matchLabels:
          {{- include "loadgen.componentSelectorLabels" (dict "component" "coordinator" "context" .) | nindent 10 }}
{{- end -}}

{{/*
Image reference for a component. Expects a dict: {global: .Values.global, svc: <component values>, chart: .Chart}.
*/}}
{{- define "loadgen.image" -}}
{{- printf "%s/%s/%s:%s" .global.imageRegistry .global.imageRepository .svc.image.repository (default .chart.AppVersion .svc.image.tag) -}}
{{- end -}}

{{/*
Pod annotations for annotation-based Prometheus discovery, pointing at the
component's diagnostics port. Expects a dict: {root: $, port: <diagnostics
port>}. Empty when metrics.prometheusAnnotations is false -- see values.yaml's
`metrics` block for why this chart carries scrape configuration at all when
k8s/versola does not.

/metrics is on the diagnostics port for all three components: VersolaApp's
serviceRoutes for the coordinator and the drivers, mockapi's own
diagnosticsRoutes for mockapi (which serves the same three paths off its own
minimal boot sequence -- see mockapi/src/main/scala/versola/mockapi/Main.scala).
*/}}
{{- define "loadgen.scrapeAnnotations" -}}
{{- if .root.Values.metrics.prometheusAnnotations -}}
prometheus.io/scrape: "true"
prometheus.io/port: {{ .port | quote }}
prometheus.io/path: /metrics
{{- end -}}
{{- end -}}

{{/*
`envFrom` entries for one component: the shared `${VAR}` Secret, if set, plus
whatever the component's own extraEnvFrom adds. Expects a dict: {root: $, svc:
<component values>}.

Delivered as a whole-Secret secretRef rather than k8s/versola's per-var
secretKeyRef list -- see values.yaml's `secrets` block for why loadgen cannot
enumerate its required vars the way that chart can. Unset is legal and not a
template failure: a campaign whose env.conf holds no `${VAR}` placeholder at
all (no store password, e.g. a driver against a local trust-auth Postgres)
needs no Secret, and failing here would make one mandatory on the strength of
a guess about the file's contents.
*/}}
{{- define "loadgen.envFrom" -}}
{{- $entries := list -}}
{{- if .root.Values.secrets.existingSecret -}}
{{- $entries = append $entries (dict "secretRef" (dict "name" .root.Values.secrets.existingSecret)) -}}
{{- end -}}
{{- range $entry := .svc.extraEnvFrom -}}
{{- $entries = append $entries $entry -}}
{{- end -}}
{{- if $entries -}}
{{- toYaml $entries -}}
{{- end -}}
{{- end -}}

{{/*
Guard against pod placement set at the top level of values instead of under
`global` or a component (versolauth/versola#404). Expects the root context.

`nodeSelector`, `tolerations` and `affinity` are valid pod-spec field names on
their own, so they are what someone reaching for "how do I place this pod"
guesses at first. This chart reads none of them at the top level, and Helm
does not reject a values path nothing references: `--set
nodeSelector.workload=loadgen` installs, reports success, and places nothing.
The pods land wherever the scheduler puts them -- which is the one failure
this chart's placement support exists to prevent, and the only way to notice
is `kubectl get pods -o wide`.
*/}}
{{- define "loadgen.checkTopLevelPlacement" -}}
{{- range $field := list "nodeSelector" "tolerations" "affinity" -}}
{{- if hasKey $.Values $field -}}
{{- fail (printf "top-level `%s` is not read by this chart -- pod placement is `global.%s` chart-wide, or `<component>.%s` (driver, coordinator, mockapi) for one component; see values.yaml's `global` block" $field $field $field) -}}
{{- end -}}
{{- end -}}
{{- end -}}
