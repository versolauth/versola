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

Paths are the app's real routes, taken straight from each
*Controller.scala, not a copy of the VPS nginx locations (which only ever
covered the `oidc`/`login`/`api` groups below -- `users`, `service` and
`settings` were never in it): auth serves /authorize /token /par
/introspect /revoke /userinfo /.well-known/* /challenge* and exactly
/logout (all public OIDC surface), plus /users/* (UserController),
/service/* (ServiceController) and /settings/* (AccountSettingsController,
on auth's *additional* port -- see services.auth.additionalPort).
edge serves /login/{presetId}, /complete, /logout/{presetId},
/logout/frontchannel, /logout/backchannel, /permissions/me and
/resources/{resourceId}/*.

There is deliberately no catch-all `/` group: /users and /service are
called by central over in-cluster Service DNS (config.auth.url), not
through this Ingress, and /settings isn't proxied by the VPS's nginx
either (deploy.md marks it "internal"). Every real endpoint above is
named, so nothing reaches the outside world unless a host's `routes`
lists the group it's in.

/logout is the one place oidc and login overlap: auth owns it exactly,
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
{{- $authAdditionalPort := .Values.services.auth.additionalPort -}}
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
# UserController -- central's own client (see AuthClient.scala) calls
# this over in-cluster Service DNS, not through the Ingress. Only add
# this group to a host if that's genuinely not true in your deployment.
# Not a bare door either way: every endpoint requires authorizeInternal.scala's
# Bearer JWT, signed with central's own secret key, never a user session.
users:
  - {path: /users, pathType: Exact, service: {{ $auth }}, port: {{ $authPort }}}
  - {path: /users/, pathType: Prefix, service: {{ $auth }}, port: {{ $authPort }}}
# ServiceController -- same cluster-internal caller, same authorizeInternal
# guard as `users`, plus its own belt-and-braces: both endpoints 404
# outright whenever env is "prod" (ServiceController.scala's env.isProd
# check), so this group is a no-op in production even if listed.
service:
  - {path: /service/, pathType: Prefix, service: {{ $auth }}, port: {{ $authPort }}}
# AccountSettingsController -- lives on auth's *additional* port
# (APORT), not `oidc`'s main one, so this only resolves to a working
# backend once services.auth.additionalPort is set and the Deployment's
# extra container port is enabled (see templates/deployment.yaml). Guarded
# by authorizeResource: HTTP Basic auth against a resource secret central
# issues for auth, the same edge/central-to-auth mechanism as `users` and
# `service`, not an end-user credential.
settings:
  - {path: /settings, pathType: Exact, service: {{ $auth }}, port: {{ $authAdditionalPort }}}
  - {path: /settings/, pathType: Prefix, service: {{ $auth }}, port: {{ $authAdditionalPort }}}
{{- end -}}
