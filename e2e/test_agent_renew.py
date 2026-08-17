"""
E2E: Agent certificate renewal endpoint (proof-of-possession).

/internal/agents/renew re-issues a certificate for an existing agent only
when the request is signed by the agent's *current* private key. Without
that proof it must be rejected. The full happy path (which requires the
agent's private key) is covered by the agent's own unit tests; here we
verify the endpoint is wired and enforces the signature check.
"""

import test_helpers as h

_BOGUS_CSR = "-----BEGIN CERTIFICATE REQUEST-----\nbogus\n-----END CERTIFICATE REQUEST-----\n"


@h.log_test("Renew rejects a forged signature for a known agent", reset_db_before=False)
def test_renew_rejects_forged_signature():
    # e2e-agent is registered by the stack at startup. A renewal request with
    # a bogus CSR + signature cannot prove possession of the current key.
    status, body = h.api("POST", "/internal/agents/renew", {
        "slug": "e2e-agent",
        "csrPem": _BOGUS_CSR,
        "signature": "Zm9yZ2Vk",  # base64("forged")
    })
    print(f"  renew(forged) -> status={status}")
    assert status in (400, 403), f"Expected 400/403 for a forged signature, got {status}: {body}"


@h.log_test("Renew rejects an unknown agent", reset_db_before=False)
def test_renew_rejects_unknown_agent():
    status, body = h.api("POST", "/internal/agents/renew", {
        "slug": "does-not-exist-agent",
        "csrPem": _BOGUS_CSR,
        "signature": "eA==",
    })
    print(f"  renew(unknown) -> status={status}")
    assert status in (400, 403, 404), f"Expected rejection for an unknown agent, got {status}: {body}"


def get_tests():
    return [
        test_renew_rejects_forged_signature,
        test_renew_rejects_unknown_agent,
    ]
