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
{{- with .Values.global.environment }}
environment: {{ . | quote }}
{{- end }}
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
Required ${VAR} secret keys per service (see scripts/gen-env.scala's
secretField/useOpenBao) -- fixed by the app's own config contract, not a
values.yaml knob. CENTRAL_SECRET_KEY and CLIENT_SECRETS_SECRET are the
only two genuinely shared between auth and central (same value, see
gen-env.scala's own comment on why); everything else -- including
POSTGRES_PASSWORD, which every service needs but with a DIFFERENT value
each -- is per-service only. See versola.secretKeyFor below for how that
distinction avoids the three POSTGRES_PASSWORDs colliding under one key
in the single shared Secret (values.yaml's `secrets.existingSecret`).
*/}}
{{- define "versola.requiredSecretVars" -}}
{{- $vars := dict
  "auth" (list "ACCESS_TOKENS_SECRET" "CLIENT_SECRETS_SECRET" "REFRESH_TOKENS_SECRET" "AUTH_CODES_SECRET" "SESSIONS_SECRET" "PASSWORDS_SECRET" "CONVERSATION_COOKIE_SECRET" "SESSION_COOKIE_SECRET" "USER_AGENT_COOKIE_SECRET" "PAR_REQUESTS_SECRET" "DPOP_NONCES_SECRET" "JWT_PRIVATE_KEY" "CENTRAL_SECRET_KEY" "POSTGRES_PASSWORD" "ADMIN_BOOTSTRAP_PASSWORD")
  "central" (list "CENTRAL_SECRET_KEY" "CLIENT_SECRETS_SECRET" "ACCOUNT_RESOURCE_SECRET" "JWKS_JSON" "EDGE_PUBLIC_JWK" "POSTGRES_PASSWORD" "CENTRAL_RESOURCE_SECRET" "UTILITY_CLIENT_PUBLIC_JWK")
  "edge" (list "EDGE_PRIVATE_KEY" "EDGE_KEY_ID" "EDGE_TOKEN_ENC_KEY" "EDGE_SESSIONS_SECRET" "EDGE_INTERNAL_SECRET" "EDGE_DPOP_NONCE_SALT" "EDGE_NATIVE_BLOB_KEY" "POSTGRES_PASSWORD")
 -}}
{{- toYaml (index $vars .) -}}
{{- end -}}

{{/*
Secret data key for one (service, VAR) pair in the shared
secrets.existingSecret Secret. Expects a dict: {service: "auth", var:
"ACCESS_TOKENS_SECRET"}. kebab-cases the VAR name, then:
  - the 2 vars genuinely shared across services (see
    versola.requiredSecretVars above) stay bare, e.g. "central-secret-key",
    since both auth and central must read the identical key.
  - everything else is namespaced by service, e.g. "auth-postgres-password"
    vs "central-postgres-password" vs "edge-postgres-password", since
    these coincide on VAR name but differ in value per service. Skips the
    prefix if the kebab-cased name already starts with it (EDGE_PRIVATE_KEY
    -> "edge-private-key", not "edge-edge-private-key").
*/}}
{{- define "versola.secretKeyFor" -}}
{{- $shared := list "CENTRAL_SECRET_KEY" "CLIENT_SECRETS_SECRET" -}}
{{- $kebab := kebabcase .var -}}
{{- if has .var $shared -}}
{{- $kebab -}}
{{- else if hasPrefix (printf "%s-" .service) $kebab -}}
{{- $kebab -}}
{{- else -}}
{{- printf "%s-%s" .service $kebab -}}
{{- end -}}
{{- end -}}

