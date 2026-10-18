#!/usr/bin/env python3
"""Runs the OpenID Foundation FAPI 2.0 conformance plan against a local Versola.

    ./run.py suite up                    start the conformance suite (docker compose)
    ./run.py provision --issuer URL      create the tenant, clients and user the plan needs
    ./run.py run --issuer URL            run every module of the plan, write results/<stamp>/
    ./run.py all --issuer URL            the three above, in order
    ./run.py suite down

Python 3.9+, standard library only. See README.md for the setup around it (the issuer has to
be a public https URL, which `expose-local-stack.sh` arranges for a local stack).
"""
import argparse
import base64
import datetime
import http.cookiejar
import json
import os
import re
import secrets
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
STATE_DIR = os.path.join(HERE, ".state")
PLAN = "fapi2-security-profile-final-test-plan"
PREFIX = "fapi2-security-profile-final-"
ALIAS = "versola-fapi2"
CLIENTS = ("fapi2-client", "fapi2-client2")
USER_LOGIN = "fapi2-user"
# The suite posts its pages under this alias; the plan appends two dummy query parameters to
# the redirect URI, so both forms are registered.
CALLBACK = f"https://localhost.emobix.co.uk:8443/test/a/{ALIAS}/callback"
REDIRECT_URIS = [CALLBACK, CALLBACK + "?dummy1=lorem&dummy2=ipsum"]
DEFAULT_VARIANT = {
    "fapi_profile": "plain_fapi",
    "client_auth_type": "private_key_jwt",
    "sender_constrain": "dpop",
    "openid": "openid_connect",
    "authorization_request_type": "simple",
    "grant_management": "disabled",
}
# Modules that cannot run unattended: the suite waits for a person to refuse the login.
MANUAL = {PREFIX + "user-rejects-authentication"}
# Modules whose authorization URL is visited twice: the first visit must not log in.
TWO_VISITS = {PREFIX + "par-ensure-reused-request-uri-prior-to-auth-completion-succeeds"}
FINISHED = ("FINISHED", "INTERRUPTED")

INSECURE = ssl.create_default_context()
INSECURE.check_hostname = False
INSECURE.verify_mode = ssl.CERT_NONE


def log(msg):
    print(f"[conformance] {msg}", flush=True)


def die(msg):
    sys.exit(f"[conformance] error: {msg}")


# ──────────────────────────────── HTTP ────────────────────────────────

def http_json(method, url, body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=INSECURE) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Browser:
    """A cookie-keeping client that hands redirects back instead of following them."""

    def __init__(self):
        # Versola's cookies are Secure once the issuer is https; this client reaches it over plain
        # http on localhost, as a browser would, so the policy has to send them there too.
        self.jar = http.cookiejar.CookieJar(http.cookiejar.DefaultCookiePolicy(secure_protocols=("https", "http")))
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.HTTPSHandler(context=INSECURE),
            NoRedirect(),
        )

    def request(self, url, form=None, method=None):
        data = None if form is None else urllib.parse.urlencode(form).encode()
        req = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"))
        try:
            r = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as e:
            r = e
        body = r.read().decode(errors="replace")
        return r.status if hasattr(r, "status") else r.code, r.headers.get("Location"), body


# ──────────────────────────────── central ────────────────────────────────

