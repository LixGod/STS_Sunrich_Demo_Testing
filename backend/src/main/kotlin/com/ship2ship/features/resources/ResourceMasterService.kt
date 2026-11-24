package com.ship2ship.features.resources

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.EquipmentLookup
import com.ship2ship.core.security.EquipmentOptionRef
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

@Service
class ResourceMasterService(
    private val jdbc: JdbcClient, private val access: ResourceAccess, private val policy: AccessPolicy,
    private val audit: AuditService, private val files: FileService, private val references: ReferenceLookup,
) : EquipmentLookup {
    private val codeTables = mapOf("categories" to "resource_category", "competencies" to "resource_competency",
        "certificate-types" to "resource_certificate_type")

    @Transactional(readOnly = true)
    fun codes(kind: String): List<ResourceCodeDto> {
        policy.requireAny(Permissions.MASTER_RESOURCE_READ)
        val table = codeTables[kind] ?: throw NotFoundException("Resource catalogue")
        val head = if (kind == "categories") "cost_head_id" else "NULL"
        return jdbc.sql("SELECT code,label,active,version,$head FROM $table ORDER BY code").query { rs, _ ->
            ResourceCodeDto(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getLong(4), rs.getBytes(5).uuid())
        }.list()
    }

    @Transactional
    fun saveCode(kind: String, code: String?, expected: Long?, input: ResourceCodeInput): ResourceCodeDto {
        policy.requireAny(Permissions.MASTER_RESOURCE_WRITE)
        val table = codeTables[kind] ?: throw NotFoundException("Resource catalogue")
        val cleanCode = text(code ?: input.code, "code", 40).uppercase()
        if (!Regex("^[A-Z][A-Z0-9_]{0,39}$").matches(cleanCode)) invalid("code", "Use capital letters, digits and underscores.")
        val label = text(input.label, "label", 100)
        val costHead = input.costHeadId?.takeIf { kind == "categories" }?.also { id ->
            val head = references.costHeads(listOf(id))[id]
            if (head?.active != true || head.kind == "REVENUE") invalid("costHeadId", "Choose an active expense cost head.")
        }
        if (code == null) {
            try {
                if (kind == "categories") jdbc.sql("""INSERT INTO resource_category
                    (code,label,sort_order,active,cost_head_id,created_at,created_by,updated_at,updated_by,version)
                    VALUES (:c,:l,999,:a,:h,:t,:u,:t,:u,0)""")
                    .params(mapOf("c" to cleanCode, "l" to label, "a" to input.active, "h" to costHead?.bytes(),
                        "t" to access.now(), "u" to access.actor().bytes())).update()
                else jdbc.sql("INSERT INTO $table (code,label,active,updated_at,updated_by,version) VALUES (:c,:l,:a,:t,:u,0)")
                    .params(mapOf("c" to cleanCode, "l" to label, "a" to input.active, "t" to access.now(), "u" to access.actor().bytes())).update()
            } catch (_: DuplicateKeyException) { throw ConflictException("DUPLICATE_CODE", "This code is already in use.") }
        } else {
            val current = codes(kind).firstOrNull { it.code == cleanCode } ?: throw NotFoundException("Resource catalogue entry")
            Versioning.checkCurrent(expected ?: -1, current.version)
            val head = if (kind == "categories") ",cost_head_id=:h" else ""
            jdbc.sql("UPDATE $table SET label=:l,active=:a$head,updated_at=:t,updated_by=:u,version=version+1 WHERE code=:c")
                .params(mapOf("l" to label, "a" to input.active, "t" to access.now(), "u" to access.actor().bytes(), "c" to cleanCode)
                    + (if (kind == "categories") mapOf("h" to costHead?.bytes()) else emptyMap())).update()
        }
        audit.record(AuditEntry(if (code == null) AuditAction.CREATE else AuditAction.UPDATE,
            "RESOURCE_CATALOGUE", null, details = mapOf("kind" to kind, "code" to cleanCode)))
        return codes(kind).first { it.code == cleanCode }
    }

    @Transactional(readOnly=true)
    fun units():List<ResourceUnitDto> {
        policy.requireAny(Permissions.MASTER_RESOURCE_READ)
        return jdbc.sql("SELECT code,label,dimension,active,version FROM resource_unit ORDER BY code")
            .query { rs,_ -> ResourceUnitDto(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getLong(5)) }.list()
    }

    @Transactional
    fun saveUnit(code:String?,expected:Long?,input:ResourceUnitInput):ResourceUnitDto {
        policy.requireAny(Permissions.MASTER_RESOURCE_WRITE)
        val clean=text(code?:input.code,"code",30).uppercase()
        if(!Regex("^[A-Z][A-Z0-9_]{0,29}$").matches(clean)) invalid("code","Use capital letters, digits and underscores.")
        val label=text(input.label,"label",100); val dimension=text(input.dimension,"dimension",40).uppercase()
        if(code==null) {
            try { jdbc.sql("""INSERT INTO resource_unit (code,label,dimension,active,updated_at,updated_by,version)
                VALUES (:c,:l,:d,:a,:t,:u,0)""")
                .params(mapOf("c" to clean,"l" to label,"d" to dimension,"a" to input.active,
                    "t" to access.now(),"u" to access.actor().bytes())).update()
            } catch (_:DuplicateKeyException) { throw ConflictException("DUPLICATE_CODE","Unit code is already in use.") }
        } else {
            val before=units().firstOrNull { it.code==clean }?:throw NotFoundException("Resource unit")
            Versioning.checkCurrent(expected?:-1,before.version)
            if(before.dimension!=dimension && jdbc.sql("""SELECT (SELECT COUNT(*) FROM equipment_asset WHERE swl_unit=:c OR mbl_unit=:c)
                +(SELECT COUNT(*) FROM resource_craft WHERE bollard_pull_unit=:c)""").param("c",clean)
                    .query(Long::class.java).single()>0) throw BusinessRuleException("UNIT_IN_USE","Unit dimension cannot change while used.")
            jdbc.sql("""UPDATE resource_unit SET label=:l,dimension=:d,active=:a,
                updated_at=:t,updated_by=:u,version=version+1 WHERE code=:c""")
                .params(mapOf("l" to label,"d" to dimension,"a" to input.active,
                    "t" to access.now(),"u" to access.actor().bytes(),"c" to clean)).update()
        }
        audit.record(AuditEntry(if(code==null) AuditAction.CREATE else AuditAction.UPDATE,"RESOURCE_UNIT",null,
            details=mapOf("code" to clean)))
        return units().first { it.code==clean }
    }

    @Transactional(readOnly = true)
    fun models(): List<EquipmentModelDto> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        return jdbc.sql("SELECT id,category_code,code,name,active,version,standard_rate,standard_rate_currency FROM equipment_model ORDER BY name")
            .query { rs, _ -> model(rs) }.list()
    }

    @Transactional(readOnly = true)
    fun modelPage(q: String?, page: Int, size: Int, sort: String?): ResourcePage<EquipmentModelDto> =
        masterPage("equipment_model", "category_code,code,name,active,version,standard_rate,standard_rate_currency", "name", q, page, size, sort,
            mapOf("name" to "name", "code" to "code", "categoryCode" to "category_code"), ::model)

    @Transactional(readOnly = true)
    fun modelById(id: UUID): EquipmentModelDto {
        val row=jdbc.sql("SELECT id,category_code,code,name,active,version,standard_rate,standard_rate_currency FROM equipment_model WHERE id=:id")
            .param("id",id.bytes()).query { rs,_ -> model(rs) }.optional().orElseThrow { NotFoundException("Equipment model") }
        access.company(Permissions.MASTER_RESOURCE_READ)
        return row
    }

    @Transactional
    fun saveModel(id: UUID?, expected: Long?, input: EquipmentModelInput): EquipmentModelDto {
        access.company(Permissions.MASTER_RESOURCE_WRITE)
        val name = text(input.name, "name", 150); val code = text(input.code, "code", 40).uppercase()
        val category = codes("categories").firstOrNull { it.code == input.categoryCode && it.active }
            ?: invalid("categoryCode", "Choose an active category.")
        val rate = input.standardRate
        val rateCurrency = input.standardRateCurrency?.trim()?.uppercase()?.ifBlank { null }
        if (rate == null && rateCurrency != null) invalid("standardRate", "Enter the rate for this currency or clear the currency.")
        if (rate != null && (rate.signum() < 0 || rate.scale() > 4)) invalid("standardRate", "Enter a non-negative rate with at most 4 decimals.")
        if (rate != null && (rateCurrency == null || references.currency(rateCurrency)?.active != true))
            invalid("standardRateCurrency", "Choose an active currency.")
        val actual = id ?: UuidV7.generate()
        if (id == null) {
            try { jdbc.sql("""INSERT INTO equipment_model
                (id,category_code,code,name,active,standard_rate,standard_rate_currency,created_at,created_by,updated_at,updated_by,version)
                VALUES (:id,:cat,:code,:name,:active,:rate,:cur,:t,:u,:t,:u,0)""")
                .params(mapOf("id" to actual.bytes(), "cat" to category.code,
                    "code" to code, "name" to name, "active" to input.active, "rate" to rate, "cur" to rateCurrency,
                    "t" to access.now(), "u" to access.actor().bytes())).update()
            } catch (_: DuplicateKeyException) { throw ConflictException("DUPLICATE_CODE", "This model code is already in use.") }
        } else {
            val before = models().firstOrNull { it.id == id } ?: throw NotFoundException("Equipment model")
            Versioning.checkCurrent(expected ?: -1, before.version)
            if (before.code != code || before.categoryCode != category.code) invalid("code", "Model code and category cannot change.")
            jdbc.sql("""UPDATE equipment_model SET name=:n,active=:a,standard_rate=:rate,standard_rate_currency=:cur,
                updated_at=:t,updated_by=:u,version=version+1 WHERE id=:id""")
                .params(mapOf("n" to name, "a" to input.active, "rate" to rate, "cur" to rateCurrency,
                    "t" to access.now(), "u" to access.actor().bytes(), "id" to id.bytes())).update()
        }
        audit.record(AuditEntry(if (id == null) AuditAction.CREATE else AuditAction.UPDATE, "EQUIPMENT_MODEL", actual))
        return models().first { it.id == actual }
    }

    /** The job a unit is on now: an allocation covering this moment, or a craft trip whose return is not yet recorded. */
    private val currentJob = """COALESCE(
        (SELECT r.job_id FROM job_resource_allocation r WHERE r.asset_id=a.id AND r.status='ACTIVE'
            AND r.start_at<=:now AND r.end_at>:now ORDER BY r.start_at LIMIT 1),
        (SELECT cm.job_id FROM movement_asset ma JOIN craft_movement cm ON cm.id=ma.movement_id
            LEFT JOIN job_recovery jr ON jr.job_id=cm.job_id
            LEFT JOIN recovery_asset ra ON ra.job_id=cm.job_id AND ra.allocation_id=ma.allocation_id
            WHERE ma.asset_id=a.id AND ma.status='ACTIVE' AND cm.status='ACTIVE' AND ra.id IS NULL
            AND (jr.status IS NULL OR jr.status='OPEN') ORDER BY cm.departed_at DESC LIMIT 1))"""

    @Transactional(readOnly = true)
    fun inventory(): List<InventoryRowDto> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        return jdbc.sql("""SELECT m.id,m.code,m.name,m.category_code,c.label,COUNT(u.id),
            SUM(u.status='AVAILABLE' AND u.job_id IS NOT NULL),SUM(u.status='AVAILABLE' AND u.job_id IS NULL),
            SUM(u.status='MAINTENANCE'),SUM(u.status='DAMAGED'),SUM(u.status='LOST'),SUM(u.status='RETIRED')
            FROM equipment_model m JOIN resource_category c ON c.code=m.category_code
            LEFT JOIN (SELECT a.id,a.model_id,a.status,$currentJob job_id FROM equipment_asset a) u
                ON u.model_id=m.id
            GROUP BY m.id,m.code,m.name,m.category_code,c.label,c.sort_order
            ORDER BY c.sort_order,m.name""")
            .param("now", access.now()).query { rs, _ ->
                val original = rs.getInt(6); val damaged = rs.getInt(10); val lost = rs.getInt(11); val retired = rs.getInt(12)
                InventoryRowDto(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    original, rs.getInt(7), rs.getInt(8), rs.getInt(9), damaged, lost, retired, original - damaged - lost - retired)
            }.list()
    }

    @Transactional(readOnly = true)
    override fun equipmentOptions(): List<EquipmentOptionRef> =
        jdbc.sql("""SELECT m.id,m.name,c.label,c.cost_head_id,m.standard_rate,m.standard_rate_currency,
            (SELECT COUNT(*) FROM equipment_asset a WHERE a.model_id=m.id AND a.status='AVAILABLE' AND $currentJob IS NULL)
            FROM equipment_model m JOIN resource_category c ON c.code=m.category_code
            WHERE m.active=TRUE AND c.active=TRUE ORDER BY c.sort_order,m.name""")
            .param("now", access.now()).query { rs, _ ->
                EquipmentOptionRef(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3), rs.getBytes(4).uuid(),
                    rs.getBigDecimal(5), rs.getString(6), rs.getInt(7))
            }.list()

    @Transactional(readOnly = true)
    fun inventoryUnits(modelId: UUID): List<InventoryUnitDto> {
        modelById(modelId)
        val history = jdbc.sql("""SELECT r.asset_id,ra.job_id,j.job_number,ra.status,ra.notes,ra.resolution_reason,ra.updated_at
            FROM recovery_asset ra JOIN job_resource_allocation r ON r.id=ra.allocation_id JOIN sts_job j ON j.id=ra.job_id
            JOIN equipment_asset a ON a.id=r.asset_id WHERE a.model_id=:m AND ra.status IN ('DAMAGED','SHORT')
            ORDER BY ra.updated_at DESC""")
            .param("m", modelId.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! to InventoryEventDto(rs.getBytes(2).uuid()!!,
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()) }.list()
            .groupBy({ it.first }, { it.second })
        return jdbc.sql("""SELECT u.id,u.asset_identifier,u.status,u.job_id,j.job_number FROM
            (SELECT a.id,a.asset_identifier,a.status,$currentJob job_id FROM equipment_asset a WHERE a.model_id=:m) u
            LEFT JOIN sts_job j ON j.id=u.job_id ORDER BY u.asset_identifier""")
            .param("m", modelId.bytes()).param("now", access.now()).query { rs, _ ->
                val id = rs.getBytes(1).uuid()!!; val job = rs.getBytes(4).uuid()
                InventoryUnitDto(id, rs.getString(2), rs.getString(3), job != null, job, rs.getString(5), history[id].orEmpty())
            }.list()
    }

    @Transactional(readOnly = true)
    fun assets(q: String? = null, page: Int = 0, size: Int = 25, sort: String? = null): ResourcePage<AssetDto> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        val (p, s) = bounds(page, size)
        val term = "%${q.orEmpty().trim()}%"
        val where = "WHERE a.asset_identifier LIKE :q"
        val total = jdbc.sql("SELECT COUNT(*) FROM equipment_asset a $where").param("q", term)
            .query(Long::class.java).single()
        val order = sortClause(sort, mapOf("assetIdentifier" to "a.asset_identifier", "status" to "a.status", "categoryCode" to "m.category_code"), "a.asset_identifier")
        val rows = jdbc.sql("""SELECT a.id,a.branch_id,a.model_id,m.category_code,a.asset_identifier,
            a.dimensions_text,a.swl_value,a.swl_unit,a.swl_source,a.mbl_value,a.mbl_unit,a.mbl_source,
            a.home_location,a.status,a.version FROM equipment_asset a JOIN equipment_model m ON m.id=a.model_id
            $where ORDER BY $order, a.id LIMIT :limit OFFSET :offset""")
            .param("q", term).param("limit", s).param("offset", p*s)
            .query { rs, _ -> asset(rs) }.list()
        return ResourcePage(rows, p, s, total)
    }

    @Transactional(readOnly = true)
    fun asset(id: UUID): AssetDto {
        val result = jdbc.sql("""SELECT a.id,a.branch_id,a.model_id,m.category_code,a.asset_identifier,
            a.dimensions_text,a.swl_value,a.swl_unit,a.swl_source,a.mbl_value,a.mbl_unit,a.mbl_source,
            a.home_location,a.status,a.version FROM equipment_asset a JOIN equipment_model m ON m.id=a.model_id WHERE a.id=:id""")
            .param("id", id.bytes()).query { rs, _ -> asset(rs) }.optional().orElseThrow { NotFoundException("Equipment asset") }
        access.company(Permissions.MASTER_RESOURCE_READ)
        return result
    }

    @Transactional
    fun saveAsset(id: UUID?, expected: Long?, input: AssetInput): AssetDto {
        access.company(Permissions.MASTER_RESOURCE_WRITE)
        val identifier = text(input.assetIdentifier, "assetIdentifier", 80)
        if (input.status !in setOf("AVAILABLE","MAINTENANCE","DAMAGED","LOST","RETIRED")) invalid("status", "Choose an asset status.")
        val model = models().firstOrNull { it.id == input.modelId && it.active }
            ?: invalid("modelId", "Choose an active model.")
        if (input.branchId != null && !branchExists(input.branchId)) invalid("branchId", "Choose an existing branch.")
        checkQuantity(input.swlValue, input.swlUnit, input.swlSource, "swlValue")
        checkQuantity(input.mblValue, input.mblUnit, input.mblSource, "mblValue")
        val actual = id ?: UuidV7.generate()
        val fields = mapOf("id" to actual.bytes(), "b" to input.branchId?.bytes(),
            "m" to model.id.bytes(), "code" to identifier, "dims" to optional(input.dimensionsText,500),
            "swl" to input.swlValue, "swlu" to input.swlUnit, "swls" to optional(input.swlSource,500),
            "mbl" to input.mblValue, "mblu" to input.mblUnit, "mbls" to optional(input.mblSource,500),
            "home" to optional(input.homeLocation,150), "status" to input.status,
            "t" to access.now(), "u" to access.actor().bytes())
        if (id == null) {
            try { jdbc.sql("""INSERT INTO equipment_asset
                (id,branch_id,model_id,asset_identifier,dimensions_text,swl_value,swl_unit,swl_source,
                 mbl_value,mbl_unit,mbl_source,home_location,status,created_at,created_by,updated_at,updated_by,version)
                VALUES (:id,:b,:m,:code,:dims,:swl,:swlu,:swls,:mbl,:mblu,:mbls,:home,:status,:t,:u,:t,:u,0)""")
                .params(fields).update()
            } catch (_: DuplicateKeyException) { throw ConflictException("DUPLICATE_ASSET_ID", "Asset ID is already in use.") }
        } else {
            val before = asset(id); Versioning.checkCurrent(expected ?: -1, before.version)
            if (before.assetIdentifier != identifier) invalid("assetIdentifier", "Asset identity cannot change.")
            jdbc.sql("""UPDATE equipment_asset SET branch_id=:b,model_id=:m,dimensions_text=:dims,
                swl_value=:swl,swl_unit=:swlu,swl_source=:swls,mbl_value=:mbl,mbl_unit=:mblu,mbl_source=:mbls,
                home_location=:home,status=:status,updated_at=:t,updated_by=:u,version=version+1 WHERE id=:id""")
                .params(fields).update()
        }
        audit.record(AuditEntry(if (id == null) AuditAction.CREATE else AuditAction.UPDATE, "EQUIPMENT_ASSET", actual, input.branchId, mapOf("assetIdentifier" to identifier, "status" to input.status)))
        return asset(actual)
    }

    @Transactional(readOnly = true)
    fun people(): List<PersonnelDto> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        return jdbc.sql("SELECT id,personnel_identifier,full_name,app_user_id,availability_note,active,version FROM personnel ORDER BY full_name")
            .query { rs, _ -> person(rs) }.list()
    }


    @Transactional(readOnly = true)
    fun peoplePage(q: String?, page: Int, size: Int, sort: String?): ResourcePage<PersonnelDto> =
        masterPage("personnel", "personnel_identifier,full_name,app_user_id,availability_note,active,version",
            "full_name", q, page, size, sort, mapOf("fullName" to "full_name", "personnelIdentifier" to "personnel_identifier"), ::person)

    @Transactional
    fun savePerson(id: UUID?, expected: Long?, input: PersonnelInput): PersonnelDto {
        access.company(Permissions.MASTER_RESOURCE_WRITE)
        val identifier = text(input.personnelIdentifier,"personnelIdentifier",80)
        val name = text(input.fullName,"fullName",150)
        val codes = codes("competencies").filter { it.active }.map { it.code }.toSet()
        if (input.competencies.any { it !in codes } || input.competencies.distinct().size != input.competencies.size)
            invalid("competencies", "Choose distinct active competencies.")
        val actual = id ?: UuidV7.generate()
        val fields = mapOf("id" to actual.bytes(), "code" to identifier,
            "name" to name, "user" to input.appUserId?.bytes(), "note" to optional(input.availabilityNote,500),
            "active" to input.active, "t" to access.now(), "u" to access.actor().bytes())
        if (id == null) {
            try { jdbc.sql("""INSERT INTO personnel
                (id,personnel_identifier,full_name,app_user_id,availability_note,active,
                 created_at,created_by,updated_at,updated_by,version)
                VALUES (:id,:code,:name,:user,:note,:active,:t,:u,:t,:u,0)""").params(fields).update()
            } catch (_: DuplicateKeyException) { throw ConflictException("DUPLICATE_CODE", "Personnel ID or app user is already linked.") }
        } else {
            val before = people().firstOrNull { it.id == id } ?: throw NotFoundException("Person")
            Versioning.checkCurrent(expected ?: -1, before.version)
            if (before.personnelIdentifier != identifier) invalid("personnelIdentifier", "Personnel ID cannot change.")
            jdbc.sql("""UPDATE personnel SET full_name=:name,app_user_id=:user,availability_note=:note,active=:active,
                updated_at=:t,updated_by=:u,version=version+1 WHERE id=:id""").params(fields).update()
            jdbc.sql("DELETE FROM personnel_competency WHERE personnel_id=:id").param("id", id.bytes()).update()
        }
        input.competencies.forEach { code -> jdbc.sql("""INSERT INTO personnel_competency
            (personnel_id,competency_code,created_at,created_by) VALUES (:id,:code,:t,:u)""")
            .params(mapOf("id" to actual.bytes(), "code" to code, "t" to access.now(), "u" to access.actor().bytes())).update() }
        audit.record(AuditEntry(if (id == null) AuditAction.CREATE else AuditAction.UPDATE, "PERSONNEL", actual))
        return people().first { it.id == actual }
    }

    @Transactional(readOnly = true)
    fun vendors(): List<VendorDto> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        return jdbc.sql("SELECT id,name,contacts,contract_from,contract_to,active,version FROM resource_vendor ORDER BY name")
            .query { rs, _ -> vendor(rs) }.list()
    }


    @Transactional(readOnly = true)
    fun vendorPage(q: String?, page: Int, size: Int, sort: String?): ResourcePage<VendorDto> =
        masterPage("resource_vendor", "name,contacts,contract_from,contract_to,active,version", "name",
            q, page, size, sort, mapOf("name" to "name", "contractFrom" to "contract_from"), ::vendor)

    @Transactional(readOnly = true)
    fun vendorById(id: UUID): VendorDto {
        val row=jdbc.sql("SELECT id,name,contacts,contract_from,contract_to,active,version FROM resource_vendor WHERE id=:id")
            .param("id",id.bytes()).query { rs,_ -> vendor(rs) }.optional().orElseThrow { NotFoundException("Vendor") }
        access.company(Permissions.MASTER_RESOURCE_READ)
        return row
    }

    @Transactional
    fun saveVendor(id: UUID?, expected: Long?, input: VendorInput): VendorDto {
        access.company(Permissions.MASTER_RESOURCE_WRITE)
        if (input.contractFrom != null && input.contractTo != null && input.contractTo < input.contractFrom)
            invalid("contractTo", "Contract end precedes start.")
        val actual = id ?: UuidV7.generate()
        val fields = mapOf("id" to actual.bytes(), "name" to text(input.name,"name",150),
            "contacts" to optional(input.contacts,1000), "from" to input.contractFrom, "to" to input.contractTo,
            "active" to input.active, "t" to access.now(), "u" to access.actor().bytes())
        if (id == null) jdbc.sql("""INSERT INTO resource_vendor
            (id,name,contacts,contract_from,contract_to,active,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:name,:contacts,:from,:to,:active,:t,:u,:t,:u,0)""").params(fields).update()
        else {
            val before = vendors().firstOrNull { it.id == id } ?: throw NotFoundException("Vendor")
            Versioning.checkCurrent(expected ?: -1, before.version)
            jdbc.sql("""UPDATE resource_vendor SET name=:name,contacts=:contacts,contract_from=:from,contract_to=:to,
                active=:active,updated_at=:t,updated_by=:u,version=version+1 WHERE id=:id""").params(fields).update()
        }
        audit.record(AuditEntry(if (id == null) AuditAction.CREATE else AuditAction.UPDATE, "RESOURCE_VENDOR", actual))
        return vendors().first { it.id == actual }
    }

    @Transactional(readOnly = true)
    fun craft(): List<CraftDto> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        return jdbc.sql("""SELECT id,vendor_id,identifier,name,craft_type,technical_data,
            bollard_pull_value,bollard_pull_unit,bollard_pull_source,home_location,active,version
            FROM resource_craft ORDER BY name""")
            .query { rs, _ -> craft(rs) }.list()
    }


    @Transactional(readOnly = true)
    fun craftPage(q: String?, page: Int, size: Int, sort: String?): ResourcePage<CraftDto> =
        masterPage("resource_craft", "vendor_id,identifier,name,craft_type,technical_data,bollard_pull_value,bollard_pull_unit,bollard_pull_source,home_location,active,version",
            "name", q, page, size, sort, mapOf("name" to "name", "identifier" to "identifier", "craftType" to "craft_type"), ::craft)

    private fun <T> masterPage(table: String, fields: String, searchColumn: String,
        q: String?, page: Int, size: Int, sort: String?, allowed: Map<String, String>, mapper: (ResultSet) -> T): ResourcePage<T> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        val (p, s) = bounds(page, size)
        val term = "%${q.orEmpty().trim().take(100)}%"
        val where = "WHERE $searchColumn LIKE :q"
        val total = jdbc.sql("SELECT COUNT(*) FROM $table $where").param("q", term)
            .query(Long::class.java).single()
        val order = sortClause(sort, allowed, searchColumn)
        val rows = jdbc.sql("SELECT id,$fields FROM $table $where ORDER BY $order,id LIMIT :limit OFFSET :offset")
            .param("q", term).param("limit", s).param("offset", p*s)
            .query { rs, _ -> mapper(rs) }.list()
        return ResourcePage(rows, p, s, total)
    }

    private fun sortClause(sort: String?, allowed: Map<String, String>, default: String): String {
        if (sort.isNullOrBlank()) return "$default ASC"
        val parts = sort.split(',')
        val column = allowed[parts.first()] ?: invalid("sort", "Unsupported sort field.")
        val direction = parts.getOrNull(1)?.lowercase() ?: "asc"
        if (parts.size > 2 || direction !in setOf("asc", "desc")) invalid("sort", "Unsupported sort direction.")
        return "$column ${direction.uppercase()}"
    }

    @Transactional
    fun saveCraft(id: UUID?, expected: Long?, input: CraftInput): CraftDto {
        access.company(Permissions.MASTER_RESOURCE_WRITE)
        if (input.craftType !in setOf("TUG","LAUNCH","WORKBOAT")) invalid("craftType", "Choose a craft type.")
        if (vendors().none { it.id == input.vendorId && it.active }) invalid("vendorId", "Choose an active vendor.")
        checkQuantity(input.bollardPullValue,input.bollardPullUnit,input.bollardPullSource,"bollardPullValue")
        val actual = id ?: UuidV7.generate()
        val fields = mapOf("id" to actual.bytes(), "v" to input.vendorId.bytes(),
            "code" to text(input.identifier,"identifier",80), "name" to text(input.name,"name",150),
            "type" to input.craftType, "data" to optional(input.technicalData,1000),
            "pull" to input.bollardPullValue, "unit" to input.bollardPullUnit,
            "source" to optional(input.bollardPullSource,500), "home" to optional(input.homeLocation,150),
            "active" to input.active, "t" to access.now(), "u" to access.actor().bytes())
        if (id == null) {
            try { jdbc.sql("""INSERT INTO resource_craft
                (id,vendor_id,identifier,name,craft_type,technical_data,bollard_pull_value,
                 bollard_pull_unit,bollard_pull_source,home_location,active,created_at,created_by,updated_at,updated_by,version)
                VALUES (:id,:v,:code,:name,:type,:data,:pull,:unit,:source,:home,:active,:t,:u,:t,:u,0)""").params(fields).update()
            } catch (_: DuplicateKeyException) { throw ConflictException("DUPLICATE_CODE", "Craft identifier is already in use.") }
        } else {
            val before = craft().firstOrNull { it.id == id } ?: throw NotFoundException("Craft")
            Versioning.checkCurrent(expected ?: -1, before.version)
            if (before.identifier != input.identifier) invalid("identifier", "Craft identifier cannot change.")
            jdbc.sql("""UPDATE resource_craft SET vendor_id=:v,name=:name,craft_type=:type,technical_data=:data,
                bollard_pull_value=:pull,bollard_pull_unit=:unit,bollard_pull_source=:source,home_location=:home,
                active=:active,updated_at=:t,updated_by=:u,version=version+1 WHERE id=:id""").params(fields).update()
        }
        audit.record(AuditEntry(if (id == null) AuditAction.CREATE else AuditAction.UPDATE, "RESOURCE_CRAFT", actual))
        return craft().first { it.id == actual }
    }

    @Transactional(readOnly = true)
    fun policy(): ResourcePolicyDto {
        access.company(Permissions.MASTER_RESOURCE_READ)
        return policyForAllocation()
    }

    internal fun policyForAllocation(): ResourcePolicyDto {
        return jdbc.sql("SELECT mode,warning_days,requirements_configured,version FROM resource_certificate_policy WHERE id=1")
            .query { rs, _ -> ResourcePolicyDto(rs.getString(1),rs.getInt(2).takeUnless { rs.wasNull() },
                rs.getBoolean(3),rs.getLong(4)) }.optional().orElse(ResourcePolicyDto("BLOCK",null,false,0))
    }

    @Transactional
    fun savePolicy(expected: Long, input: ResourcePolicyInput): ResourcePolicyDto {
        access.company(Permissions.MASTER_RESOURCE_WRITE)
        val before = policy(); Versioning.checkCurrent(expected,before.version)
        if (input.mode !in setOf("BLOCK","WARN") || input.warningDays != null && input.warningDays !in 0..3650)
            invalid("mode", "Choose BLOCK or WARN and a valid warning horizon.")
        jdbc.sql("""INSERT INTO resource_certificate_policy
            (id,mode,warning_days,requirements_configured,updated_at,updated_by,version)
            VALUES (1,:m,:d,:configured,:t,:u,1) ON DUPLICATE KEY UPDATE mode=:m,warning_days=:d,
            requirements_configured=:configured,updated_at=:t,updated_by=:u,version=version+1""")
            .params(mapOf("m" to input.mode,"d" to input.warningDays,
                "configured" to input.requirementsConfigured,"t" to access.now(),"u" to access.actor().bytes())).update()
        audit.record(AuditEntry(AuditAction.UPDATE,"RESOURCE_CERTIFICATE_POLICY",null,
            details=mapOf("mode" to input.mode,"requirementsConfigured" to input.requirementsConfigured)))
        return policy()
    }

    @Transactional(readOnly = true)
    fun requirements(): List<ResourceRequirementDto> {
        access.company(Permissions.MASTER_RESOURCE_READ)
        return requirementsForAllocation()
    }

    internal fun requirementsForAllocation(): List<ResourceRequirementDto> {
        return jdbc.sql("""SELECT id,resource_kind,scope_code,certificate_type_code,active,version
            FROM resource_certificate_requirement ORDER BY resource_kind,scope_code,certificate_type_code""")
            .query { rs,_ -> ResourceRequirementDto(rs.getBytes(1).uuid()!!,
                rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5),rs.getLong(6)) }.list()
    }

    @Transactional
    fun addRequirement(input: ResourceRequirementInput): ResourceRequirementDto {
        access.company(Permissions.MASTER_RESOURCE_WRITE)
        if (input.resourceKind !in setOf("ASSET","PERSON","CRAFT")) invalid("resourceKind","Choose a resource kind.")
        val scopeCode = text(input.scopeCode,"scopeCode",40)
        val scopeValid=when(input.resourceKind) {
            "ASSET" -> codes("categories").any { it.code==scopeCode && it.active }
            "PERSON" -> codes("competencies").any { it.code==scopeCode && it.active }
            else -> scopeCode in setOf("TUG","LAUNCH","WORKBOAT")
        }
        if(!scopeValid) invalid("scopeCode","Choose an active category, competency or craft type.")
        if (codes("certificate-types").none { it.code==input.certificateTypeCode && it.active }) invalid("certificateTypeCode","Choose an active type.")
        val id=UuidV7.generate()
        try { jdbc.sql("""INSERT INTO resource_certificate_requirement
            (id,resource_kind,scope_code,certificate_type_code,active,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:kind,:scope,:type,:active,:t,:u,:t,:u,0)""")
            .params(mapOf("id" to id.bytes(),"kind" to input.resourceKind,
                "scope" to scopeCode,"type" to input.certificateTypeCode,"active" to input.active,
                "t" to access.now(),"u" to access.actor().bytes())).update()
        } catch (_: DuplicateKeyException) { throw ConflictException("DUPLICATE_CODE","Certificate requirement already exists.") }
        audit.record(AuditEntry(AuditAction.CREATE,"RESOURCE_CERTIFICATE_REQUIREMENT",id))
        return requirements().first { it.id==id }
    }

    @Transactional(readOnly = true)
    fun certificates(kind: String, ownerId: UUID): List<CertificateDto> {
        ownerExists(kind,ownerId)
        access.company(Permissions.MASTER_RESOURCE_READ)
        return certificatesForAllocation(kind,ownerId)
    }

    internal fun certificatesForAllocation(kind: String, ownerId: UUID): List<CertificateDto> {
        val col=ownerColumn(kind)
        return jdbc.sql("""SELECT c.id,c.type_code,c.certificate_number,c.issuer,c.issue_date,c.expiry_date,
            c.file_id,f.original_name,f.scan_state,c.version FROM resource_certificate c
            LEFT JOIN stored_file f ON f.id=c.file_id WHERE c.$col=:id ORDER BY c.expiry_date,c.id""")
            .param("id",ownerId.bytes()).query { rs,_ -> CertificateDto(rs.getBytes(1).uuid()!!,kind,ownerId,
                rs.getString(2),rs.getString(3),rs.getString(4),rs.getDate(5).toLocalDate(),
                rs.getDate(6)?.toLocalDate(),rs.getBytes(7).uuid(),rs.getString(8),rs.getString(9),rs.getLong(10)) }.list()
    }

    @Transactional
    fun addCertificate(kind: String, ownerId: UUID, input: CertificateInput, file: MultipartFile?): CertificateDto {
        ownerExists(kind,ownerId); access.company(Permissions.MASTER_RESOURCE_WRITE)
        if (input.expiryDate!=null && input.expiryDate<input.issueDate) throw BadRequestException("INVALID_CERTIFICATE_DATES",
            "Expiry cannot precede issue.",listOf(FieldError("expiryDate","range",null)))
        if (codes("certificate-types").none { it.code==input.typeCode && it.active }) invalid("typeCode","Choose an active certificate type.")
        val stored=file?.let { files.store(it,FileAccess(Permissions.MASTER_RESOURCE_READ)) }
        val id=UuidV7.generate(); val col=ownerColumn(kind)
        jdbc.sql("""INSERT INTO resource_certificate
            (id,$col,type_code,certificate_number,issuer,issue_date,expiry_date,file_id,
             created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:owner,:type,:number,:issuer,:issue,:expiry,:file,:t,:u,:t,:u,0)""")
            .params(mapOf("id" to id.bytes(),"owner" to ownerId.bytes(),"type" to input.typeCode,
                "number" to text(input.certificateNumber,"certificateNumber",100),"issuer" to text(input.issuer,"issuer",150),
                "issue" to input.issueDate,"expiry" to input.expiryDate,"file" to stored?.id?.bytes(),
                "t" to access.now(),"u" to access.actor().bytes())).update()
        bumpOwner(kind,ownerId)
        val created=certificates(kind,ownerId).first { it.id==id }
        audit.record(AuditEntry(AuditAction.CREATE,"RESOURCE_CERTIFICATE",id,
            details=mapOf("ownerKind" to kind,"ownerId" to ownerId,"after" to created)))
        return created
    }

    @Transactional
    fun editCertificate(kind: String, ownerId: UUID, id: UUID, expected: Long, input: CertificateEditInput,
        file: MultipartFile? = null): CertificateDto {
        ownerExists(kind,ownerId); access.company(Permissions.MASTER_RESOURCE_WRITE)
        val before=certificates(kind,ownerId).firstOrNull { it.id==id } ?: throw NotFoundException("Certificate")
        Versioning.checkCurrent(expected,before.version)
        if(input.expiryDate!=null && input.expiryDate<input.issueDate) invalid("expiryDate","Expiry cannot precede issue.")
        if(codes("certificate-types").none { it.code==input.typeCode && it.active }) invalid("typeCode","Choose an active certificate type.")
        val reason=correctionReason(kind,ownerId,input.reason)
        val stored=file?.let { files.store(it,FileAccess(Permissions.MASTER_RESOURCE_READ)) }
        jdbc.sql("""UPDATE resource_certificate SET type_code=:type,certificate_number=:number,issuer=:issuer,
            issue_date=:issue,expiry_date=:expiry,file_id=COALESCE(:file,file_id),
            updated_at=:t,updated_by=:u,version=version+1 WHERE id=:id""")
            .params(mapOf("type" to input.typeCode,"number" to text(input.certificateNumber,"certificateNumber",100),
                "issuer" to text(input.issuer,"issuer",150),"issue" to input.issueDate,"expiry" to input.expiryDate,
                "file" to stored?.id?.bytes(),"t" to access.now(),"u" to access.actor().bytes(),"id" to id.bytes())).update()
        bumpOwner(kind,ownerId)
        val after=certificates(kind,ownerId).first { it.id==id }
        audit.record(AuditEntry(AuditAction.UPDATE,"RESOURCE_CERTIFICATE",id,
            details=mapOf("ownerKind" to kind,"ownerId" to ownerId,"reason" to reason,"before" to before,"after" to after)))
        return after
    }

    @Transactional(readOnly = true)
    fun certificateHistory(kind: String, ownerId: UUID, id: UUID): List<ResourceChangeDto> {
        ownerExists(kind,ownerId); access.company(Permissions.MASTER_RESOURCE_READ)
        if(certificates(kind,ownerId).none { it.id==id }) throw NotFoundException("Certificate")
        return changeHistory("RESOURCE_CERTIFICATE",id)
    }

    @Transactional(readOnly = true)
    fun inspections(assetId: UUID): List<InspectionDto> {
        val asset=asset(assetId)
        return jdbc.sql("SELECT id,inspected_at,result,source,next_due_at,version FROM equipment_inspection WHERE asset_id=:id ORDER BY inspected_at DESC")
            .param("id",asset.id.bytes()).query { rs,_ -> InspectionDto(rs.getBytes(1).uuid()!!,assetId,
                rs.getDate(2).toLocalDate(),rs.getString(3),rs.getString(4),rs.getDate(5)?.toLocalDate(),rs.getLong(6)) }.list()
    }

    @Transactional
    fun addInspection(assetId: UUID, input: InspectionInput): InspectionDto {
        val asset=asset(assetId); access.company(Permissions.MASTER_RESOURCE_WRITE)
        if (input.nextDueAt!=null && input.nextDueAt<input.inspectedAt) invalid("nextDueAt","Due date precedes inspection.")
        val id=UuidV7.generate()
        jdbc.sql("""INSERT INTO equipment_inspection
            (id,asset_id,inspected_at,result,source,next_due_at,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:asset,:at,:result,:source,:due,:t,:u,:t,:u,0)""")
            .params(mapOf("id" to id.bytes(),"asset" to assetId.bytes(),"at" to input.inspectedAt,
                "result" to text(input.result,"result",150),"source" to text(input.source,"source",500),
                "due" to input.nextDueAt,"t" to access.now(),"u" to access.actor().bytes())).update()
        bumpOwner("ASSET",assetId)
        val created=inspections(assetId).first { it.id==id }
        audit.record(AuditEntry(AuditAction.CREATE,"EQUIPMENT_INSPECTION",id,
            details=mapOf("assetId" to assetId,"after" to created)))
        return created
    }

    @Transactional
    fun editInspection(assetId: UUID, id: UUID, expected: Long, input: InspectionEditInput): InspectionDto {
        val asset=asset(assetId); access.company(Permissions.MASTER_RESOURCE_WRITE)
        val before=inspections(assetId).firstOrNull { it.id==id } ?: throw NotFoundException("Inspection")
        Versioning.checkCurrent(expected,before.version)
        if(input.nextDueAt!=null && input.nextDueAt<input.inspectedAt) invalid("nextDueAt","Due date precedes inspection.")
        val reason=correctionReason("ASSET",assetId,input.reason)
        jdbc.sql("""UPDATE equipment_inspection SET inspected_at=:at,result=:result,source=:source,next_due_at=:due,
            updated_at=:t,updated_by=:u,version=version+1 WHERE id=:id""")
            .params(mapOf("at" to input.inspectedAt,"result" to text(input.result,"result",150),
                "source" to text(input.source,"source",500),"due" to input.nextDueAt,
                "t" to access.now(),"u" to access.actor().bytes(),"id" to id.bytes())).update()
        bumpOwner("ASSET",assetId)
        val after=inspections(assetId).first { it.id==id }
        audit.record(AuditEntry(AuditAction.UPDATE,"EQUIPMENT_INSPECTION",id,
            details=mapOf("assetId" to assetId,"reason" to reason,"before" to before,"after" to after)))
        return after
    }

    @Transactional(readOnly = true)
    fun inspectionHistory(assetId: UUID, id: UUID): List<ResourceChangeDto> {
        val asset=asset(assetId); access.company(Permissions.MASTER_RESOURCE_READ)
        if(inspections(assetId).none { it.id==id }) throw NotFoundException("Inspection")
        return changeHistory("EQUIPMENT_INSPECTION",id)
    }

    private fun correctionReason(kind: String, ownerId: UUID, reason: String?): String? {
        val used=jdbc.sql("SELECT COUNT(*) FROM job_resource_allocation WHERE ${ownerColumn(kind)}=:id")
            .param("id",ownerId.bytes()).query(Long::class.java).single()>0
        if(used && reason.isNullOrBlank()) invalid("reason","A reason is required for an allocated resource.")
        return reason?.let { text(it,"reason",500) }
    }

    private fun changeHistory(type: String, id: UUID): List<ResourceChangeDto> =
        jdbc.sql("""SELECT occurred_at,actor_name,details FROM audit_event
            WHERE entity_type=:type AND entity_id=:id ORDER BY occurred_at,id""")
            .param("type",type).param("id",id.bytes()).query { rs,_ ->
                ResourceChangeDto(rs.getTimestamp(1).toInstant(),rs.getString(2),rs.getString(3)) }.list()

    private fun checkQuantity(value: BigDecimal?, unit: String?, source: String?, field: String) {
        if (value==null && (unit!=null || source!=null)) invalid(field,"Enter the value with its unit and source.")
        if (value!=null && (value.signum()<0 || value.scale()>3 || unit.isNullOrBlank() || source.isNullOrBlank()))
            invalid(field,"Enter a non-negative value, controlled unit and source.")
        if (unit!=null && jdbc.sql("SELECT COUNT(*) FROM resource_unit WHERE code=:c AND active=TRUE")
                .param("c",unit).query(Long::class.java).single()==0L) invalid(field,"Choose a controlled unit.")
    }

    private fun branchExists(branchId: UUID): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM branch WHERE id=:b").param("b",branchId.bytes()).query(Long::class.java).single()>0

    private fun ownerColumn(kind: String)=when(kind.uppercase()) {
        "ASSET"->"asset_id"; "PERSON"->"personnel_id"; "CRAFT"->"craft_id"; else->throw NotFoundException("Resource owner")
    }
    private fun ownerExists(kind: String,id: UUID) {
        val table=when(kind.uppercase()) {"ASSET"->"equipment_asset";"PERSON"->"personnel";"CRAFT"->"resource_craft";
            else->throw NotFoundException("Resource owner")}
        if (jdbc.sql("SELECT COUNT(*) FROM $table WHERE id=:id").param("id",id.bytes()).query(Long::class.java).single()==0L)
            throw NotFoundException("Resource owner")
    }
    private fun bumpOwner(kind: String,id: UUID) {
        val table=when(kind.uppercase()) {"ASSET"->"equipment_asset";"PERSON"->"personnel";"CRAFT"->"resource_craft";
            else->throw NotFoundException("Resource owner")}
        jdbc.sql("UPDATE $table SET version=version+1,updated_at=:t,updated_by=:actor WHERE id=:id")
            .param("t",access.now()).param("actor",access.actor().bytes()).param("id",id.bytes()).update()
    }
    private fun bounds(page:Int,size:Int): Pair<Int,Int> {
        if(page<0) invalid("page","Page must be non-negative.")
        return page to size.coerceIn(1,100)
    }
    private fun model(rs:ResultSet)=EquipmentModelDto(rs.getBytes(1).uuid()!!,
        rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5),rs.getLong(6),rs.getBigDecimal(7),rs.getString(8))
    private fun asset(rs:ResultSet)=AssetDto(rs.getBytes(1).uuid()!!,rs.getBytes(2).uuid(),
        rs.getBytes(3).uuid()!!,rs.getString(4),rs.getString(5),rs.getString(6),rs.getBigDecimal(7),
        rs.getString(8),rs.getString(9),rs.getBigDecimal(10),rs.getString(11),rs.getString(12),
        rs.getString(13),rs.getString(14),rs.getLong(15))
    private fun person(rs:ResultSet):PersonnelDto {
        val id=rs.getBytes(1).uuid()!!
        val competencies=jdbc.sql("SELECT competency_code FROM personnel_competency WHERE personnel_id=:id ORDER BY competency_code")
            .param("id",id.bytes()).query(String::class.java).list().filterNotNull()
        return PersonnelDto(id,rs.getString(2),rs.getString(3),rs.getBytes(4).uuid(),
            rs.getString(5),rs.getBoolean(6),competencies,rs.getLong(7))
    }
    private fun vendor(rs:ResultSet)=VendorDto(rs.getBytes(1).uuid()!!,rs.getString(2),
        rs.getString(3),rs.getDate(4)?.toLocalDate(),rs.getDate(5)?.toLocalDate(),rs.getBoolean(6),rs.getLong(7))
    private fun craft(rs:ResultSet)=CraftDto(rs.getBytes(1).uuid()!!,rs.getBytes(2).uuid()!!,
        rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getBigDecimal(7),rs.getString(8),
        rs.getString(9),rs.getString(10),rs.getBoolean(11),rs.getLong(12))
}
