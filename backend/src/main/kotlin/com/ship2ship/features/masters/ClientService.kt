package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Client master with roles and contacts. Group-wide reference data: any MASTER.COMMERCIAL grant may maintain it. */
@Service
class ClientService(
    private val clients: ClientRepository,
    private val contacts: ClientContactRepository,
    private val currencies: CurrencyRepository,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun list(q: String?, active: Boolean?, role: ClientRole?, pageable: Pageable): PageResponse<ClientDto> {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_READ)
        val byRole = Specification<ClientEntity> { root, _, cb ->
            if (role == null) cb.conjunction() else cb.isMember(role, root.get<Collection<ClientRole>>("roles"))
        }
        return PageResponse.of(clients.findAll(OrganisationFilters.search<ClientEntity>(q, active, "legalName").and(byRole), pageable)) {
            it.toDto()
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): ClientDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_READ)
        return load(id).toDto()
    }

    @Transactional
    fun create(request: ClientRequest): ClientDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val code = request.code.trim().uppercase()
        validate(request)
        if (clients.existsByCode(code)) {
            throw ConflictException("DUPLICATE_CODE", "This code is already in use.", listOf(FieldError("code", "duplicate", null)))
        }
        val client = ClientEntity(code, request.legalName.trim()).also { apply(it, request) }
        clients.saveAndFlush(client)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, client.id, details = client.toAuditMap()))
        return client.toDto()
    }

    @Transactional
    fun quickCreate(request: QuickClientRequest): QuickClientDto {
        accessPolicy.requireAny(Permissions.ENQUIRY_WRITE)
        val phone = request.phone?.trim()?.ifBlank { null }
        if (phone != null && phone.none(Char::isDigit)) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("phone", "phone", "Enter a valid phone number.")))
        }
        val code = "CL-${UUID.randomUUID().toString().replace("-", "").take(16).uppercase()}"
        val client = ClientEntity(code, request.legalName.trim()).also {
            it.roles.add(ClientRole.OTHER)
            it.countryCode = request.countryCode?.trim()?.uppercase()?.ifBlank { null }
            it.defaultCurrency = "USD"
            it.phone = phone
            it.emails.add(request.email.trim().lowercase())
            phone?.let(it.phones::add)
        }
        clients.saveAndFlush(client)
        val contact = ClientContactEntity(client.id, client.legalName).also {
            it.email = request.email.trim().lowercase()
            it.phone = phone
            it.primary = true
        }
        contacts.saveAndFlush(contact)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, client.id, details = client.toAuditMap()))
        audit.record(AuditEntry(AuditAction.CREATE, CONTACT, contact.id, details = contact.toAuditMap()))
        return QuickClientDto(client.toDto(), contact.toDto())
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: ClientRequest): ClientDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val client = load(id)
        Versioning.checkCurrent(expectedVersion, client.version)
        if (request.code.trim().uppercase() != client.code) {
            throw BadRequestException("CODE_IMMUTABLE", "The code cannot be changed once created.", listOf(FieldError("code", "immutable", null)))
        }
        validate(request)
        val before = client.toAuditMap()
        client.legalName = request.legalName.trim()
        apply(client, request)
        clients.saveAndFlush(client)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = AuditDiff.changes(before, client.toAuditMap())))
        return client.toDto()
    }

    @Transactional
    fun changeStatus(id: UUID, expectedVersion: Long, request: ActiveStatusRequest): ClientDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val client = load(id)
        Versioning.checkCurrent(expectedVersion, client.version)
        OrganisationValidation.check(OrganisationValidation.reasonForDeactivation(request.active, request.reason))
        if (client.active != request.active) {
            client.active = request.active
            clients.saveAndFlush(client)
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id, details = mapOf("active" to request.active, "reason" to request.reason)))
        }
        return client.toDto()
    }

    @Transactional
    fun delete(id: UUID, expectedVersion: Long) {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val client = load(id)
        Versioning.checkCurrent(expectedVersion, client.version)
        val details = client.toAuditMap()
        contacts.deleteAll(contacts.findByClientIdOrderByPrimaryDescFullNameAsc(id))
        contacts.flush()
        clients.delete(client)
        clients.flush()
        audit.record(AuditEntry(AuditAction.DELETE, ENTITY, id, details = details))
    }

    @Transactional(readOnly = true)
    fun contacts(clientId: UUID): List<ClientContactDto> {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_READ)
        load(clientId)
        return contacts.findByClientIdOrderByPrimaryDescFullNameAsc(clientId).map { it.toDto() }
    }

    @Transactional
    fun addContact(clientId: UUID, request: ClientContactRequest): ClientContactDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        load(clientId)
        val contact = ClientContactEntity(clientId, request.fullName.trim()).also { applyContact(it, request) }
        if (contact.primary) clearPrimary(clientId)
        contacts.saveAndFlush(contact)
        audit.record(AuditEntry(AuditAction.CREATE, CONTACT, contact.id, details = contact.toAuditMap()))
        return contact.toDto()
    }

    @Transactional
    fun updateContact(clientId: UUID, contactId: UUID, expectedVersion: Long, request: ClientContactRequest): ClientContactDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val contact = loadContact(clientId, contactId)
        Versioning.checkCurrent(expectedVersion, contact.version)
        val before = contact.toAuditMap()
        contact.fullName = request.fullName.trim()
        applyContact(contact, request)
        if (contact.primary) clearPrimary(clientId, except = contactId)
        contacts.saveAndFlush(contact)
        audit.record(AuditEntry(AuditAction.UPDATE, CONTACT, contactId, details = AuditDiff.changes(before, contact.toAuditMap())))
        return contact.toDto()
    }

    @Transactional
    fun changeContactStatus(clientId: UUID, contactId: UUID, expectedVersion: Long, request: ActiveStatusRequest): ClientContactDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val contact = loadContact(clientId, contactId)
        Versioning.checkCurrent(expectedVersion, contact.version)
        if (contact.active != request.active) {
            contact.active = request.active
            if (!request.active) contact.primary = false
            contacts.saveAndFlush(contact)
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, CONTACT, contactId, details = mapOf("active" to request.active, "reason" to request.reason)))
        }
        return contact.toDto()
    }

    @Transactional
    fun deleteContact(clientId: UUID, contactId: UUID, expectedVersion: Long) {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val contact = loadContact(clientId, contactId)
        Versioning.checkCurrent(expectedVersion, contact.version)
        val details = contact.toAuditMap()
        contacts.delete(contact)
        contacts.flush()
        audit.record(AuditEntry(AuditAction.DELETE, CONTACT, contactId, details = details))
    }

    private fun validate(request: ClientRequest) {
        val currency = request.defaultCurrency?.trim()?.uppercase()
        val errors = mutableListOf<FieldError>()
        currency?.takeIf { currencies.findByCode(it)?.active != true }
            ?.let { errors += FieldError("defaultCurrency", "unknown", "Not an active currency in the currency master.") }
        request.emails?.let { values ->
            if (values.size > 10) errors += FieldError("emails", "size", "Use at most 10 client email addresses.")
            val seen = mutableSetOf<String>()
            values.forEachIndexed { i, raw ->
                val email = raw.trim().lowercase()
                if (email.isNotEmpty() && (email.length > 254 || !email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))))
                    errors += FieldError("emails[$i]", "email", "Enter a valid email address.")
                else if (email.isNotEmpty() && !seen.add(email))
                    errors += FieldError("emails[$i]", "duplicate", "This email is already listed.")
            }
        }
        request.phones?.let { values ->
            if (values.size > 10) errors += FieldError("phones", "size", "Use at most 10 client phone numbers.")
            val seen = mutableSetOf<String>()
            values.forEachIndexed { i, raw ->
                val phone = raw.trim()
                if (phone.isNotEmpty() && (phone.length > 40 || !phone.matches(Regex("^[0-9+()\\-. ]+$")) || phone.none(Char::isDigit)))
                    errors += FieldError("phones[$i]", "phone", "Enter a valid phone number.")
                else if (phone.isNotEmpty() && !seen.add(phone))
                    errors += FieldError("phones[$i]", "duplicate", "This phone is already listed.")
            }
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
    }

    private fun apply(client: ClientEntity, request: ClientRequest) {
        client.roles = request.roles.toMutableSet()
        client.taxRegistrationNo = request.taxRegistrationNo?.trim()?.ifBlank { null }
        client.billingAddress = request.billingAddress?.trim()?.ifBlank { null }
        client.countryCode = request.countryCode?.trim()?.uppercase()?.ifBlank { null }
        request.emails?.let { client.emails = it.map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toMutableList() }
        val phones = request.phones?.map(String::trim)?.filter(String::isNotEmpty)
            ?: request.phone?.trim()?.takeIf(String::isNotEmpty)?.let { listOf(it) + client.phones.drop(1) }
        if (phones != null) client.phones = phones.toMutableList()
        client.phone = client.phones.firstOrNull()
        client.defaultCurrency = request.defaultCurrency?.trim()?.uppercase()?.ifBlank { null }
        client.paymentTerms = request.paymentTerms?.trim()?.ifBlank { null }
    }

    private fun applyContact(contact: ClientContactEntity, request: ClientContactRequest) {
        contact.position = request.position?.trim()?.ifBlank { null }
        contact.email = request.email?.trim()?.lowercase()?.ifBlank { null }
        contact.phone = request.phone?.trim()?.ifBlank { null }
        contact.primary = request.primary
    }

    /** Only one primary contact per client. */
    private fun clearPrimary(clientId: UUID, except: UUID? = null) {
        contacts.findByClientIdOrderByPrimaryDescFullNameAsc(clientId)
            .filter { it.primary && it.id != except }
            .forEach { it.primary = false }
    }

    private fun load(id: UUID) = clients.findByIdOrNull(id) ?: throw NotFoundException("Client")

    private fun loadContact(clientId: UUID, contactId: UUID) =
        contacts.findByIdOrNull(contactId)?.takeIf { it.clientId == clientId } ?: throw NotFoundException("Contact")

    private companion object {
        const val ENTITY = "CLIENT"
        const val CONTACT = "CLIENT_CONTACT"
    }
}

internal fun ClientEntity.toDto() = ClientDto(
    id, code, legalName, roles.sorted(), taxRegistrationNo, billingAddress, countryCode, phone, defaultCurrency, paymentTerms,
    active, updatedAt, version, emails.toList(), phones.toList(),
)

internal fun ClientContactEntity.toDto() = ClientContactDto(id, clientId, fullName, position, email, phone, primary, active, version)
