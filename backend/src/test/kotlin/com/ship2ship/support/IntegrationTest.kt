package com.ship2ship.support

import com.mysql.cj.MysqlConnection
import com.mysql.cj.Query
import com.mysql.cj.interceptors.QueryInterceptor
import com.mysql.cj.log.Log
import com.mysql.cj.protocol.Resultset
import com.mysql.cj.protocol.ServerSession
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["ship2ship.test.external-db"], havingValue = "false", matchIfMissing = true)
class MySqlContainerConfig {

    /** The approved production database version (decision K4). One container is shared by all test classes. */
    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer =
        MySQLContainer(DockerImageName.parse("mysql:8.4")).withUrlParam("connectionTimeZone", "UTC")
}

/**
 * Counts the data statements the application sends to MySQL (registered as a driver query interceptor in
 * application-test.yml), so a test can pin how many round trips a request costs.
 */
class StatementCounter : QueryInterceptor {
    override fun init(conn: MysqlConnection?, props: Properties?, log: Log?): QueryInterceptor = this
    override fun <T : Resultset?> preProcess(sql: Supplier<String>?, query: Query?): T? {
        if (sql?.get()?.trimStart()?.take(6)?.uppercase() in DATA) count.incrementAndGet()
        return null
    }
    override fun executeTopLevelOnly() = true
    override fun destroy() {}
    override fun <T : Resultset?> postProcess(sql: Supplier<String>?, query: Query?, result: T, session: ServerSession?): T = result

    companion object {
        private val DATA = setOf("SELECT", "INSERT", "UPDATE", "DELETE")
        private val count = AtomicLong()

        /** The block's result and the statements it sent; tests run one at a time and background monitors are off. */
        fun <R> measure(block: () -> R): Pair<R, Long> = count.get().let { before -> block() to count.get() - before }
    }
}

/** Full application against MySQL 8.4, with helpers to sign in through the real login endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlContainerConfig::class)
abstract class IntegrationTest {

    @Autowired
    protected lateinit var mvc: MockMvc

    @Autowired
    protected lateinit var json: JsonMapper

    @Autowired
    protected lateinit var jdbc: JdbcClient

    protected fun login(username: String, password: String = DEV_PASSWORDS.getValue(username)): MockHttpSession {
        val result = mvc.perform(
            post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("username" to username, "password" to password))),
        ).andExpect(status().isOk).andReturn()
        return result.request.getSession(false) as MockHttpSession
    }

    protected fun body(value: Any): String = json.writeValueAsString(value)

    protected fun read(content: String): JsonNode = json.readTree(content)

    protected fun auditCount(action: String, entityType: String? = null): Long =
        jdbc.sql("SELECT COUNT(*) FROM audit_event WHERE action = :action AND (:type IS NULL OR entity_type = :type)")
            .param("action", action).param("type", entityType).query(Long::class.java).single()

    protected companion object {
        val DEV_PASSWORDS = mapOf(
            "admin" to "admin", "sts.admin" to "sts", "ops.mumbai" to "ops", "finance" to "finance", "commercial" to "commercial", "approver" to "approver", "technical" to "technical", "marine" to "marine",
            "stranger" to "stranger", "ops.manager" to "ops",
        )

        // Synthetic seed identifiers (db/dev-seed/R__dev_synthetic_data.sql).
        const val BRANCH_MUM = "01920000-0000-7000-9000-000000000201"
        const val BRANCH_KDL = "01920000-0000-7000-9000-000000000202"
        const val BRANCH_FUJ = "01920000-0000-7000-9000-000000000203"
        const val USER_OPS_MUMBAI = "01920000-0000-7000-9000-000000000402"
        const val USER_FINANCE = "01920000-0000-7000-9000-000000000403"
        const val USER_ADMIN = "01920000-0000-7000-9000-000000000401"
        const val ROLE_OPERATIONS = "01920000-0000-7000-8000-000000000004"
        const val ROLE_AUDITOR = "01920000-0000-7000-8000-000000000008"

        // Synthetic commercial seed (db/dev-seed/R__dev_synthetic_data_commercial.sql).
        const val UNIT_MT = "01920000-0000-7000-9000-000000000601"
        const val GRADE_PROPANE = "01920000-0000-7000-9000-000000000611"
        const val GRADE_BUTANE = "01920000-0000-7000-9000-000000000612"
        const val CLIENT_CHARTERER = "01920000-0000-7000-9000-000000000701"
        const val CLIENT_OWNER = "01920000-0000-7000-9000-000000000702"
        const val CONTACT_CHARTERER = "01920000-0000-7000-9000-000000000711"
        const val VESSEL_MOTHER = "01920000-0000-7000-9000-000000000721"
        const val VESSEL_DAUGHTER = "01920000-0000-7000-9000-000000000722"
        const val VESSEL_TANKER = "01920000-0000-7000-9000-000000000723"
    }
}
