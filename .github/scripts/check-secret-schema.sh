#!/usr/bin/env bash
#
# Asserts that the secret schema scripts/gen-env.scala publishes (secrets.schema.json) says
# the same thing as what the generator actually does, for every target that writes secrets
# (docker-local, vps, k8s):
#
#   1. each service's *.generated-secrets.env holds exactly the keys the schema lists for
#      that service -- in both directions: a generated key the schema lacks, and a schema key
#      nothing generated;
#   2. each service's *.conf asks for exactly those keys: every required `${NAME}` placeholder
#      in it is a schema key, and every key the schema lists for it is a placeholder. (The
#      optional `${?NAME}` form is a tuning knob with a default, not a secret, and is ignored.)
#      This is the check that catches a secret written into a .conf as a literal, or one
#      placeholdered but never generated;
#   3. a value one entry shares between services (CENTRAL_SECRET_KEY, CLIENT_SECRETS_SECRET,
#      and vps's POSTGRES_PASSWORD) is the identical value in each service's file. (k8s's
#      POSTGRES_PASSWORD is an entry per service: separate values, nothing to compare.);
#   4. a base64url value decodes to the byte count the schema declares;
#   5. a secret the schema puts in a file of its own (the `utils` client's private key) is in
#      that file and in no *.generated-secrets.env;
#   6. secrets.schema.json holds no secret value.
#
# Like check-chart-secret-vars.sh this compares GENERATED ARTIFACTS, not source text: the keys
# arrive through `Seq`s joined with `++`, and some placeholders through variables, so reading
# the Scala would get the answer wrong. Values are only ever compared or measured here, never
# printed: every message names a key.
#
# Needs scala-cli (CI has it -- see ci-cd.yml's "Set up Scala CLI"), bash, grep, sed, comm.
# Usage: bash .github/scripts/check-secret-schema.sh

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

SERVICES=(auth central edge)
TARGETS=(docker-local vps k8s)
failed=0

fail() { echo "    FAIL: $*" >&2; failed=1; }

# gen-env writes .local/env/<target>/ relative to the working directory, and .local is not
# gitignored, so each target runs in a scratch directory of its own.
#   - docker-local needs nothing. vps needs the two values versola-cli passes it (the values
#     are never connected to). Both read the target name from stdin, as entrypoint.sh does.
#   - k8s is the interactive target. Every prompt it reaches falls back to its default when
#     stdin is empty, so it is told its target with a flag and given /dev/null to read.
generate() {
  local target="$1"
  mkdir -p "$work/$target"
  (
    cd "$work/$target"
    case "$target" in
      k8s)
        scala-cli run --server=false "$repo_root/scripts/gen-env.scala" -- --target=k8s </dev/null >/dev/null
        ;;
      *)
        ENV_NAME=ci-check AUTH_URL=https://ci.invalid POSTGRES_HOST=ci.invalid:5432 \
          scala-cli run --server=false "$repo_root/scripts/gen-env.scala" <<< "$target" >/dev/null
        ;;
    esac
  )
}

# secrets.schema.json has one entry per line (see SecretSchema.toJson), so line tools are enough
# to read it without a JSON parser.
schema_names_for() { # schema service
  { grep "\"services\":\[[^]]*\"$2\"" "$1" || true; } | sed 's/.*"name":"\([A-Z0-9_]*\)".*/\1/' | sort -u
}
schema_all_entries() { grep '^    {"name"' "$1"; }

env_keys() { cut -d= -f1 "$1" | sort -u; }
env_value() { { grep "^$2=" "$1" || true; } | head -n 1 | cut -d= -f2-; }

# The required placeholders of a generated config, comment lines left out.
conf_placeholders() {
  # CLIENT_CERT_ISSUER_NAME (k8s's central.conf) is the one required placeholder that is not a
  # secret: the chart sets it from its own issuer (see gen-env.scala's clientCertificatesBlock),
  # so it is neither generated nor in the shared Secret.
  { grep -v '^[[:space:]]*#' "$1" | grep -o '\${[A-Z][A-Z0-9_]*}' || true; } | tr -d '${}' | sort -u \
    | { grep -vx 'CLIENT_CERT_ISSUER_NAME' || true; }
}

# Prints the difference of two sorted key lists as `<what>: <keys>`; fails when there is one.
same_keys() { # label expected-name expected actual-name actual
  local label="$1" expected_name="$2" expected="$3" actual_name="$4" actual="$5"
  local only_expected only_actual
  only_expected="$(comm -23 <(echo "$expected") <(echo "$actual") | tr '\n' ' ')"
  only_actual="$(comm -13 <(echo "$expected") <(echo "$actual") | tr '\n' ' ')"
  if [ -z "$only_expected" ] && [ -z "$only_actual" ]; then
    return 0
  fi
  [ -n "$only_expected" ] && fail "$label: in $expected_name but not in $actual_name: $only_expected"
  [ -n "$only_actual" ] && fail "$label: in $actual_name but not in $expected_name: $only_actual"
  return 0
}