{{/*
Renders the `env:` entries pulling one service's required secret values
from values.yaml's `secrets.existingSecret` via secretKeyRef. Expects a
dict: {root: $, service: "auth"|"central"|"edge"}. Fails at template time
(not a confusing "secret \"\" not found" at Pod-start) if
secrets.existingSecret is unset.
*/}}
{{- define "versola.secretEnv" -}}
{{- $svc := .service -}}
{{- $root := .root -}}
{{- $secretName := $root.Values.secrets.existingSecret -}}
{{- if not $secretName -}}
{{- fail (printf "services.%s requires secrets.existingSecret to be set -- see values.yaml's `secrets` block" $svc) -}}
{{- end -}}
{{- range $var := (include "versola.requiredSecretVars" $svc | fromYamlArray) -}}
- name: {{ $var }}
  valueFrom:
    secretKeyRef:
      name: {{ $secretName }}
      key: {{ include "versola.secretKeyFor" (dict "service" $svc "var" $var) }}
{{ end -}}
{{- end -}}

{{/*
Pod-template annotations reflecting values.yaml's secrets.restartStrategy
for one service's Deployment. Expects a dict: {root: $, service:
"auth"|"central"|"edge"}. Empty string for "none" (the default -- see
values.yaml's `secrets` block for what each mode does).
*/}}
{{- define "versola.secretRestartAnnotations" -}}
{{- $svc := .service -}}
{{- $root := .root -}}
{{- $strategy := $root.Values.secrets.restartStrategy -}}
{{- $secretName := $root.Values.secrets.existingSecret -}}
{{- if eq $strategy "reloader" -}}
reloader.stakater.com/auto: "true"
{{- else if eq $strategy "checksum" -}}
{{- if not $secretName -}}
{{- fail (printf "services.%s requires secrets.existingSecret to be set -- see values.yaml's `secrets` block" $svc) -}}
{{- end -}}
{{- $obj := lookup "v1" "Secret" $root.Release.Namespace $secretName -}}
{{- $data := dict -}}
{{- if $obj -}}
{{- $data = $obj.data -}}
{{- end -}}
{{- $parts := list -}}
{{- range $var := (include "versola.requiredSecretVars" $svc | fromYamlArray) -}}
{{- $key := include "versola.secretKeyFor" (dict "service" $svc "var" $var) -}}
{{- $parts = append $parts (printf "%s=%s" $key (index $data $key)) -}}
{{- end -}}
checksum/secret: {{ join "," $parts | sha256sum }}
{{- end -}}
{{- end -}}

{{/*
Pod annotations for annotation-based Prometheus/VictoriaMetrics discovery,
pointing at a component's diagnostics port. Expects a dict: {root: $, port:
<diagnostics port>}. Empty when metrics.prometheusAnnotations is false --
see values.yaml's `metrics` block for why this chart carries scrape
configuration at all when its Service is ClusterIP-only.

/metrics is on the diagnostics port for all three components --
VersolaApp's serviceRoutes.
*/}}
{{- define "versola.scrapeAnnotations" -}}
{{- if .root.Values.metrics.prometheusAnnotations -}}
prometheus.io/scrape: "true"
prometheus.io/port: {{ .port | quote }}
prometheus.io/path: /metrics
{{- end -}}
{{- end -}}

{{/*
console nginx.conf: static files only.

The docker-compose version of this image (docker/versola-tools/nginx.conf.template)
also proxies to auth/edge, because on a single VPS nginx is the only thing
in front of the services. Here the Ingress does that, so everything below
is about serving one directory.

central-ui's dist/ is mounted at /usr/share/nginx/html/central/admin -- copied there
by an init container out of the versola-tools image (docker/Dockerfile.tools);
`alias` re-exposes it at console.basePath.
vite.config.ts emits relative asset URLs (#222), so index.html resolves
versola-admin.js correctly regardless of which path that is.

sub_filter stamps console-mode onto <versola-admin> on the way out. The
attribute exists (#223) but the build can't hardcode it -- the same
files serve docker-compose, which needs the other mode. Rewriting
one tag here keeps that a deployment choice rather than a second image.
ngx_http_sub_module is compiled into the official nginx images.
*/}}
{{- define "versola.consoleNginxConf" -}}
{{- $basePath := .Values.console.basePath -}}
{{- $slashless := trimSuffix "/" $basePath -}}
{{- /*
Security headers (#473) for the static console. HSTS only when the ingress says the browser used
https (an empty add_header value is not sent), the rest always. The services set the same set
themselves on everything proxied; they see X-Forwarded-Proto directly in k8s (no versola-proxy in
between), so they send HSTS too.
*/}}
map $http_x_forwarded_proto $versola_hsts {
    default "";
    https "max-age=31536000; includeSubDomains";
}

