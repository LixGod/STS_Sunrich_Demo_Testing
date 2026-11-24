package com.ship2ship.features.technical

import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.FieldError
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

@Component
class MooringPlanValidator {
    fun parse(tree: JsonNode, mapper: JsonMapper): MooringModel {
        val errors = mutableListOf<FieldError>()
        fun objectFields(node: JsonNode?, path: String, allowed: Set<String>) {
            if (node == null || !node.isObject) { errors += FieldError(path, "object", null); return }
            node.properties().forEach { (name, _) -> if (name !in allowed) errors += FieldError("$path.$name", "unknown", null) }
        }
        fun arrayFields(name: String, allowed: Set<String>, nested: Pair<String, Set<String>>? = null) {
            val array = tree[name] ?: return
            if (!array.isArray) { errors += FieldError(name, "array", null); return }
            for (i in 0 until array.size()) {
                val node = array[i]
                objectFields(node, "$name[$i]", allowed)
                nested?.let { (field, keys) ->
                    val children = node[field] ?: return@let
                    if (!children.isArray) errors += FieldError("$name[$i].$field", "array", null)
                    else for (j in 0 until children.size()) objectFields(children[j], "$name[$i].$field[$j]", keys)
                }
            }
        }
        objectFields(tree, "model", setOf("schemaVersion", "vessels", "layers", "objects", "lines", "particulars", "rulerStepM"))
        if (tree.isObject) {
            arrayFields("vessels", setOf("jobVesselId", "heading", "lateralOffsetM", "orientation"))
            arrayFields("layers", setOf("id", "jobVesselId", "documentVersionId", "xM", "yM", "scale", "visible"))
            arrayFields("objects", setOf("id", "type", "xM", "yM", "jobVesselId", "label"))
            arrayFields("lines", setOf("id", "label", "jobVesselId", "fromPointId", "toPointId", "berthingOrder", "unmooringOrder", "capacities"),
                "capacities" to setOf("columnCode", "value", "unit", "sourceDocumentId", "sourcePage", "sourceText"))
            arrayFields("particulars", setOf("jobVesselId", "code", "value", "unit", "sourceDocumentId", "sourcePage", "sourceText"))
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "Unknown or malformed mooring model fields.", errors)
        return mapper.treeToValue(tree, MooringModel::class.java)
    }

