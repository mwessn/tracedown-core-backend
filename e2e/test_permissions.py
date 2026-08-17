"""
E2E: Invite -> accept -> permission enforcement.

Invites a member, accepts the invite (activating the account), logs in as
the new member, and verifies org-section permission enforcement: an
authenticated member with no granted sections can read their own profile
but is denied privileged operations. Depends on test_service_lifecycle
(uses h.admin_token).
"""

import test_helpers as h

_MEMBER_EMAIL = "e2e-perms@example.com"
_MEMBER_PASSWORD = "Member123!x"
_member_token = None


@h.log_test("Invite a new member", reset_db_before=False)
def test_invite_member():
    if not h.admin_token:
        h.skip_test("No admin_token -- test_service_lifecycle must run first")

    status, body = h.api("POST", "/api/v1/invites", {"email": _MEMBER_EMAIL}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert body.get("ok") is True, f"Expected ok=true, got {body}"

    # The invite created a stub org_users row (status='invited').
    rows = h.query_db(
        "SELECT ou.status FROM org_users ou JOIN users u ON ou.user_id = u.id "
        f"WHERE u.email = '{_MEMBER_EMAIL}'"
    )
    assert rows and rows[0] == "invited", f"Expected an 'invited' org_users row, got {rows}"
    print(f"  Invited {_MEMBER_EMAIL} (status=invited)")


@h.log_test("Accept the invite via the public token", reset_db_before=False)
def test_accept_invite():
    # The raw token is emailed, not returned by the API -- read it from the DB.
    rows = h.query_db(
        "SELECT ou.invite_token FROM org_users ou JOIN users u ON ou.user_id = u.id "
        f"WHERE u.email = '{_MEMBER_EMAIL}'"
    )
    assert rows and rows[0], "No invite_token found for the invited member"
    token = rows[0]

    # Public info endpoint works without auth.
    status, body = h.api("GET", f"/api/v1/invites/{token}")
    h.assert_status(status, 200, f"body={body}")
    assert body.get("email") == _MEMBER_EMAIL, f"Invite info email mismatch: {body}"

    # Accept: sets the password and activates the account.
    status, body = h.api("POST", f"/api/v1/invites/{token}/accept",
                         {"password": _MEMBER_PASSWORD, "displayName": "E2E Member"})
    h.assert_status(status, 200, f"body={body}")

    rows = h.query_db(
        "SELECT ou.status, u.is_active FROM org_users ou JOIN users u ON ou.user_id = u.id "
        f"WHERE u.email = '{_MEMBER_EMAIL}'"
    )
    parts = rows[0].split("|")
    assert parts[0] == "active", f"Expected status=active after accept, got {parts}"
    print(f"  Accepted invite; member active={parts[1]}")


@h.log_test("Member logs in and is permission-restricted", reset_db_before=False)
def test_member_permissions_enforced():
    global _member_token
    status, body = h.api("POST", "/api/v1/auth/login",
                         {"email": _MEMBER_EMAIL, "password": _MEMBER_PASSWORD})
    h.assert_status(status, 200, f"login body={body}")
    _member_token = body["token"]

    # Positive control: any authenticated user can read their own profile.
    status, body = h.api("GET", "/api/v1/auth/me", token=_member_token)
    h.assert_status(status, 200, f"/auth/me body={body}")
    assert body.get("isOwner") in (False, None), f"Member must not be owner: {body}"
    print("  Member can read /auth/me (session valid, non-owner)")

    # No 'users' section -> listing members is forbidden (not a 401 -- session is valid).
    status, _ = h.api("GET", "/api/v1/users", token=_member_token)
    assert status == 403, f"Expected 403 listing users without the 'users' section, got {status}"
    print("  Member denied GET /users (no users section) -> 403")

    # No 'workspaces' write -> creating a workspace is forbidden.
    status, _ = h.api("POST", "/api/v1/workspaces", {"name": "Nope"}, token=_member_token)
    assert status == 403, f"Expected 403 creating a workspace without write, got {status}"
    print("  Member denied POST /workspaces (no workspaces write) -> 403")


def get_tests():
    return [
        test_invite_member,
        test_accept_invite,
        test_member_permissions_enforced,
    ]
