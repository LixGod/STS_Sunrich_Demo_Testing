package com.ship2ship.features.masters

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class ClientDto(
    val id: UUID,
    val code: String,
    val legalName: String,
    val roles: List<ClientRole>,
    val taxRegistrationNo: String?,
    val billingAddress: String?,
    val countryCode: String?,
    val phone: String?,
    val defaultCurrency: String?,
    val paymentTerms: String?,
    val active: Boolean,
    val updatedAt: Instant?,
    val version: Long?,
    val emails: List<String>,
    val phones: List<String>,
)

data class ClientRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,19}$", message = "Use 2-20 letters, digits or hyphens.")
    val code: String,
    @field:NotBlank @field:Size(max = 200) val legalName: String,
    @field:NotEmpty val roles: Set<ClientRole>,
    @field:Size(max = 50) val taxRegistrationNo: String? = null,
    @field:Size(max = 500) val billingAddress: String? = null,
    @field:Pattern(regexp = "^[A-Za-z]{2}$", message = "Use an ISO 3166 two-letter country code.") val countryCode: String? = null,
    @field:Pattern(regexp = "^[0-9+()\\-. ]{0,40}$", message = "Use digits, spaces and + ( ) - . only.") val phone: String? = null,
    @field:Pattern(regexp = "^[A-Za-z]{3}$", message = "Use an ISO 4217 currency code.") val defaultCurrency: String? = null,
    @field:Size(max = 500) val paymentTerms: String? = null,
    val emails: List<String>? = null,
    val phones: List<String>? = null,
)

data class QuickClientRequest(
    @field:NotBlank @field:Size(max = 200) val legalName: String,
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:Pattern(regexp = "^[A-Za-z]{2}$", message = "Use an ISO 3166 two-letter country code.") val countryCode: String? = null,
    @field:Pattern(regexp = "^[0-9+()\\-. ]{0,40}$", message = "Use digits, spaces and + ( ) - . only.") val phone: String? = null,
)

data class QuickClientDto(val client: ClientDto, val contact: ClientContactDto)

data class ClientContactDto(
    val id: UUID,
    val clientId: UUID,
    val fullName: String,
    val position: String?,
    val email: String?,
    val phone: String?,
    val primary: Boolean,
    val active: Boolean,
    val version: Long?,
)

data class ClientContactRequest(
    @field:NotBlank @field:Size(max = 150) val fullName: String,
    @field:Size(max = 100) val position: String? = null,
    @field:Email @field:Size(max = 254) val email: String? = null,
    @field:Pattern(regexp = "^[0-9+()\\-. ]{0,40}$", message = "Use digits, spaces and + ( ) - . only.") val phone: String? = null,
    val primary: Boolean = false,
)