class Central:
    """Central's admin API, either directly or through a throwaway curl container on the
    stack's docker network (central's admin port is not published on the host)."""

    def __init__(self, args):
        self.url = args.central_url
        self.secret = args.central_secret or os.environ.get("CENTRAL_RESOURCE_SECRET")
        self.network = args.docker_network
        self.container = args.central_container
        if self.url is None:
            self.url = "http://%s:8090" % self.container
            if not self.secret:
                out = subprocess.run(
                    ["docker", "inspect", self.container, "--format", "{{range .Config.Env}}{{println .}}{{end}}"],
                    capture_output=True, text=True)
                m = re.search(r"^CENTRAL_RESOURCE_SECRET=(.+)$", out.stdout, re.M)
                if not m:
                    die(f"cannot read CENTRAL_RESOURCE_SECRET from container {self.container}; "
                        "pass --central-url and --central-secret")
                self.secret = m.group(1)
            self.via_docker = True
        else:
            if not self.secret:
                die("--central-url needs --central-secret (or CENTRAL_RESOURCE_SECRET)")
            self.via_docker = False

    def call(self, method, path, body=None, query=None):
        url = self.url + path + ("?" + urllib.parse.urlencode(query) if query else "")
        if self.via_docker:
            cmd = ["docker", "run", "--rm", "-i", "--network", self.network, "curlimages/curl", "-s", "-m", "20",
                   "-u", "central:" + self.secret, "-X", method, "-w", "\n%{http_code}"]
            if body is not None:
                cmd += ["-H", "Content-Type: application/json", "--data-binary", "@-"]
            out = subprocess.run(cmd + [url], input=json.dumps(body) if body is not None else None,
                                 capture_output=True, text=True).stdout
            text, _, code = out.rpartition("\n")
            status = int(code or 0)
        else:
            auth = base64.b64encode(f"central:{self.secret}".encode()).decode()
            data = None if body is None else json.dumps(body).encode()
            req = urllib.request.Request(url, data=data, method=method, headers={
                "Authorization": "Basic " + auth, "Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    status, text = r.status, r.read().decode()
            except urllib.error.HTTPError as e:
                status, text = e.code, e.read().decode(errors="replace")
        try:
            return status, (json.loads(text) if text else None)
        except ValueError:
            return status, text


# ──────────────────────────────── state ────────────────────────────────

def state_path(tenant):
    return os.path.join(STATE_DIR, f"{tenant}.json")


def load_state(tenant):
    try:
        with open(state_path(tenant)) as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def save_state(tenant, state):
    os.makedirs(STATE_DIR, exist_ok=True)
    path = state_path(tenant)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(state, f, indent=2)


# ──────────────────────────────── suite ────────────────────────────────

def compose(*a):
    return subprocess.run(["docker", "compose", "-f", os.path.join(HERE, "docker-compose.yml"), *a])


def suite_up(args):
    if compose("up", "-d").returncode:
        die("docker compose up failed")
    log("waiting for the suite on %s" % args.suite)
    deadline = time.time() + 300
    while time.time() < deadline:
        try:
            if http_json("GET", args.suite + "/api/runner/available", timeout=5)[0] == 200:
                log("suite is up")
                return
        except OSError:
            pass
        time.sleep(3)
    die("the suite did not come up within five minutes (docker compose logs server)")


def suite_ready(args):
    try:
        return http_json("GET", args.suite + "/api/runner/available", timeout=5)[0] == 200
    except OSError:
        return False


# ──────────────────────────────── provision ────────────────────────────────

def provision(args):
    central = Central(args)
    state = load_state(args.tenant)
    tenant = args.tenant

    status, tenants = central.call("GET", "/configuration/tenants")
    if status != 200:
        die(f"central refused the admin call ({status}): {tenants}")
    if not any(t["id"] == tenant for t in tenants["tenants"]):
        status, body = central.call("POST", "/configuration/tenants", {
            "id": tenant, "description": "FAPI 2.0 conformance", "securityProfile": "fapi2"})
        if status != 201:
            die(f"creating tenant {tenant}: {status} {body}")
        log(f"tenant {tenant} created (securityProfile fapi2)")
        time.sleep(3)  # settings reach the tenant's row asynchronously
    else:
        log(f"tenant {tenant} exists")

    # Tokens must be signed with PS256 or ES256, which FAPI 2.0 requires.
    status, settings = central.call("GET", "/configuration/challenges/challenge-settings", query={"tenantId": tenant})
    if status != 200 or not settings.get("settings"):
        die(f"reading challenge settings: {status} {settings}")
    s = settings["settings"]
    _, jwks = central.call("GET", "/configuration/jwks")
    algs = {k["kid"]: k.get("alg") for k in jwks["keys"]}
    # A published key may be verify-only (no private half in central), so reuse only one this
    # runner generated earlier, and generate otherwise.
    if state.get("signingKeyId") in algs:
        s["signingKeyId"] = state["signingKeyId"]
    elif algs.get(s.get("signingKeyId")) not in ("PS256", "ES256") or not state.get("signingKeyId"):
        status, gen = central.call("POST", "/configuration/jwks/generate", query={"alg": "PS256"})
        if status != 201:
            die(f"generating a PS256 signing key: {status} {gen}")
        state["signingKeyId"] = s["signingKeyId"] = gen["kid"]
        log(f"generated signing key {gen['kid']}")
        save_state(tenant, state)
        time.sleep(3)  # the key reaches every verifier before anything signs with it
    s["clientAssertionMaxLifetimeSeconds"] = 900
    # Forty-odd logins in a row from one address must not be throttled.
    s["submissionLimits"] = {
        "otpRequest": [{"maxAttempts": 50, "windowSeconds": 60}],
        "otpSubmit": [{"maxAttempts": 50, "windowSeconds": 120}],
        "passwordSubmit": [{"maxAttempts": 1000, "windowSeconds": 900}],
        "passkeyAssertion": [{"maxAttempts": 50, "windowSeconds": 300}],
        "banDurationSeconds": 60,
    }
    status, body = central.call("PUT", "/configuration/challenges/challenge-settings", s)
    if status not in (200, 204):
        die(f"updating challenge settings: {status} {body}")

    state.setdefault("clients", {})
    _, existing = central.call("GET", "/configuration/clients", query={"tenantId": tenant, "limit": 100})
    existing_ids = {c["id"] for c in (existing or {}).get("clients", [])}
    for cid in CLIENTS:
        if cid in existing_ids and (cid in state["clients"] and not args.reset):
            log(f"client {cid} exists, keeping its key")
            continue
        if cid in existing_ids:
            central.call("DELETE", "/configuration/clients", query={"clientId": cid})
        status, body = central.call("POST", "/configuration/clients/web", {
            "tenantId": tenant, "id": cid, "clientName": {"en": "FAPI2 conformance " + cid},
            "redirectUris": REDIRECT_URIS,
            "allowedScopes": ["openid", "offline_access"],
            "accessTokenTtl": 3600, "theme": "default", "otpTemplateId": "default",
            "authMethod": "private_key_jwt", "generateJwks": "PS256",
            "dpopBoundAccessTokens": True, "dpopSigningAlgs": ["PS256"],
            "requirePushedAuthorizationRequests": True,
            "issueEdgeClientCertificate": False, "enrollEdgeClientCertificate": False,
            "authFlow": {"primary": {"credentials": ["login"], "inlinePassword": True, "factors": []},
                         "equivalents": {}, "otpType": "email"},
        })
        if status != 201:
            die(f"creating client {cid}: {status} {body}")
        state["clients"][cid] = body["privateKey"]
        log(f"client {cid} created")
        save_state(tenant, state)

    user = state.get("user")
    status, found = central.call("GET", "/users", query={"login": USER_LOGIN})
    users = found if isinstance(found, list) else (found or {}).get("users", [])
    uid = users[0]["id"] if users else None
    if uid is None:
        status, body = central.call("POST", "/users", {"login": USER_LOGIN, "email": USER_LOGIN + "@example.com"})
        if status not in (200, 201):
            die(f"creating the user: {status} {body}")
        uid = body["id"]
    if not user or user["userId"] != uid:
        user = {"login": USER_LOGIN, "password": "Fx1-" + secrets.token_urlsafe(12), "userId": uid}
    status, body = central.call("POST", "/users/password/set", {"userId": uid, "password": user["password"]})
    if status != 204:
        die(f"setting the user's password: {status} {body} (the endpoint is test-only: it is 404 in production)")
    state["user"] = user
    save_state(tenant, state)
    log("waiting for auth to pick the configuration up")
    time.sleep(args.settle)
    log(f"provisioned; credentials are in {state_path(tenant)}")


# ──────────────────────────────── run ────────────────────────────────

def parse_variant(pairs):
    variant = dict(DEFAULT_VARIANT)
    for p in pairs or []:
        k, _, v = p.partition("=")
        if not v:
            die(f"--variant wants key=value, got {p!r}")
        variant[k] = v
    return variant


def create_plan(args, state, variant):
    iss = args.issuer.rstrip("/")
    cfg = {
        "alias": ALIAS,
        "description": "Versola FAPI 2.0 " + " ".join(f"{k}={v}" for k, v in variant.items()),
        "server": {"discoveryUrl": iss + "/.well-known/openid-configuration"},
        "resource": {"resourceUrl": iss + "/userinfo", "resourceMethod": "GET"},
        "browser": [],
    }
    for name, cid in (("client", CLIENTS[0]), ("client2", CLIENTS[1])):
        cfg[name] = {"client_id": cid, "jwks": {"keys": [state["clients"][cid]]},
                     "dpop_signing_alg": "PS256", "scope": "openid offline_access"}
    url = "%s/api/plan?planName=%s&variant=%s" % (args.suite, PLAN, urllib.parse.quote(json.dumps(variant)))
    status, plan = http_json("POST", url, cfg)
    if status != 201 and status != 200:
        die(f"the suite refused the plan: {status} {plan}")
    return plan


class Driver:
    """Plays the user: opens the authorization URL the suite hands out, signs in at Versola over
    plain HTTP, and gives the suite the page it lands on back (the suite reads the URL fragment
    through a script, which a client with no JavaScript has to do by hand)."""

    def __init__(self, args, state):
        self.issuer = args.issuer.rstrip("/")
        self.local = args.local_issuer.rstrip("/")
        self.user = state["user"]

    def visit(self, url, login=True):
        b = Browser()
        url = url.replace(self.issuer, self.local)
        status, loc, body = b.request(url)
        if not login:
            return
        for _ in range(15):
            if status in (301, 302, 303, 307) and loc:
                if "localhost.emobix.co.uk" in loc:
                    self.hand_back(b, loc)
                    return
                url = loc if loc.startswith("http") else self.local + loc
                status, loc, body = b.request(url)
                continue
            step = re.search(r'versola-step" content="([^"]+)"', body)
            step = step.group(1) if step else None
            csrf = re.search(r'"csrf"\s*:\s*"([^"]+)"', body)
            csrf = csrf.group(1) if csrf else ""
            if step == "credential":
                status, loc, body = b.request(self.local + "/challenge/login-password", {
                    "login": self.user["login"], "password": self.user["password"], "csrf": csrf})
            elif step == "consent":
                status, loc, body = b.request(self.local + "/challenge/consent", {"scope": "openid", "csrf": csrf})
            elif status == 400:
                return  # an error page, which is what the negative modules expect
            else:
                log(f"  unhandled login step {step!r} (HTTP {status})")
                return

    @staticmethod
    def hand_back(b, loc):
        _, _, page = b.request(loc.split("#")[0])
        m = re.search(r"xhr\.open\(.POST., \"([^\"]+)\"", page)
        if m:
            submit = m.group(1).replace("\\/", "/")
            fragment = ("#" + loc.split("#", 1)[1]) if "#" in loc else ""
            req = urllib.request.Request(submit, data=fragment.encode(), method="POST",
                                         headers={"Content-Type": "text/plain"})
            try:
                urllib.request.urlopen(req, timeout=30, context=INSECURE).read()
            except urllib.error.URLError:
                pass


def run_module(args, plan_id, module, driver):
    status, r = http_json("POST", f"{args.suite}/api/runner?test={module}&plan={plan_id}")
    if status not in (200, 201):
        raise RuntimeError(f"cannot start {module}: {status} {r}")
    rid = r["id"]
    visited = 0
    deadline = time.time() + args.timeout
    info = {}
    idle = 0
    while time.time() < deadline:
        time.sleep(1)
        _, info = http_json("GET", f"{args.suite}/api/info/{rid}")
        if info["status"] in FINISHED:
            break
        _, runner = http_json("GET", f"{args.suite}/api/runner/{rid}")
        urls = (runner or {}).get("browser", {}).get("urls", [])
        idle = 0 if visited < len(urls) else idle + 1
        while visited < len(urls):
            visited += 1
            url = urls[visited - 1]
            driver.visit(url, login=not (module in TWO_VISITS and visited == 1))
            # what the suite's own browser automation does after a visit: some modules
            # (a request_uri used twice) wait for it
            http_json("POST", f"{args.suite}/api/runner/browser/{rid}/visit?url={urllib.parse.quote(url, safe='')}")
        if visited and idle >= 5 and idle % 5 == 0 and needs_review(args, rid):
            info["result"] = "REVIEW"
            break
    return rid, info


def needs_review(args, rid):
    """True when the test has stopped to ask for a screenshot of an error page, which only a
    person can supply."""
    _, entries = http_json("GET", f"{args.suite}/api/log/{rid}", timeout=120)
    return any(e.get("result") == "REVIEW" for e in entries or [])


def summarize(args, rid):
    """The conditions that failed or warned, from the test's log."""
    _, entries = http_json("GET", f"{args.suite}/api/log/{rid}", timeout=120)
    findings = []
    for e in entries or []:
        if e.get("result") in ("FAILURE", "WARNING"):
            findings.append({"result": e["result"], "condition": e.get("src"), "message": str(e.get("msg"))[:300],
                             **{k: str(e[k])[:200] for k in ("expected", "actual", "error") if k in e}})
    return findings, entries


def verdict(name, result, findings, expected):
    """'pass', 'expected' (a known finding: same result, same failing conditions) or 'regression'."""
    if result == "PASSED":
        return "pass"
    if result == "REVIEW":
        return "review"
    known = expected.get(name)
    if known is None or known.get("result") != result:
        return "regression"
    allowed = known.get("conditions", [])
    if all(any(f["condition"].startswith(a) for a in allowed) for f in findings):
        return "expected"
    return "regression"


def run(args):
    state = load_state(args.tenant)
    if not state.get("clients") or not state.get("user"):
        die(f"nothing provisioned for tenant {args.tenant}: run `provision` first")
    if not args.issuer:
        die("--issuer is required")
    if not suite_ready(args):
        die(f"the suite is not answering on {args.suite}: run `suite up`")
    with open(os.path.join(HERE, "expected.json")) as f:
        expected = {k: v for k, v in json.load(f).items() if not k.startswith("_")}

    variant = parse_variant(args.variant)
    plan = create_plan(args, state, variant)
    modules = [m["testModule"] for m in plan["modules"]]
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = os.path.join(HERE, "results", stamp)
    os.makedirs(os.path.join(out, "logs"))
    log(f"plan {plan['id']}: {len(modules)} modules; results in {out}")
    driver = Driver(args, state)

    rows = []
    for module in modules:
        short = module.removeprefix(PREFIX)
        if args.only and not any(o in short for o in args.only):
            continue
        if module in MANUAL:
            rows.append({"module": short, "status": "SKIPPED-MANUAL", "result": None, "findings": [], "verdict": "manual"})
            log(f"  {short}: not run (needs a person)")
            continue
        try:
            rid, info = run_module(args, plan["id"], module, driver)
            findings, entries = summarize(args, rid)
        except Exception as e:  # keep going: one broken module must not hide the rest
            rid, info, findings, entries = "-", {"status": "ERROR", "result": None}, [
                {"result": "FAILURE", "condition": "runner", "message": str(e)}], []
        result = info.get("result") or "TIMEOUT:" + str(info.get("status"))
        v = verdict(short, result, findings, expected)
        rows.append({"module": short, "id": rid, "status": info.get("status"), "result": result,
                     "findings": findings, "verdict": v})
        with open(os.path.join(out, "logs", short + ".json"), "w") as f:
            json.dump(entries, f, indent=1)
        log(f"  {short}: {result}" + ("" if v == "pass" else f" ({v})"))

    write_report(out, plan, variant, rows, expected, args)
    regressions = [r for r in rows if r["verdict"] == "regression"]
    log(f"{sum(r['verdict']=='pass' for r in rows)} passed, "
        f"{sum(r['verdict']=='review' for r in rows)} need a screenshot, "
        f"{sum(r['verdict']=='expected' for r in rows)} known, {len(regressions)} regressions; report: {out}/report.md")
    sys.exit(1 if regressions else 0)


def write_report(out, plan, variant, rows, expected, args):
    with open(os.path.join(out, "results.json"), "w") as f:
        json.dump({"plan": plan["id"], "variant": variant, "issuer": args.issuer, "modules": rows}, f, indent=1)
    lines = ["# Versola FAPI 2.0 conformance run", "",
             f"- plan: `{PLAN}` (`{plan['id']}`)",
             f"- variant: " + ", ".join(f"`{k}={v}`" for k, v in variant.items()),
             f"- issuer: {args.issuer}", "",
             "| module | result | verdict |", "|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['module']} | {r['result']} | {r['verdict']} |")
    notes = [r for r in rows if r["verdict"] in ("expected", "regression")]
    if notes:
        lines += ["", "## Findings", ""]
        for r in notes:
            lines.append(f"### {r['module']} — {r['verdict']}")
            if r["verdict"] == "expected":
                lines.append(f"Accepted: {expected[r['module']].get('why', '')}")
            for f in r["findings"]:
                detail = {k: f[k] for k in ("expected", "actual", "error") if k in f}
                lines.append(f"- {f['result']} `{f['condition']}`: {f['message']} {detail or ''}")
            lines.append("")
    with open(os.path.join(out, "report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")


# ──────────────────────────────── cli ────────────────────────────────

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["suite", "provision", "run", "all"])
    p.add_argument("action", nargs="?", choices=["up", "down", "status"], help="for `suite`")
    p.add_argument("--issuer", help="Versola's public https issuer, as the suite will fetch it")
    p.add_argument("--local-issuer", default="http://localhost:2821",
                   help="where this script reaches the same Versola to sign in (default %(default)s)")
    p.add_argument("--suite", default="https://localhost:8443", help="the suite's URL (default %(default)s)")
    p.add_argument("--tenant", default="fapi2", help="tenant to provision and test (default %(default)s)")
    p.add_argument("--central-url", help="central's admin URL; default: through docker on --docker-network")
    p.add_argument("--central-secret", help="central's admin secret (or CENTRAL_RESOURCE_SECRET)")
    p.add_argument("--central-container", default="versola-central")
    p.add_argument("--docker-network", default="versola-local_default")
    p.add_argument("--variant", action="append", metavar="KEY=VALUE", help="override a plan variant, e.g. sender_constrain=mtls")
    p.add_argument("--only", action="append", metavar="SUBSTRING", help="run only the modules whose name contains this")
    p.add_argument("--timeout", type=int, default=120, help="seconds a module may take (default %(default)s)")
    p.add_argument("--settle", type=int, default=20, help="provision: seconds to let auth's configuration cache catch up (default %(default)s)")
    p.add_argument("--reset", action="store_true", help="provision: register the clients again with fresh keys")
    args = p.parse_args()

    if args.command == "suite":
        if args.action == "up":
            suite_up(args)
        elif args.action == "down":
            compose("down")
        elif args.action == "status":
            log("up" if suite_ready(args) else "down")
        else:
            die("suite wants up, down or status")
    elif args.command == "provision":
        provision(args)
    elif args.command == "run":
        run(args)
    else:
        suite_up(args)
        provision(args)
        run(args)


if __name__ == "__main__":
    main()
