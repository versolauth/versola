"""The conformance plans this runner knows, one entry per suite.

A suite names the plan on the conformance suite, how its clients are registered with Versola, and
which of its modules need special handling. `run.py --suite NAME` picks one; add an entry here (and
an `expected/NAME.json`) to add a plan.
"""

CALLBACK_HOST = "https://localhost.emobix.co.uk:8443/test/a/"


def callback(alias):
    return f"{CALLBACK_HOST}{alias}/callback"


SUITES = {
    # FAPI 2.0 Security Profile (Final): private_key_jwt clients, PAR required, DPoP-bound tokens.
    "fapi2": {
        "plan": "fapi2-security-profile-final-test-plan",
        "prefix": "fapi2-security-profile-final-",
        "alias": "versola-fapi2",
        "tenant": "fapi2",
        "security_profile": "fapi2",
        "variant": {
            "fapi_profile": "plain_fapi",
            "client_auth_type": "private_key_jwt",
            "sender_constrain": "dpop",
            "openid": "openid_connect",
            "authorization_request_type": "simple",
            "grant_management": "disabled",
        },
        "clients": {
            "auth": "private_key_jwt",
            "ids": ("fapi2-client", "fapi2-client2"),
            "scopes": ["openid", "offline_access"],
            "scope_param": "openid offline_access",
            # the plan appends two dummy query parameters to the redirect URI
            "redirect_suffixes": ["", "?dummy1=lorem&dummy2=ipsum"],
        },
        "user": "fapi2-user",
        "resource": "/userinfo",
        # the suite waits for a person to refuse the login
        "manual": {"user-rejects-authentication"},
        # the authorization URL is visited twice; the first visit must not log in
        "two_visits": {"par-ensure-reused-request-uri-prior-to-auth-completion-succeeds"},
    },
    # OpenID Connect Basic OP: static clients with a secret, on a standard-profile tenant.
    "oidcc-basic": {
        "plan": "oidcc-basic-certification-test-plan",
        "prefix": "",
        "alias": "versola-basic",
        "tenant": "conformance",
        "security_profile": "standard",
        "variant": {"server_metadata": "discovery", "client_registration": "static_client"},
        "clients": {
            "auth": "client_secret",
            "ids": ("conf-client", "conf-client2"),
            "scopes": ["openid", "profile", "email", "address", "phone", "offline_access"],
            "scope_param": None,
            "redirect_suffixes": [""],
        },
        "user": "oidctest",
        "resource": None,
        "manual": set(),
        "two_visits": set(),
    },
    # OpenID Connect Config OP: checks of the discovery document only, nothing to provision.
    "oidcc-config": {
        "plan": "oidcc-config-certification-test-plan",
        "prefix": "",
        "alias": "versola-config",
        "tenant": None,
        "variant": {},
        "clients": None,
        "user": None,
        "resource": None,
        "manual": set(),
        "two_visits": set(),
    },
}