server {
    listen {{ .Values.console.port }};
    server_name _;

    {{- /*
    With `alias`, nginx only matches the location's exact string -- there's
    no implicit fallback from the slashless path to the trailing-slash one
    the way there is with `root` + a directory index. For a non-root
    basePath (e.g. /central/admin/) that would make the slashless bookmark
    (/central/admin) 404 instead of resolving, unlike the old gateway's
    proxy_pass rules, which had an explicit `location = /central/admin`
    redirect for exactly this (see nginx.conf.template). basePath "/" has
    no slashless variant to redirect from, so this is skipped there.
    */}}
    {{- if and (ne $basePath "/") (hasSuffix "/" $basePath) }}
    location = {{ $slashless }} {
        return 301 {{ $basePath }};
    }
    {{- end }}

    location {{ $basePath }} {
        alias /usr/share/nginx/html/central/admin/;
        index index.html;
        try_files $uri $uri/ =404;
        add_header Cache-Control "no-cache" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "no-referrer" always;
        add_header X-Frame-Options "DENY" always;
        add_header Content-Security-Policy "frame-ancestors 'none'; base-uri 'none'; object-src 'none'" always;
        add_header Strict-Transport-Security $versola_hsts always;
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
# A mobile app's front door under FAPI 2.0 (#420): /native/{start,complete,token,revoke}/{clientId}.
# Public by design -- the device is the caller, and edge authenticates to auth on its behalf --
# so a deployment with native clients has to list it; one without leaves it out, and edge
# answers 404 for any clientId it does not front natively anyway.
native:
  - {path: /native/, pathType: Prefix, service: {{ $edge }}, port: {{ $edgePort }}}
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

{{/*
Guard against pod placement set at the top level of values instead of under
`global` or a component (versolauth/versola#404). Expects the root context.

`nodeSelector`, `tolerations` and `affinity` are valid pod-spec field names on
their own, so they are what someone reaching for "how do I place this pod"
guesses at first. This chart reads none of them at the top level, and Helm
does not reject a values path nothing references: `--set
nodeSelector.workload=versola` installs, reports success, and places nothing.
The pods land wherever the scheduler puts them -- which is the one failure
this chart's placement support exists to prevent, and the only way to notice
is `kubectl get pods -o wide`.
*/}}
{{- define "versola.checkTopLevelPlacement" -}}
{{- range $field := list "nodeSelector" "tolerations" "affinity" -}}
{{- if hasKey $.Values $field -}}
{{- fail (printf "top-level `%s` is not read by this chart -- pod placement is `global.%s` chart-wide, or `services.<name>.%s` / `console.%s` for one component; see values.yaml's `global` block" $field $field $field $field) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Name of the Secret holding auth's mutual-TLS listener certificate (values.yaml's `pki.authServer`).
*/}}
{{- define "versola.pki.serverSecret" -}}
{{- default (printf "%s-auth-mtls" (include "versola.fullname" .)) .Values.pki.authServer.secretName -}}
{{- end -}}

{{/* Secrets of central's mutual-TLS listener certificate and of the edge's certificate for it (#554). */}}
{{- define "versola.pki.centralServerSecret" -}}
{{- default (printf "%s-central-mtls" (include "versola.fullname" .)) .Values.pki.centralServer.secretName -}}
{{- end -}}

{{- define "versola.pki.edgeCentralSecret" -}}
{{- default (printf "%s-edge-central-client" (include "versola.fullname" .)) .Values.pki.edgeCentral.secretName -}}
{{- end -}}

{{/*
Names of the ConfigMaps trust-manager writes the trust anchors to: auth's handshake anchors (client
and edge intermediates) and the edge anchors alone (central's handshake; auth's edge names).
*/}}
{{- define "versola.pki.trustBundle" -}}
{{- printf "%s-client-ca" (include "versola.fullname" .) -}}
{{- end -}}

{{- define "versola.pki.edgeTrustBundle" -}}
{{- printf "%s-edge-ca" (include "versola.fullname" .) -}}
{{- end -}}

{{/*
The issuer the operator named for a role ("server", "edge" or "client"): `pki.issuerRefs.<role>`, else
`pki.issuerRef`. Expects {root: $, role: "..."}. Renders the issuerRef's fields, or nothing when this
chart bootstraps that role's CA itself (templates/pki.yaml).
*/}}
{{- define "versola.pki.operatorRef" -}}
{{- $pki := .root.Values.pki -}}
{{- $ref := index (default dict $pki.issuerRefs) .role | default dict -}}
{{- if not $ref.name -}}
{{- $ref = default dict $pki.issuerRef -}}
{{- end -}}
{{- if $ref.name -}}
name: {{ $ref.name }}
kind: {{ default "ClusterIssuer" $ref.kind }}
group: {{ default "cert-manager.io" $ref.group }}
{{- end -}}
{{- end -}}

{{/*
The cert-manager issuerRef a role's certificates are issued from: the operator's when one is named,
otherwise the role's ClusterIssuer this chart bootstraps. Expects {root: $, role: "..."}.
*/}}
{{- define "versola.pki.issuerRef" -}}
{{- $own := include "versola.pki.operatorRef" . -}}
{{- if $own -}}
{{- $own -}}
{{- else -}}
name: {{ include "versola.fullname" .root }}-{{ .role }}-ca
kind: ClusterIssuer
group: cert-manager.io
{{- end -}}
{{- end -}}

{{/*
pki volumes / mounts / reload annotations for one service's Deployment. Expects a dict:
{root: $, service: "auth"|"central"|"edge"}. Empty unless `pki.enabled`. Auth presents the server
certificate and trusts the client and edge intermediates (an edge presents its identity to it, too),
and is told separately which of them is the edge's; edge pins the leaf of auth's and of central's
listener certificate (and nothing else from those Secrets -- not their keys). Central presents its own
listener certificate for the edge surface and trusts the edge CA alone for it; edge presents its
identity there (#554). Without trust-manager the trust files are the leaf Secret's `ca.crt`.
*/}}
{{- define "versola.pki.volumes" -}}
{{- $root := .root -}}
{{- if $root.Values.pki.enabled -}}
{{- $bundles := $root.Values.pki.trustBundle.enabled -}}
{{- if eq .service "auth" -}}
- name: mtls-server
  secret:
    secretName: {{ include "versola.pki.serverSecret" $root }}
    items:
      - key: tls.crt
        path: tls.crt
      - key: tls.key
        path: tls.key
- name: mtls-trust
  {{- if $bundles }}
  configMap:
    name: {{ include "versola.pki.trustBundle" $root }}
    items:
      - key: ca-bundle.crt
        path: ca.crt
  {{- else }}
  secret:
    secretName: {{ include "versola.pki.serverSecret" $root }}
    items:
      - key: ca.crt
        path: ca.crt
  {{- end }}
# The edge anchor on its own: a certificate may carry an edge's `urn:versola:edge:*` name only if it chains to this.
- name: mtls-edge-trust
  {{- if $bundles }}
  configMap:
    name: {{ include "versola.pki.edgeTrustBundle" $root }}
    items:
      - key: ca-bundle.crt
        path: ca.crt
  {{- else }}
  secret:
    secretName: {{ include "versola.pki.serverSecret" $root }}
    items:
      - key: ca.crt
        path: ca.crt
  {{- end }}
{{- else if eq .service "central" -}}
- name: central-mtls-server
  secret:
    secretName: {{ include "versola.pki.centralServerSecret" $root }}
    items:
      - key: tls.crt
        path: tls.crt
      - key: tls.key
        path: tls.key
# The edge CA alone, which is what the listener's handshake validates an edge's chain against.
- name: central-mtls-trust
  {{- if $bundles }}
  configMap:
    name: {{ include "versola.pki.edgeTrustBundle" $root }}
    items:
      - key: ca-bundle.crt
        path: ca.crt
  {{- else }}
  secret:
    secretName: {{ include "versola.pki.centralServerSecret" $root }}
    items:
      - key: ca.crt
        path: ca.crt
  {{- end }}
{{- else if eq .service "edge" -}}
- name: mtls-auth-pin
  secret:
    secretName: {{ include "versola.pki.serverSecret" $root }}
    items:
      - key: tls.crt
        path: tls.crt
- name: central-mtls-client
  secret:
    secretName: {{ include "versola.pki.edgeCentralSecret" $root }}
    items:
      - key: tls.crt
        path: tls.crt
      - key: tls.key
        path: tls.key
# Central's leaf only, as edge pins auth's: never its key.
- name: central-mtls-server-pin
  secret:
    secretName: {{ include "versola.pki.centralServerSecret" $root }}
    items:
      - key: tls.crt
        path: tls.crt
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "versola.pki.volumeMounts" -}}
{{- if .root.Values.pki.enabled -}}
{{- if eq .service "auth" -}}
- name: mtls-server
  mountPath: /app/mtls/server
  readOnly: true
