package dev.tracedown.common.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseFactory {

    /** Env override so constrained deployments can shrink per-service pools. */
    private val envPoolSize = System.getenv("DB_POOL_SIZE")?.toIntOrNull()

    /**
     * How long a session may sit inside a transaction doing nothing before
     * PostgreSQL ends it (`idle_in_transaction_session_timeout`), in seconds;
     * `DB_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS`, default 60, 0 for no limit.
     *
     * An open transaction is not free for anyone else: it holds back vacuum,
     * and it holds back the event feed, which reads only what was written
     * below the oldest transaction still open. A connection a bug or a stalled
     * thread leaves in an open transaction would otherwise hold both forever.
     */
    val idleInTransactionTimeoutSeconds: Int =
        System.getenv("DB_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS")?.toIntOrNull()?.coerceAtLeast(0) ?: 60

    /** The size of the pool [init] made, for callers that bound their own share of it. */
    @Volatile
    var poolSize: Int = 10
        private set

    fun init(
        jdbcUrl: String,
        username: String,
        password: String,
        maximumPoolSize: Int = envPoolSize ?: 10
    ): HikariDataSource {
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            this.maximumPoolSize = maximumPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            connectionInitSql = "SET idle_in_transaction_session_timeout = '${idleInTransactionTimeoutSeconds}s'"
            validate()
        })

        Database.connect(dataSource)
        poolSize = maximumPoolSize

        // Initialise every table object here, on this one thread, before the
        // caller launches jobs or serves requests: touched concurrently for
        // the first time, the tables' mutual references deadlock the JVM's
        // class initialisation with no error and no log. See [Tables].
        dev.tracedown.common.models.Tables.preload()

        return dataSource
    }
}
