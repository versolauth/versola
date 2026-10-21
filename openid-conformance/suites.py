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
        "signing_alg": "PS256",  # FAPI 2.0 allows PS256 and ES256 only
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
            "config_keys": ("client", "client2"),
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
        "signing_alg": "RS256",  # OpenID Connect Core 15.1: every OP supports RS256
        "variant": {"server_metadata": "discovery", "client_registration": "static_client"},
        "clients": {
            "auth": "client_secret",
            "ids": ("conf-client", "conf-client2", "conf-client-post"),
            # the plan config keys the clients go under; oidcc-server-client-secret-post reads its own
            "config_keys": ("client", "client2", "client_secret_post"),
            "scopes": ["openid", "profile", "email", "address", "phone", "offline_access"],
            "scope_param": None,
            "redirect_suffixes": [""],
        },
        "user": "oidctest",
        # What the plan needs beyond the defaults, applied by `provision`: the tenant's `acr`
        # vocabulary (acr_values must be answered with an acr claim) and a user who has the claims
        # the scope and claims modules look for.
        "setup": {
            "challenge_settings": {"acrVocabulary": {"1": ["password"], "2": ["password", "otp"]}},
            "user_claims": {
                "name": "Oidc Test", "given_name": "Oidc", "family_name": "Test", "middle_name": "Q",
                "nickname": "oidc", "preferred_username": "oidctest", "profile": "https://example.com/oidctest",
                "picture": "https://example.com/oidctest.png", "website": "https://example.com",
                "gender": "other", "birthdate": "1990-01-01", "zoneinfo": "Asia/Almaty", "locale": "en-US",
                "email_verified": True, "updated_at": 1760000000, "phone_number": "+77001234567", "phone_number_verified": True,
                "address": {"formatted": "1 Test St, Almaty, 050000, KZ", "street_address": "1 Test St",
                            "locality": "Almaty", "region": "Almaty", "postal_code": "050000", "country": "KZ"},
            },
        },
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