- name: mtls-trust
  mountPath: /app/mtls/trust
  readOnly: true
- name: mtls-edge-trust
  mountPath: /app/mtls/edge-trust
  readOnly: true
{{- else if eq .service "central" -}}
- name: central-mtls-server
  mountPath: /app/central-mtls/server
  readOnly: true
- name: central-mtls-trust
  mountPath: /app/central-mtls/trust
  readOnly: true
{{- else if eq .service "edge" -}}
- name: mtls-auth-pin
  mountPath: /app/mtls/auth-pin
  readOnly: true
- name: central-mtls-client
  mountPath: /app/central-mtls/client
  readOnly: true
- name: central-mtls-server-pin
  mountPath: /app/central-mtls/server-pin
  readOnly: true
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "versola.pki.annotations" -}}
{{- if and .root.Values.pki.enabled .root.Values.pki.reloader (has .service (list "auth" "central" "edge")) -}}
{{- $bundles := .root.Values.pki.trustBundle.enabled -}}
{{- if eq .service "central" -}}
secret.reloader.stakater.com/reload: {{ include "versola.pki.centralServerSecret" .root | quote }}
{{- if $bundles }}
configmap.reloader.stakater.com/reload: {{ include "versola.pki.edgeTrustBundle" .root | quote }}
{{- end }}
{{- else if eq .service "edge" -}}
secret.reloader.stakater.com/reload: {{ printf "%s,%s,%s" (include "versola.pki.serverSecret" .root) (include "versola.pki.centralServerSecret" .root) (include "versola.pki.edgeCentralSecret" .root) | quote }}
{{- else -}}
secret.reloader.stakater.com/reload: {{ include "versola.pki.serverSecret" .root | quote }}
{{- if $bundles }}
configmap.reloader.stakater.com/reload: {{ printf "%s,%s" (include "versola.pki.trustBundle" .root) (include "versola.pki.edgeTrustBundle" .root) | quote }}
{{- end }}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
The env central.conf's `client-certificates.cert-manager` block reads (scripts/gen-env.scala): the
client role's issuer, the one that signs the certificates central issues to edge-fronted clients.
*/}}
{{- define "versola.pki.issuerEnv" -}}
{{- $own := include "versola.pki.operatorRef" (dict "root" . "role" "client") | fromYaml -}}
{{- if $own.name -}}
- name: CLIENT_CERT_ISSUER_NAME
  value: {{ $own.name | quote }}
