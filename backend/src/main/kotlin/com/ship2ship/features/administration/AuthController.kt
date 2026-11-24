package com.ship2ship.features.administration

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.SessionUser
import com.ship2ship.core.web.ApiException
import com.ship2ship.integrations.auth.ExternalAuthClient
import com.ship2ship.integrations.auth.ExternalAuthUnavailableException
import com.ship2ship.integrations.auth.ExternalIdentity
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.http.HttpHeaders
import com.ship2ship.core.web.Versioning
import org.springframework.web.bind.annotation.RestController

/**
 * Login and logout through the existing authentication system (K6). The browser receives only the HttpOnly
 * session cookie; credentials travel once in the JSON body over TLS and are never logged or stored.
 */
@RestController
@RequestMapping("/api/v1")
class AuthController(
    private val externalAuth: ExternalAuthClient,
    private val userService: UserService,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val securityContextRepository: SecurityContextRepository,
) {

    @PostMapping("/auth/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): MeDto {
        val identity = try {
            externalAuth.authenticate(request.username.trim(), request.password)
        } catch (ex: ExternalAuthUnavailableException) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE", "Sign-in is temporarily unavailable.")
        }
        if (identity == null) {
            audit.recordIndependently(AuditEntry(AuditAction.LOGIN_FAILED, actorName = request.username.trim()))
            throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password.")
        }
        val user = userService.findActiveBySubject(identity.subjectId)
        if (user == null) {
            audit.recordIndependently(AuditEntry(AuditAction.LOGIN_REJECTED, actorName = request.username.trim(),
                details = mapOf("subjectId" to identity.subjectId)))
            throw ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "Your Ship2Ship account is not active. Contact an administrator.")
        }

        // New session id on login prevents session fixation.
        httpRequest.getSession(true)
        httpRequest.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(
            SessionUser(user.id, user.fullName ?: user.externalSubjectId ?: "User", identity.sessionToken), null, emptyList(),
        )
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, httpRequest, httpResponse)

        userService.recordLogin(user.id, identity.mfaVerified)
        return me()
    }

    @PostMapping("/auth/logout")
    fun logout(httpRequest: HttpServletRequest): ResponseEntity<Void> {
        val sessionUser = SecurityContextHolder.getContext().authentication?.principal as? SessionUser
        if (sessionUser != null) {
            audit.recordIndependently(AuditEntry(AuditAction.LOGOUT, "USER", sessionUser.userId))
            sessionUser.externalSessionToken?.let { externalAuth.logout(ExternalIdentity(sessionUser.userId.toString(), sessionToken = it)) }
        }
        httpRequest.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(): MeDto {
        val user = accessPolicy.currentUser()
        return MeDto(
            id = user.id,
            fullName = user.fullName,
            workEmail = userService.workEmail(user.id),
            language = user.language,
            permissions = user.permissions,
            grants = user.grants.map { GrantDto(it.roleCode, it.branchId) },
            directPermissions = user.grants.filter { it.roleCode == "DIRECT" && it.branchId == null }
                .flatMapTo(sortedSetOf()) { it.permissions },
            globalPermissions = user.grants.filter { it.branchId == null }.flatMapTo(sortedSetOf()) { it.permissions },
        )
    }

    @PutMapping("/me/preferences")
    fun updatePreferences(@Valid @RequestBody request: PreferencesRequest): ResponseEntity<Void> {
        userService.updateLanguage(accessPolicy.currentUser().id, request.language)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me/profile")
    fun profile(): ResponseEntity<UserDetailDto> =
        userService.ownProfile(accessPolicy.currentUser().id).let { Versioning.ok(it, it.version) }

    @PutMapping("/me/profile")
    fun updateProfile(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: OwnProfileUpdateRequest,
    ): ResponseEntity<UserDetailDto> =
        userService.updateOwnProfile(accessPolicy.currentUser().id, Versioning.requireIfMatch(ifMatch), request)
            .let { Versioning.ok(it, it.version) }
}
