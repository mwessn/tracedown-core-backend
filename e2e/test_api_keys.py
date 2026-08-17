"""
E2E: API key lifecycle through the gateway HTTP API.

Tests create, list (redacted), revoke, and delete operations.
Depends on test_service_lifecycle having run first (uses h.admin_token).
"""

import test_helpers as h


@h.log_test("Create API key", reset_db_before=False)
def test_create_api_key():
    if not h.admin_token:
        h.skip_test("No admin_token -- test_service_lifecycle must run first")

    status, body = h.api("POST", "/api/v1/api-keys",
                         {"name": "E2E Key"}, h.admin_token)
    h.assert_status(status, 201, f"body={body}")

    # The plaintext key is returned only on creation
    assert "key" in body and body["key"], f"Expected key field, got {body}"
    assert body.get("name") == "E2E Key", f"Expected name='E2E Key', got {body.get('name')}"

    # Store for subsequent tests
    global _key_id
    _key_id = body["id"]
    print(f"  Created API key id={_key_id[:8]}..., key present=True")


@h.log_test("List API keys -- key field is redacted", reset_db_before=False)
def test_list_api_keys():
    status, body = h.api("GET", "/api/v1/api-keys", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    # body may be a list or a paginated wrapper with "items"
    items = body if isinstance(body, list) else body.get("items", body.get("data", []))
    assert len(items) >= 1, f"Expected at least 1 API key, got {len(items)}"

    # Find the key we created
    our_key = next((k for k in items if k.get("id") == _key_id), None)
    assert our_key is not None, f"Created key {_key_id} not found in list"

    # The plaintext key must NOT be returned on list
    assert our_key.get("key") is None, f"Expected key=null on list, got {our_key.get('key')}"
    print(f"  List contains {len(items)} key(s), key field correctly null")


@h.log_test("Revoke API key", reset_db_before=False)
def test_revoke_api_key():
    status, body = h.api("POST", f"/api/v1/api-keys/{_key_id}/revoke",
                         token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert body.get("ok") is True, f"Expected ok=true, got {body}"
    print(f"  Revoked key {_key_id[:8]}...")


@h.log_test("Delete API key", reset_db_before=False)
def test_delete_api_key():
    status, body = h.api("DELETE", f"/api/v1/api-keys/{_key_id}",
                         token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert body.get("ok") is True, f"Expected ok=true, got {body}"
    print(f"  Deleted key {_key_id[:8]}...")


# Module-level state
_key_id = None


def get_tests():
    return [
        test_create_api_key,
        test_list_api_keys,
        test_revoke_api_key,
        test_delete_api_key,
    ]