- name: CLIENT_CERT_ISSUER_KIND
  value: {{ $own.kind | quote }}
- name: CLIENT_CERT_ISSUER_GROUP
  value: {{ $own.group | quote }}
{{- else -}}
- name: CLIENT_CERT_ISSUER_NAME
  value: {{ printf "%s-client-ca" (include "versola.fullname" .) | quote }}
{{- end -}}
{{- end -}}

{{/*
How many replicas a service runs at least: `autoscaling.minReplicas` when its HPA is on, the Deployment's
`replicaCount` otherwise. A PodDisruptionBudget only means something above one (see pdb.yaml).
*/}}
{{- define "versola.minReplicas" -}}
{{- if .svc.autoscaling.enabled -}}{{ .svc.autoscaling.minReplicas }}{{- else -}}{{ .svc.replicaCount }}{{- end -}}
{{- end -}}

{{/*
The PriorityClass a service's pods run under: the service's own `priorityClassName` when set; otherwise,
only when this chart creates the classes (`priorityClasses.create`), `controlPlane` for central and
`customerFacing` for auth and edge. Empty -- no priorityClassName on the pod -- in every other case: a
name that no cluster object carries leaves the pod unschedulable, so the chart does not guess one.
*/}}
{{- define "versola.priorityClassName" -}}
{{- if .svc.priorityClassName -}}
{{- .svc.priorityClassName -}}
{{- else if .root.Values.priorityClasses.create -}}
{{- if eq .name "central" -}}{{ .root.Values.priorityClasses.controlPlane.name }}{{- else -}}{{ .root.Values.priorityClasses.customerFacing.name }}{{- end -}}
{{- end -}}
{{- end -}}

