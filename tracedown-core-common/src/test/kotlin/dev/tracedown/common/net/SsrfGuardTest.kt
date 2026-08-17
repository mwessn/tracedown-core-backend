package dev.tracedown.common.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the outbound-webhook SSRF guard. The write-time syntax check must
 * reject non-https and obvious private/internal literals, and the delivery-time
 * check must additionally block anything that resolves to a private address.
 */
class SsrfGuardTest {

    @Test
    fun `syntax check rejects non-https schemes`() {
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "http://example.com/hook"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "ftp://example.com"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "file:///etc/passwd"))
    }

    @Test
    fun `syntax check rejects loopback and private literals`() {
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://127.0.0.1/x"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://10.0.0.5/x"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://192.168.1.1/x"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://169.254.169.254/latest/meta-data"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://[::1]/x"))
    }

    @Test
    fun `syntax check rejects internal hostnames`() {
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://localhost/x"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://gateway.railway.internal/x"))
        assertNotNull(SsrfGuard.validateUrlSyntax("url", "https://db.internal/x"))
    }

    @Test
    fun `syntax check accepts a normal public https url and null`() {
        assertNull(SsrfGuard.validateUrlSyntax("url", "https://hooks.example.com/services/abc"))
        assertNull(SsrfGuard.validateUrlSyntax("url", null))
        // Templated host is deferred to delivery-time (only scheme enforced here).
        assertNull(SsrfGuard.validateUrlSyntax("url", "https://\$o.endpoint/hook"))
    }

    @Test
    fun `isBlockedAddress covers loopback link-local private cgnat and ipv6 ula`() {
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("172.16.9.9")))
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("192.168.0.1")))
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("169.254.1.1")))
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("100.64.0.1"))) // CGNAT
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("0.0.0.0")))
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("::1")))
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName("fc00::1"))) // IPv6 ULA
        // A public address is not blocked.
        assertEquals(false, SsrfGuard.isBlockedAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun `assertAllowed throws on non-https and internal host`() {
        assertFailsWith<SsrfGuard.BlockedException> { SsrfGuard.assertAllowed("http://example.com/x") }
        assertFailsWith<SsrfGuard.BlockedException> { SsrfGuard.assertAllowed("https://localhost/x") }
        assertFailsWith<SsrfGuard.BlockedException> { SsrfGuard.assertAllowed("https://127.0.0.1/x") }
        // An unresolved templated host cannot resolve → blocked, never dialed.
        assertFailsWith<SsrfGuard.BlockedException> { SsrfGuard.assertAllowed("https://\$o.host/x") }
    }
}
