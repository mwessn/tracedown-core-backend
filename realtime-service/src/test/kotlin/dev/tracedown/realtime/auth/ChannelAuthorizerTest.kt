package dev.tracedown.realtime.auth

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DB-free coverage of the channel gate's routing decisions. Grant-based
 * decisions on resolvable resources are exercised by the integration suite (they
 * need the shared database); here we pin the paths that never touch the DB:
 *
 * - non-resource channels (`org:`, `session:`, `agents:`) are not gated here and
 *   must be allowed through, and
 * - a resource channel naming a malformed id is denied outright, before any
 *   lookup — a client can't smuggle a non-UUID past the gate.
 */
class ChannelAuthorizerTest {

    private val user = UUID.randomUUID()
    private val org = UUID.randomUUID()

    @Test
    fun `non-resource channels are allowed through the gate`() {
        assertTrue(ChannelAuthorizer.canSubscribe(user, org, "org:$org"))
        assertTrue(ChannelAuthorizer.canSubscribe(user, org, "session:${UUID.randomUUID()}"))
        assertTrue(ChannelAuthorizer.canSubscribe(user, org, "agents:all"))
    }

    @Test
    fun `resource channel with a malformed id is denied`() {
        assertFalse(ChannelAuthorizer.canSubscribe(user, org, "service:not-a-uuid"))
        assertFalse(ChannelAuthorizer.canSubscribe(user, org, "svc-edit:garbage"))
        assertFalse(ChannelAuthorizer.canSubscribe(user, org, "project:123"))
        assertFalse(ChannelAuthorizer.canRelay(user, org, "svc-edit:nope"))
    }
}
