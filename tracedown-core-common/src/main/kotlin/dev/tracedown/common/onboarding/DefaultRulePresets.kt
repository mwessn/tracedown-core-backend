package dev.tracedown.common.onboarding

/**
 * The built-in starter Lace-script templates — the default content the
 * [OrgBootstrapSeeder] seeds into every new org (editable/deletable like any
 * other org preset). Scripts use `$p.baseUrl` — teams set it once as a project
 * variable and every template points at the right host. All scripts are
 * Lace-validator checked.
 */
object DefaultRulePresets {

    data class Preset(val name: String, val script: String)

    val PRESETS: List<Preset> = listOf(
        Preset(
            "Health check with latency budget",
            """
            // Health endpoint with a latency budget. Soft checks notify without
            // failing the run outright.
            get("${'$'}p.baseUrl/health")
            .expect(status: 200)
            .check(ttfb: { value: 800, op: "lt" }, totalDelayMs: { value: 2000, op: "lt" })
            """.trimIndent(),
        ),
        Preset(
            "Uptime ping",
            """
            // Plain availability ping.
            get("${'$'}p.baseUrl/")
            .expect(status: 200)
            """.trimIndent(),
        ),
        Preset(
            "Login + authenticated fetch",
            """
            // Login, then fetch an authenticated resource with the session token.
            // Set probeUser / probePassword as service variables (secret type).
            post("${'$'}p.baseUrl/auth/login", {
              body: json({ username: "${'$'}s.probeUser", password: "${'$'}s.probePassword" })
            })
            .expect(status: 200)
            .store({ "$${'$'}token": this.body.token })

            get("${'$'}p.baseUrl/api/me", {
              headers: { "Authorization": "Bearer $${'$'}token" }
            })
            .expect(status: 200)
            """.trimIndent(),
        ),
        Preset(
            "Create-read-delete smoke test",
            """
            // Create -> read -> delete round-trip: proves writes work end to end
            // and cleans up after itself.
            post("${'$'}p.baseUrl/api/items", {
              body: json({ name: "tracedown-probe" })
            })
            .expect(status: 201)
            .store({ "$${'$'}itemId": this.body.id })

            get("${'$'}p.baseUrl/api/items/$${'$'}itemId")
            .expect(status: 200)

            delete("${'$'}p.baseUrl/api/items/$${'$'}itemId")
            .expect(status: 204)
            """.trimIndent(),
        ),
        Preset(
            "List endpoint with size + latency limits",
            """
            // List endpoint: status, response-size ceiling, latency budget.
            get("${'$'}p.baseUrl/api/items?limit=10")
            .expect(status: 200, bodySize: { value: "1mb", op: "lt" })
            .check(totalDelayMs: { value: 2000, op: "lt" })
            """.trimIndent(),
        ),
        Preset(
            "Form submission",
            """
            // Form-encoded submission (contact form, newsletter, ...).
            post("${'$'}p.baseUrl/contact", {
              body: form({ email: "probe@example.com", message: "tracedown probe" })
            })
            .expect(status: 200)
            """.trimIndent(),
        ),
        Preset(
            "Legacy redirect integrity",
            """
            // A legacy path must still land on a real page.
            get("${'$'}p.baseUrl/old-path", { redirects: { follow: true, max: 3 } })
            .expect(status: 200)
            """.trimIndent(),
        ),
    )
}