for target in "${TARGETS[@]}"; do
  echo "==> $target: generating with scripts/gen-env.scala"
  generate "$target"
  dir="$work/$target/.local/env/$target"
  schema="$dir/secrets.schema.json"

  if [ ! -s "$schema" ]; then
    fail "$target: gen-env wrote no secrets.schema.json"
    continue
  fi
  grep -q '"schemaVersion": 1,' "$schema" || fail "$target: secrets.schema.json has no schemaVersion 1"
  grep -q "\"target\": \"$target\"," "$schema" || fail "$target: secrets.schema.json is not for target $target"

  # 1 and 2: per service, the schema against the generated env file and against the conf.
  for svc in "${SERVICES[@]}"; do
    from_schema="$(schema_names_for "$schema" "$svc")"
    if [ -z "$from_schema" ]; then
      fail "$target/$svc: the schema lists no secret for this service"
      continue
    fi
    same_keys "$target/$svc generated-secrets.env" "the schema" "$from_schema" \
      "$svc.generated-secrets.env" "$(env_keys "$dir/$svc.generated-secrets.env")"
    same_keys "$target/$svc.conf placeholders" "the schema" "$from_schema" \
      "$svc.conf" "$(conf_placeholders "$dir/$svc.conf")"
  done

  # 3, 4: values shared between services are identical; base64url values have their size.
  while IFS= read -r entry; do
    name="$(sed 's/.*"name":"\([A-Z0-9_]*\)".*/\1/' <<< "$entry")"
    services="$(sed 's/.*"services":\[\([^]]*\)\].*/\1/' <<< "$entry" | tr -d '"' | tr ',' ' ')"
    type="$(sed 's/.*"type":"\([a-z0-9-]*\)".*/\1/' <<< "$entry")"
    size="$(sed 's/.*"size":\([0-9a-z]*\),.*/\1/' <<< "$entry")"

    # `utils` holds its value in a file, not an env file: checked below.
    case " $services " in *" utils "*) continue ;; esac

    first=""
    for svc in $services; do
      value="$(env_value "$dir/$svc.generated-secrets.env" "$name")"
      if [ -z "$value" ]; then
        fail "$target/$svc: $name has no value"
        continue
      fi
      if [ -z "$first" ]; then
        first="$value"
      elif [ "$value" != "$first" ]; then
        fail "$target: $name differs between the services that share it ($services)"
      fi
    done

    if [ "$type" = "base64url" ] && [ -n "$first" ]; then
      if ! [[ "$first" =~ ^[A-Za-z0-9_-]+$ ]]; then
        fail "$target: $name is not URL-safe base64 without padding"
      elif [ $(( ${#first} * 3 / 4 )) -ne "$size" ]; then
        fail "$target: $name does not decode to the $size bytes the schema declares"
      fi
    fi
  done < <(schema_all_entries "$schema")

  # 5: a secret kept in a file of its own is there, and in no env file.
  while IFS= read -r entry; do
    name="$(sed 's/.*"name":"\([A-Z0-9_]*\)".*/\1/' <<< "$entry")"
    file="$(sed 's/.*"file":"\([^"]*\)".*/\1/' <<< "$entry")"
    [ -s "$dir/$file" ] || fail "$target: $name should be in $file, which is missing or empty"
    for svc in "${SERVICES[@]}"; do
      if grep -q "^$name=" "$dir/$svc.generated-secrets.env"; then
        fail "$target/$svc: $name is in a generated-secrets.env, which versola-cli loads into the container"
      fi
    done
  done < <(schema_all_entries "$schema" | grep -v '"file":null')

  # 6: no value in the schema. Values of 16+ characters only: a short one (k8s's typed-in
  # default password) could by chance be a word the schema uses.
  for svc in "${SERVICES[@]}"; do
    while IFS= read -r line; do
      key="${line%%=*}"
      value="${line#*=}"
      if [ "${#value}" -ge 16 ] && grep -qF -e "$value" "$schema"; then
        fail "$target: secrets.schema.json contains the value of $key"
      fi
    done < "$dir/$svc.generated-secrets.env"
  done
  echo "    checked $target"
done

echo
if [ "$failed" -ne 0 ]; then
  cat >&2 <<'MSG'
FAIL: the secret schema in scripts/gen-env.scala (SecretSchema.specs) and what gen-env
generates disagree.

A new secret needs all of: a SecretSpec in SecretSchema.specs, a `${NAME}` placeholder in
the service's config (secretField/secretKeyField), a line in that service's
writeGeneratedSecrets list, and -- for k8s -- an entry in `versola.requiredSecretVars`
(k8s/versola/templates/_helpers.tpl).
MSG
  exit 1
fi

echo "OK: the secret schema matches what gen-env generates for ${#TARGETS[@]} targets."
