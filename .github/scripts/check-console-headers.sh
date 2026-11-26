#!/usr/bin/env bash
#
# Asserts that every nginx that serves the admin console's static files sends the same security headers,
# and that the Content-Security-Policy is the one the services themselves send.
#
# Why this exists: the console is the one surface nginx answers on its own (everything proxied carries the
# services' headers, SecurityHeaders.scala), and the nginx config is written out in three places that drift
# apart silently: this repository's docker/versola-tools/nginx.conf.template (docker-local), the Helm chart's
# console config (k8s/versola/templates/_helpers.tpl), and the `routes.conf` that versola-cli (a separate
# repository, versolauth/versola-cli) renders for a VPS. The VPS one lost every header once and nothing failed,
# because nginx replaces a server-level add_header in a location that sets its own: the headers have to be
# repeated in the console's location, and a copy that forgets them is a valid config.
#
# What is compared, for the location that serves the console in each file: the set of
#   add_header X-Content-Type-Options / Referrer-Policy / X-Frame-Options / Content-Security-Policy
# (name and value, exactly). The chart and the CLI also send Strict-Transport-Security, through a
# `$versola_hsts` variable that is empty over plain http; the docker-local template listens on plain http only
# and says why it has none, so it is required of the other two and not of it. The CSP value is also compared with
# `HtmlContentSecurityPolicy` in util/.../SecurityHeaders.scala.
#
# Usage:
#   .github/scripts/check-console-headers.sh                       # this repository's two files and the Scala constant
#   .github/scripts/check-console-headers.sh path/to/routes.conf   # plus a versola-cli routes.conf
#   .github/scripts/check-console-headers.sh --fetch-cli           # plus the one on versola-cli's main, over the network

set -euo pipefail
cd "$(dirname "$0")/../.."

cli=""
case "${1:-}" in
  "") ;;
  --fetch-cli)
    cli="$(mktemp)"
    trap 'rm -f "$cli"' EXIT
    curl -fsSL "https://raw.githubusercontent.com/versolauth/versola-cli/main/internal/proxy/templates/routes.conf" -o "$cli"
    ;;
  *) cli="$1" ;;
esac

CLI_ROUTES="$cli" python3 - <<'PY'
import os
import re
import sys

failures = []

CORE = ["X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options", "Content-Security-Policy"]


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def location_block(text, header_re, label):
    """The body of the first `location` whose line matches header_re, brace-balanced."""
    m = re.search(header_re, text, re.M)
    if not m:
        failures.append(f"{label}: no console location found")
        return None
    start = m.end()  # the pattern ends with the location's own opening brace
    depth, i = 1, start
    while i < len(text) and depth:
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        i += 1
    return text[start:i - 1]


def headers(block):
    # Template directives ({{ ... }}) are not part of what is compared.
    block = re.sub(r"\{\{.*?\}\}", "", block)
    found = {}
    for m in re.finditer(r'^\s*add_header\s+(\S+)\s+("(?:[^"\\]|\\.)*"|\S+?)\s+always;', block, re.M):
        name, value = m.group(1), m.group(2)
        if name in found:
            failures.append(f"{name} is set twice in one location")
        found[name] = value.strip('"') if value.startswith('"') else value
    return found


sources = {
    "docker/versola-tools/nginx.conf.template": (r"^\s*location /central/admin/ \{", False),
    "k8s/versola/templates/_helpers.tpl": (r"^\s*location \{\{ \$basePath \}\} \{", True),
}
cli = os.environ.get("CLI_ROUTES")
if cli:
    sources[cli] = (r"^\s*location /central/admin/ \{", True)

found = {}
for path, (header_re, hsts) in sources.items():
    block = location_block(read(path), header_re, path)
    if block is None:
        continue
    found[path] = headers(block)
    for name in CORE:
        if name not in found[path]:
            failures.append(f"{path}: the console location does not send {name}")
    if hsts and found[path].get("Strict-Transport-Security") != "$versola_hsts":
        failures.append(f"{path}: the console location must send Strict-Transport-Security $versola_hsts (empty over plain http)")

reference_path = "docker/versola-tools/nginx.conf.template"
reference = found.get(reference_path)
if reference:
    for path, hs in found.items():
        for name in CORE:
            if name in hs and name in reference and hs[name] != reference[name]:
                failures.append(f"{path}: {name} is {hs[name]!r}, {reference_path} has {reference[name]!r}")

scala = read("util/src/main/scala/versola/util/http/SecurityHeaders.scala")
m = re.search(r'val HtmlContentSecurityPolicy: String = "([^"]*)"', scala)
if not m:
    failures.append("SecurityHeaders.scala: HtmlContentSecurityPolicy not found")
else:
    for path, hs in found.items():
        if hs.get("Content-Security-Policy") not in (None, m.group(1)):
            failures.append(f"{path}: Content-Security-Policy differs from SecurityHeaders.HtmlContentSecurityPolicy ({m.group(1)!r})")

if failures:
    print("check-console-headers FAILED:", file=sys.stderr)
    for f in failures:
        print("  - " + f, file=sys.stderr)
    sys.exit(1)
print(f"check-console-headers: ok ({len(found)} config(s) compared)")
PY
