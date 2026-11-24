package com.ship2ship.features.resources

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ResourcePage<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long)
data class ResourceCodeInput(val code: String, val label: String, val active: Boolean = true, val costHeadId: UUID? = null)
data class ResourceCodeDto(val code: String, val label: String, val active: Boolean, val version: Long, val costHeadId: UUID? = null)
data class ResourceUnitInput(val code: String, val label: String, val dimension: String, val active: Boolean = true)
data class ResourceUnitDto(val code: String, val label: String, val dimension: String, val active: Boolean, val version: Long)
data class ResourcePolicyInput(val mode: String, val warningDays: Int?, val requirementsConfigured: Boolean)
data class ResourcePolicyDto(val mode: String, val warningDays: Int?,
                             val requirementsConfigured: Boolean, val version: Long)
data class ResourceRequirementInput(val resourceKind: String, val scopeCode: String,
                                    val certificateTypeCode: String, val active: Boolean = true)
data class ResourceRequirementDto(val id: UUID, val resourceKind: String,
                                  val scopeCode: String, val certificateTypeCode: String, val active: Boolean, val version: Long)

data class EquipmentModelInput(val categoryCode: String, val code: String,
                               val name: String, val active: Boolean = true,
                               val standardRate: BigDecimal? = null, val standardRateCurrency: String? = null)
data class EquipmentModelDto(val id: UUID, val categoryCode: String,
                             val code: String, val name: String, val active: Boolean, val version: Long,
                             val standardRate: BigDecimal?, val standardRateCurrency: String?)

/** Stock per model. Original counts every registered unit; revised leaves out damaged, lost and retired ones. */
data class InventoryRowDto(val modelId: UUID, val modelCode: String, val modelName: String, val categoryCode: String,
                           val categoryLabel: String, val original: Int, val inUse: Int, val available: Int,
                           val maintenance: Int, val damaged: Int, val lost: Int, val retired: Int, val revised: Int)
data class InventoryEventDto(val jobId: UUID, val jobNumber: String, val result: String, val notes: String?,
                             val resolution: String?, val at: Instant)
data class InventoryUnitDto(val assetId: UUID, val assetIdentifier: String, val status: String, val inUse: Boolean,
                            val jobId: UUID?, val jobNumber: String?, val history: List<InventoryEventDto>)

data class AssetInput(val branchId: UUID?, val modelId: UUID,
                      val assetIdentifier: String, val dimensionsText: String? = null,
                      val swlValue: BigDecimal? = null, val swlUnit: String? = null, val swlSource: String? = null,
                      val mblValue: BigDecimal? = null, val mblUnit: String? = null, val mblSource: String? = null,
                      val homeLocation: String? = null, val status: String = "AVAILABLE")
data class AssetDto(val id: UUID, val branchId: UUID?, val modelId: UUID,
                    val categoryCode: String, val assetIdentifier: String, val dimensionsText: String?,
                    val swlValue: BigDecimal?, val swlUnit: String?, val swlSource: String?,
                    val mblValue: BigDecimal?, val mblUnit: String?, val mblSource: String?,
                    val homeLocation: String?, val status: String, val version: Long)

data class PersonnelInput(val personnelIdentifier: String, val fullName: String,
                          val appUserId: UUID? = null, val availabilityNote: String? = null, val active: Boolean = true,
                          val competencies: List<String> = emptyList())
data class PersonnelDto(val id: UUID, val personnelIdentifier: String, val fullName: String,
                        val appUserId: UUID?, val availabilityNote: String?, val active: Boolean,
                        val competencies: List<String>, val version: Long)

data class VendorInput(val name: String, val contacts: String? = null,
                       val contractFrom: LocalDate? = null, val contractTo: LocalDate? = null, val active: Boolean = true)
data class VendorDto(val id: UUID, val name: String, val contacts: String?,
                     val contractFrom: LocalDate?, val contractTo: LocalDate?, val active: Boolean, val version: Long)
data class CraftInput(val vendorId: UUID, val identifier: String, val name: String,
                      val craftType: String, val technicalData: String? = null,
                      val bollardPullValue: BigDecimal? = null, val bollardPullUnit: String? = null,
                      val bollardPullSource: String? = null, val homeLocation: String? = null, val active: Boolean = true)
data class CraftDto(val id: UUID, val vendorId: UUID, val identifier: String, val name: String,
                    val craftType: String, val technicalData: String?, val bollardPullValue: BigDecimal?,
                    val bollardPullUnit: String?, val bollardPullSource: String?, val homeLocation: String?,
                    val active: Boolean, val version: Long)

data class CertificateInput(val typeCode: String, val certificateNumber: String, val issuer: String,
                            val issueDate: LocalDate, val expiryDate: LocalDate?)
data class CertificateEditInput(val typeCode: String, val certificateNumber: String, val issuer: String,
                                val issueDate: LocalDate, val expiryDate: LocalDate?, val reason: String? = null)
data class CertificateDto(val id: UUID, val ownerKind: String, val ownerId: UUID, val typeCode: String,
                          val certificateNumber: String, val issuer: String, val issueDate: LocalDate,
                          val expiryDate: LocalDate?, val fileId: UUID?, val fileName: String?,
                          val scanState: String?, val version: Long)
data class InspectionInput(val inspectedAt: LocalDate, val result: String, val source: String,
                           val nextDueAt: LocalDate? = null)
data class InspectionEditInput(val inspectedAt: LocalDate, val result: String, val source: String,
                               val nextDueAt: LocalDate? = null, val reason: String? = null)
data class ResourceChangeDto(val occurredAt: java.time.Instant, val actorName: String?, val details: String?)
data class InspectionDto(val id: UUID, val assetId: UUID, val inspectedAt: LocalDate, val result: String,
                         val source: String, val nextDueAt: LocalDate?, val version: Long)

data class AllocationInput(val kind: String, val resourceId: UUID, val startAt: Instant, val endAt: Instant,
                           val exceptionReason: String? = null)
data class ResourceJobContextDto(val jobId: UUID, val jobNumber: String, val timeZone: String)
data class ResourceChoiceDto(val id: UUID, val identifier: String, val label: String, val category: String?)
data class ResourceChoicesDto(val assets: List<ResourceChoiceDto>, val people: List<ResourceChoiceDto>,
                              val craft: List<ResourceChoiceDto>, val policy: ResourcePolicyDto)
data class AllocationDecisionInput(val decision: String, val reason: String? = null)
data class AllocationDto(val id: UUID, val jobId: UUID, val kind: String, val resourceId: UUID,
                         val identifier: String, val category: String?, val startAt: Instant, val endAt: Instant,
                         val timeZone: String, val status: String, val collisionIds: List<UUID>,
                         val eligibilityMessages: List<String>, val exceptionReason: String?,
                         val requestedBy: UUID?, val approvedBy: UUID?, val decisionReason: String?,
                         val version: Long)
