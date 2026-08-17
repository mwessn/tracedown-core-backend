package dev.tracedown.migrator

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("schema-migrator")

private const val MAX_RETRIES = 30
private const val RETRY_DELAY_MS = 2000L

fun main() {
    val jdbcUrl = requireEnv("DATABASE_URL")
    val username = requireEnv("DATABASE_USER")
    val password = requireEnv("DATABASE_PASSWORD")

    val dataSource = connectWithRetry(jdbcUrl, username, password)

    try {
        val locations = mutableListOf("classpath:db/initial_schema", "classpath:db/migrations")
        val extraLocations = System.getenv("FLYWAY_LOCATIONS")
        if (!extraLocations.isNullOrBlank()) {
            locations.clear()
            locations.addAll(extraLocations.split(","))
        }

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(*locations.toTypedArray())
            .table("flyway_schema_history")
            .load()
            .migrate()

        log.info("Migration complete: {} migrations applied", result.migrationsExecuted)
        exitProcess(0)
    } catch (e: Exception) {
        log.error("Migration failed", e)
        exitProcess(1)
    } finally {
        dataSource.close()
    }
}

/** Retries HikariCP connection until Postgres is reachable. */
private fun connectWithRetry(jdbcUrl: String, username: String, password: String): HikariDataSource {
    for (attempt in 1..MAX_RETRIES) {
        try {
            val ds = HikariDataSource(HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = username
                this.password = password
                maximumPoolSize = 2
                isAutoCommit = true
                connectionTimeout = 5000
            })
            ds.connection.use { it.isValid(2) }
            log.info("Connected to database (attempt {})", attempt)
            return ds
        } catch (e: Exception) {
            if (attempt == MAX_RETRIES) {
                log.error("Failed to connect after {} attempts", MAX_RETRIES)
                throw e
            }
            log.info("Waiting for database... (attempt {}/{})", attempt, MAX_RETRIES)
            Thread.sleep(RETRY_DELAY_MS)
        }
    }
    throw IllegalStateException("unreachable")
}

private fun requireEnv(name: String): String =
    System.getenv(name) ?: error("Required environment variable $name is not set")