{{/*
A service's topologySpreadConstraints: its own when non-empty, otherwise `global`'s -- a replacement, not a
merge, like the rest of the placement fields. A constraint without a `labelSelector` gets the service's own
selector labels, so one global list spreads each service across its own replicas, not all pods together.
*/}}
{{- define "versola.topologySpreadConstraints" -}}
{{- $selector := dict "matchLabels" (include "versola.componentSelectorLabels" (dict "component" .name "context" .root) | fromYaml) -}}
{{- $constraints := list -}}
{{- range $constraint := (.svc.topologySpreadConstraints | default .root.Values.global.topologySpreadConstraints) -}}
{{- $constraints = append $constraints (ternary $constraint (merge (deepCopy $constraint) (dict "labelSelector" $selector)) (hasKey $constraint "labelSelector")) -}}
{{- end -}}
{{- if $constraints -}}{{ toYaml $constraints }}{{- end -}}
{{- end -}}

{{/*
Refuses availability settings that would otherwise render something other than what was asked for.
Called from validate-values.yaml.
*/}}
{{- define "versola.checkAvailability" -}}
{{- range $name, $svc := .Values.services -}}
{{- if $svc.enabled -}}
{{- $hpa := $svc.autoscaling -}}
{{- if $hpa.enabled -}}
{{- if lt (int $hpa.maxReplicas) (int $hpa.minReplicas) -}}
{{- fail (printf "services.%s.autoscaling: maxReplicas (%v) is below minReplicas (%v)" $name $hpa.maxReplicas $hpa.minReplicas) -}}
{{- end -}}
{{- if and (not $hpa.metrics) (not (dig "requests" "cpu" "" ($svc.resources | default dict))) -}}
{{- fail (printf "services.%s.autoscaling: the default CPU metric is a utilisation of the CPU request, and services.%s.resources.requests.cpu is not set; set it, or give autoscaling.metrics explicitly" $name $name) -}}
{{- end -}}
{{- end -}}
{{- $pdb := $svc.podDisruptionBudget -}}
{{- if and $pdb.enabled (kindIs "invalid" $pdb.minAvailable | not) (kindIs "invalid" $pdb.maxUnavailable | not) -}}
{{- fail (printf "services.%s.podDisruptionBudget: set minAvailable or maxUnavailable, not both" $name) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
The pod spec of the migration Job (templates/migrate-job.yaml), one container per enabled
service, expects the root context. Empty when no service is enabled.

One container per service rather than one for all three, because the credentials differ:
every service's env.conf says `${POSTGRES_PASSWORD}`, with a different value each (see
versola.requiredSecretVars). Each container gets only its own service's env.conf and only that
password -- not the token, signing and client secrets the Deployments carry -- and migrates
only that service's schema (`--service`). The three schemas are independent, so the containers
run side by side; the Job fails if any of them does.

The volumes of a service (`services.<name>.extraVolumes`, typically the CA that
`postgres.ssl-root-cert` points at) are mounted at the same paths, so the env.conf means the
same thing here as in its Deployment.
*/}}
{{- define "versola.migrate.podSpec" -}}
{{- $root := . -}}
{{- $m := .Values.migrations -}}
{{- $volumes := dict -}}
{{- $containers := list -}}
{{- range $name := list "auth" "central" "edge" -}}
{{- $svc := index $root.Values.services $name -}}
{{- if $svc.enabled -}}
{{- if not $svc.config.existingSecret -}}
{{- fail (printf "migrations.enabled needs services.%s.config.existingSecret: the Job reads the same env.conf as the service" $name) -}}
{{- end -}}
{{- if not $root.Values.secrets.existingSecret -}}
{{- fail "migrations.enabled needs secrets.existingSecret: the Job reads the database password from it" -}}
{{- end -}}
{{- $cfg := printf "config-%s" $name -}}
{{- $_ := set $volumes $cfg (dict "name" $cfg "secret" (dict "secretName" $svc.config.existingSecret "items" (list (dict "key" $svc.config.secretKey "path" (printf "%s.conf" $name))))) -}}
{{- range $v := $svc.extraVolumes -}}
{{- if and (hasKey $volumes $v.name) (ne (toJson (get $volumes $v.name)) (toJson $v)) -}}
{{- fail (printf "services.%s.extraVolumes names a volume %q that another service defines differently; the migration Job shares one volume list" $name $v.name) -}}
{{- end -}}
{{- $_ := set $volumes $v.name $v -}}
{{- end -}}
{{- $args := list "migrate" "--service" $name -}}
{{- if $m.dryRun -}}{{- $args = append $args "--dry-run" -}}{{- end -}}
{{- $env := list
  (dict "name" "POSTGRES_PASSWORD" "valueFrom" (dict "secretKeyRef" (dict "name" $root.Values.secrets.existingSecret "key" (include "versola.secretKeyFor" (dict "service" $name "var" "POSTGRES_PASSWORD")))))
  (dict "name" "MIGRATE_LOCK_TIMEOUT_MS" "value" ($m.lockTimeoutMs | toString))
  (dict "name" "MIGRATE_LOCK_RETRIES" "value" ($m.lockRetries | toString))
  (dict "name" "MIGRATE_CONNECT_RETRIES" "value" ($m.connectRetries | toString))
-}}
{{- $env = concat $env ($m.extraEnv | default list) -}}
{{- $mounts := concat (list (dict "name" $cfg "mountPath" "/opt/versola-tools/config" "readOnly" true)) ($svc.extraVolumeMounts | default list) -}}
{{- $containers = append $containers (dict
  "name" (printf "migrate-%s" $name)
  "image" (include "versola.image" (dict "global" $root.Values.global "svc" $m "chart" $root.Chart))
  "imagePullPolicy" $root.Values.global.imagePullPolicy
  "args" $args
  "env" $env
  "volumeMounts" $mounts
  "resources" $m.resources) -}}
{{- end -}}
{{- end -}}
{{- if $containers -}}
restartPolicy: Never
{{- with $root.Values.global.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with ($m.nodeSelector | default $root.Values.global.nodeSelector) }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with ($m.tolerations | default $root.Values.global.tolerations) }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with ($m.affinity | default $root.Values.global.affinity) }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
containers:
  {{- toYaml $containers | nindent 2 }}
volumes:
  {{- $ordered := list -}}
  {{- range $k := keys $volumes | sortAlpha -}}
  {{- $ordered = append $ordered (get $volumes $k) -}}
  {{- end }}
  {{- toYaml $ordered | nindent 2 }}
{{- end -}}
{{- end -}}
