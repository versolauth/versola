{{/*
Base name of the chart.
*/}}
{{- define "versola.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully-qualified release name, e.g. "myrelease-versola".
*/}}
{{- define "versola.fullname" -}}
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
{{- define "versola.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{ include "versola.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Selector labels common to every resource in this chart.
*/}}
{{- define "versola.selectorLabels" -}}
app.kubernetes.io/name: {{ include "versola.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Per-component labels. Expects a dict: {component: <name>, context: $}.
*/}}
{{- define "versola.componentLabels" -}}
{{ include "versola.labels" .context }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
Per-component selector labels. Expects a dict: {component: <name>, context: $}.
*/}}
{{- define "versola.componentSelectorLabels" -}}
{{ include "versola.selectorLabels" .context }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
Image reference for a service. Expects a dict: {global: .Values.global, svc: <service values>, chart: .Chart}.
*/}}
{{- define "versola.image" -}}
{{- printf "%s/%s/%s:%s" .global.imageRegistry .global.imageRepository .svc.image.repository (default .chart.AppVersion .svc.image.tag) -}}
{{- end -}}

{{/*
console nginx.conf: static files only.

The docker-compose version of this image (docker/versola-tools/nginx.conf.template)
also proxies to auth/edge, because on a single VPS nginx is the only thing
in front of the services. Here the Ingress does that, so everything below
is about serving one directory.

The image bakes central-ui's dist/ at /usr/share/nginx/html/central/admin
(docker/Dockerfile.gateway); `alias` re-exposes it at console.basePath.
vite.config.ts emits relative asset URLs (#222), so index.html resolves
versola-admin.js correctly regardless of which path that is.

sub_filter stamps console-mode onto <versola-admin> on the way out. The
attribute exists (#223) but the published image can't hardcode it -- the
same image serves docker-compose, which needs the other mode. Rewriting
one tag here keeps that a deployment choice rather than a second image.
ngx_http_sub_module is compiled into the official nginx images.
*/}}
{{- define "versola.consoleNginxConf" -}}
{{- $basePath := .Values.console.basePath -}}
server {
    listen {{ .Values.console.port }};
    server_name _;

    location {{ $basePath }} {
        alias /usr/share/nginx/html/central/admin/;
        index index.html;
        try_files $uri $uri/ =404;
        add_header Cache-Control "no-cache" always;
        {{- if eq .Values.console.mode "direct" }}
        sub_filter '<versola-admin>' '<versola-admin console-mode="direct">';
        sub_filter_once on;
        {{- end }}
    }
}
{{- end -}}

{{/*
Route groups shared by ingress.yaml and httproute.yaml, as a YAML map of
group name -> list of {path, pathType, service, port}. Consumed with
fromYaml so both templates route identically and there's one place to
change when a service gains an endpoint.

Paths are the app's real routes, not a copy of the VPS nginx locations:
auth serves /authorize /token /par /introspect /revoke /userinfo
/.well-known/* /challenge* and exactly /logout, while edge serves
/login/{presetId}, /complete, /logout/{presetId}, /logout/frontchannel,
/logout/backchannel, /permissions/me and /resources/{resourceId}/*.

/logout is the one place where the two overlap: auth owns it exactly,
edge owns everything beneath it. Ingress resolves that by preferring the
longest match and, on a tie, Exact over Prefix -- so auth wins /logout
and edge still gets /logout/frontchannel. That precedence is in the
Ingress spec, but it is also the only rule here a non-conformant
controller could get wrong; if yours does, split the two across separate
hostnames.
*/}}
{{- define "versola.routeGroups" -}}
{{- $fullname := include "versola.fullname" . -}}
{{- $auth := printf "%s-auth" $fullname -}}
{{- $edge := printf "%s-edge" $fullname -}}
{{- $console := printf "%s-console" $fullname -}}
{{- $authPort := .Values.services.auth.port -}}
{{- $edgePort := .Values.services.edge.port -}}
oidc:
  - {path: /authorize, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /token, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /par, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /introspect, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /revoke, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /userinfo, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /logout, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /.well-known/, pathType: Prefix, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /challenge, pathType: Prefix, service: {{ $auth }}, port: {{ $authPort }}}
login:
  - {path: /login/, pathType: Prefix, service: {{ $edge }}, port: {{ $edgePort }}}
  - {path: /complete, pathType: Exact, service: {{ $edge }}, port: {{ $edgePort }}}
  - {path: /logout/, pathType: Prefix, service: {{ $edge }}, port: {{ $edgePort }}}
api:
  - {path: /resources/, pathType: Prefix, service: {{ $edge }}, port: {{ $edgePort }}}
  - {path: /permissions/, pathType: Prefix, service: {{ $edge }}, port: {{ $edgePort }}}
console:
  - {path: {{ .Values.console.basePath }}, pathType: Prefix, service: {{ $console }}, port: {{ .Values.console.port }}}
catchAll:
  - {path: /, pathType: Prefix, service: {{ $auth }}, port: {{ $authPort }}}
{{- end -}}