    fun validate(job: JobTechnicalRef, model: MooringModel): MooringModel {
        val errors = mutableListOf<FieldError>()
        fun fail(path: String, code: String) { errors += FieldError(path, code, null) }
        fun bounded(path: String, value: BigDecimal?, positive: Boolean = false) {
            if (value != null && (value.abs() > BigDecimal("10000") || value.scale() > 3 || (positive && value.signum() <= 0))) fail(path, "range")
        }
        fun unique(path: String, values: List<Any>) { if (values.toSet().size != values.size) fail(path, "duplicate") }
        val vesselIds = job.vessels.map { it.id }.toSet()
        if (model.schemaVersion != 1) fail("schemaVersion", "allowed")
        if (model.vessels.map { it.jobVesselId }.toSet() != vesselIds || model.vessels.size != 2) fail("vessels", "jobVessels")
        if (model.layers.size > 20 || model.objects.size > 500 || model.lines.size > 300 || model.particulars.size > 20) fail("model", "size")
        model.vessels.forEachIndexed { i, vessel ->
            if (vessel.heading != null && vessel.heading !in HEADINGS) fail("vessels[$i].heading", "allowed")
            if (vessel.orientation !in ORIENTATIONS) fail("vessels[$i].orientation", "allowed")
            bounded("vessels[$i].lateralOffsetM", vessel.lateralOffsetM)
        }
        unique("layers", model.layers.map { it.id })
        model.layers.forEachIndexed { i, layer ->
            if (layer.jobVesselId !in vesselIds) fail("layers[$i].jobVesselId", "unknown")
            bounded("layers[$i].xM", layer.xM)
            bounded("layers[$i].yM", layer.yM)
            bounded("layers[$i].scale", layer.scale, true)
        }
        unique("objects", model.objects.map { it.id })
        model.objects.forEachIndexed { i, obj ->
            if (obj.type !in OBJECT_TYPES) fail("objects[$i].type", "allowed")
            if (obj.jobVesselId != null && obj.jobVesselId !in vesselIds) fail("objects[$i].jobVesselId", "unknown")
            if ((obj.label?.length ?: 0) > 200) fail("objects[$i].label", "size")
            bounded("objects[$i].xM", obj.xM)
            bounded("objects[$i].yM", obj.yM)
        }
        unique("lines", model.lines.map { it.id })
        unique("lines.label", model.lines.map { it.label.trim().uppercase() })
        val points = model.objects.filter { it.type == "MOORING_POINT" }.map { it.id }.toSet()
        model.lines.forEachIndexed { i, line ->
            if (line.jobVesselId !in vesselIds) fail("lines[$i].jobVesselId", "unknown")
            if (line.label.isBlank() || line.label.length > 30) fail("lines[$i].label", "required")
            if (line.fromPointId != null && line.fromPointId !in points) fail("lines[$i].fromPointId", "unknown")
            if (line.toPointId != null && line.toPointId !in points) fail("lines[$i].toPointId", "unknown")
            if (line.berthingOrder != null && line.berthingOrder <= 0) fail("lines[$i].berthingOrder", "range")
            if (line.unmooringOrder != null && line.unmooringOrder <= 0) fail("lines[$i].unmooringOrder", "range")
            unique("lines[$i].capacities", line.capacities.map { it.columnCode })
            line.capacities.forEachIndexed { j, capacity ->
                if (capacity.value != null && (capacity.value.signum() < 0 || capacity.value.scale() > 3)) fail("lines[$i].capacities[$j].value", "range")
                if (capacity.sourcePage != null && capacity.sourcePage <= 0) fail("lines[$i].capacities[$j].sourcePage", "range")
                if ((capacity.sourceText?.length ?: 0) > 500) fail("lines[$i].capacities[$j].sourceText", "size")
                if ((capacity.unit?.length ?: 0) > 30) fail("lines[$i].capacities[$j].unit", "size")
            }
        }
        unique("lines.berthingOrder", model.lines.mapNotNull { it.berthingOrder })
        unique("lines.unmooringOrder", model.lines.mapNotNull { it.unmooringOrder })
        unique("particulars", model.particulars.map { it.jobVesselId to it.code })
        model.particulars.forEachIndexed { i, value ->
            if (value.jobVesselId !in vesselIds) fail("particulars[$i].jobVesselId", "unknown")
            if (value.code !in PLAN_PARTICULARS) fail("particulars[$i].code", "allowed")
            if (value.unit != "m") fail("particulars[$i].unit", "allowed")
            bounded("particulars[$i].value", value.value, true)
            if (value.sourcePage != null && value.sourcePage <= 0) fail("particulars[$i].sourcePage", "range")
            if ((value.sourceText?.length ?: 0) > 500) fail("particulars[$i].sourceText", "size")
        }
        bounded("rulerStepM", model.rulerStepM, true)
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The mooring model has invalid fields.", errors)
        return model
    }

    companion object {
        val HEADINGS = setOf("CONSTANT", "MANOEUVRING")
        val ORIENTATIONS = setOf("BOW_UP", "BOW_DOWN")
        val OBJECT_TYPES = setOf("PRIMARY_FENDER", "SECONDARY_FENDER", "TUG", "STS_SUPPORT_CRAFT",
            "CONSTANT_HEADING_MOORING", "MANOEUVRING_MOORING", "FENDER_MOORING", "MOORING_POINT", "ANNOTATION")
        val PLAN_PARTICULARS = setOf("PRIMARY_FENDER_SIZE", "OIL_BOOM_LENGTH")
        val DIMENSIONS = listOf("LOA", "BEAM", "BOW_TO_MANIFOLD", "STERN_TO_MANIFOLD", "ACCOMMODATION_TO_MANIFOLD")
        fun valueKey(particular: MooringParticular) = "particular:${particular.jobVesselId}:${particular.code}"
        fun valueKey(lineId: UUID, code: String) = "capacity:$lineId:$code"
    }
}
