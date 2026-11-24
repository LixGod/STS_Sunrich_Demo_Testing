package com.ship2ship.core.security

import com.ship2ship.core.web.ProblemHandler
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter
import org.springframework.security.web.header.writers.StaticHeadersWriter
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.NegatedRequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Browser sessions: after login the server keeps the security context in an HttpOnly, SameSite=Strict cookie
 * session; no token is exposed to JavaScript. CSRF uses the SPA cookie/header pattern (XSRF-TOKEN / X-XSRF-TOKEN),
 * which Angular HttpClient supports out of the box. Authorisation detail lives in [AccessPolicy].
 */
@Configuration
class SecurityConfig(private val jsonMapper: JsonMapper) {

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        securityContextRepository: SecurityContextRepository,
        @Value("\${springdoc.api-docs.enabled:false}") apiDocsEnabled: Boolean,
        @Value("\${management.endpoints.web.exposure.include:}") exposedEndpoints: String,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                if (apiDocsEnabled) {
                    it.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                }
                // Only where a profile exposes metrics (local measurement); elsewhere the path stays denied.
                if ("metrics" in exposedEndpoints.split(',').map(String::trim)) {
                    it.requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**").authenticated()
                }
                it.requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll()
            }
            .csrf { it.spa() }
            .addFilterAfter(CsrfCookieFilter(), BasicAuthenticationFilter::class.java)
            .securityContext { it.securityContextRepository(securityContextRepository) }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation { fixation -> fixation.changeSessionId() }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    writeProblem(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in.")
                }.accessDeniedHandler { _, response, _ ->
                    writeProblem(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "The request was refused.")
                }
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers { headers ->
                // File downloads may be shown in a frame of this site (e.g. the source document beside the entry form);
                // every other response may not be framed at all.
                val downloads = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/files/download/**")
                val others = NegatedRequestMatcher(downloads)
                headers.frameOptions { it.disable() }
                headers.addHeaderWriter(DelegatingRequestMatcherHeaderWriter(others, XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY)))
                headers.addHeaderWriter(DelegatingRequestMatcherHeaderWriter(downloads, XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)))
                headers.addHeaderWriter(DelegatingRequestMatcherHeaderWriter(downloads,
                    StaticHeadersWriter("Content-Security-Policy", "frame-ancestors 'self'")))
                // The API serves only JSON; Swagger UI (local profile only) needs its own scripts, so it is exempt.
                if (!apiDocsEnabled) {
                    headers.addHeaderWriter(DelegatingRequestMatcherHeaderWriter(others,
                        StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")))
                }
            }
        return http.build()
    }

    private fun writeProblem(response: HttpServletResponse, status: HttpStatus, code: String, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        jsonMapper.writeValue(response.outputStream, ProblemHandler.problemDetail(status, code, detail))
    }

    /** Makes sure the XSRF-TOKEN cookie is issued on the first response, so the SPA can send it back. */
    private class CsrfCookieFilter : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
            chain.doFilter(request, response)
        }
    }
}
