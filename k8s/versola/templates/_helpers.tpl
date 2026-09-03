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
Default gateway nginx.conf, adapted from docker/versola-tools/nginx.conf.template
for in-cluster Service DNS names (this chart's own auth/edge Services) instead
of Compose service names, and a values-driven listen port instead of the fixed
2821 used for local dev. Routing itself (which paths go to auth vs edge, the
/central/admin/ static split) is otherwise unchanged -- see that file for the
full reasoning behind each location block.
*/}}
{{- define "versola.defaultNginxConf" -}}
upstream auth_backend {
    server {{ include "versola.fullname" . }}-auth:{{ .Values.services.auth.port }};
    keepalive 32;
}

upstream edge_backend {
    server {{ include "versola.fullname" . }}-edge:{{ .Values.services.edge.port }};
    keepalive 32;
}

server {
    listen {{ .Values.gateway.port }};
    server_name _;

    client_max_body_size 8m;

    location = /admin {
        return 301 /central/admin/$is_args$args;
    }

    location /admin/ {
        rewrite ^/admin/(.*)$ /central/admin/$1 permanent;
    }

    location = /central {
        return 301 /central/admin/$is_args$args;
    }

    location = /central/ {
        return 301 /central/admin/$is_args$args;
    }

    location = /central/admin {
        return 301 /central/admin/$is_args$args;
    }

    location /central/admin/ {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ =404;
        add_header Cache-Control "no-cache" always;
    }

    location = /central/permissions/me {
        proxy_pass http://edge_backend/permissions/me;
        include proxy_params.conf;
    }

    location /central/ {
        rewrite ^/central/(.*)$ /resources/central/$1 break;

        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location = /resources {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location /resources/ {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location = /permissions {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location /permissions/ {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location = /authorize {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location = /token {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location = /introspect {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location = /revoke {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location = /userinfo {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location /.well-known/ {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location = /challenge {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location /challenge/ {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location = /login {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location /login/ {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location = /complete {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location = /complete/ {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location = /logout {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }

    location /logout/ {
        proxy_pass http://edge_backend;
        include proxy_params.conf;
    }

    location / {
        proxy_pass http://auth_backend;
        include proxy_params.conf;
    }
}
{{- end -}}

{{/*
Default gateway proxy_params.conf, copied as-is from
docker/versola-tools/proxy_params.conf.template.
*/}}
{{- define "versola.defaultProxyParamsConf" -}}
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header Connection "";
{{- end -}}
