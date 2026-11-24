package com.ship2ship.features.masters

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

private const val CODE_PATTERN = "^[A-Z0-9][A-Z0-9-]{1,19}$"
private const val CODE_MESSAGE = "Use 2-20 upper-case letters, digits or hyphens."

data class BranchDto(
    val id: UUID,
    val code: String,
    val name: String,
    val timezone: String,
    val address: String?,
    val active: Boolean,
    val updatedAt: Instant?,
    val version: Long?,
)

data class BranchRequest(
    @field:NotBlank @field:Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) val code: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:NotBlank @field:Size(max = 64) val timezone: String,
    @field:Size(max = 500) val address: String? = null,
)

/** Activate / deactivate. Referenced masters are never deleted (SRS FR-MD-01). */
data class ActiveStatusRequest(
    @field:NotNull val active: Boolean,
    @field:Size(max = 500) val reason: String? = null,
)
