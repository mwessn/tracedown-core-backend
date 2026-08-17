"""
E2E: Domain management (org_domains).

Adds a domain, reads back its challenge token, runs verification, and
removes it. The e2e stack runs in trusted-domain mode, so verification of
a placeholder domain resolves without live DNS/HTTP; this exercises the
domain CRUD + challenge issuance + persistence. Depends on
test_service_lifecycle (uses h.admin_token).
"""

import test_helpers as h

_DOMAIN = "e2e-example.test"
_domain_id = None


@h.log_test("Create a domain and receive a challenge token", reset_db_before=False)
def test_create_domain():
    global _domain_id
    if not h.admin_token:
        h.skip_test("No admin_token -- test_service_lifecycle must run first")

    status, body = h.api("POST", "/api/v1/domains",
                         {"domain": _DOMAIN, "verificationType": "dns-01"}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    _domain_id = body["id"]
    assert body["domain"] == _DOMAIN, f"domain mismatch: {body}"
    assert body.get("challenge"), f"Expected a challenge token, got {body.get('challenge')}"
    print(f"  Created domain {_DOMAIN} id={_domain_id[:8]}... status={body.get('status')}")

    rows = h.query_db(f"SELECT domain, status FROM org_domains WHERE domain = '{_DOMAIN}'")
    assert rows, "Domain row not persisted in org_domains"
    print(f"  Persisted: {rows[0]}")


@h.log_test("Domain appears in the list", reset_db_before=False)
def test_list_domains():
    if not _domain_id:
        h.skip_test("No domain id")
    status, body = h.api("GET", "/api/v1/domains", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    items = body if isinstance(body, list) else body.get("items", [])
    assert any(d.get("id") == _domain_id for d in items), f"Domain not in list: {body}"
    print(f"  List contains {len(items)} domain(s)")


@h.log_test("Verify endpoint returns a status", reset_db_before=False)
def test_verify_domain():
    if not _domain_id:
        h.skip_test("No domain id")
    status, body = h.api("POST", f"/api/v1/domains/{_domain_id}/verify", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert "verified" in body, f"Expected a 'verified' field, got {body}"
    print(f"  Verify -> verified={body.get('verified')}, status={body.get('status')}")


@h.log_test("Delete the domain", reset_db_before=False)
def test_delete_domain():
    if not _domain_id:
        h.skip_test("No domain id")
    status, body = h.api("DELETE", f"/api/v1/domains/{_domain_id}", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    print(f"  Deleted domain {_domain_id[:8]}...")


def get_tests():
    return [
        test_create_domain,
        test_list_domains,
        test_verify_domain,
        test_delete_domain,
    ]
