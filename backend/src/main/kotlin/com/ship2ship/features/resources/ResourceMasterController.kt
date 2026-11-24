package com.ship2ship.features.resources

import com.ship2ship.core.web.Versioning
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/resources")
class ResourceMasterController(private val service: ResourceMasterService) {
    @GetMapping("/{kind:categories|competencies|certificate-types}")
    fun codes(@PathVariable kind:String)=service.codes(kind)

    @PostMapping("/{kind:categories|competencies|certificate-types}")
    fun addCode(@PathVariable kind:String,@RequestBody input:ResourceCodeInput):ResponseEntity<ResourceCodeDto> =
        ResponseEntity.status(201).body(service.saveCode(kind,null,null,input))

    @PutMapping("/{kind:categories|competencies|certificate-types}/{code}")
    fun updateCode(@PathVariable kind:String,@PathVariable code:String,
                   @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:ResourceCodeInput)=
        service.saveCode(kind,code,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @GetMapping("/units") fun units()=service.units()
    @PostMapping("/units") fun addUnit(@RequestBody input:ResourceUnitInput):ResponseEntity<ResourceUnitDto> =
        ResponseEntity.status(201).body(service.saveUnit(null,null,input))
    @PutMapping("/units/{code}") fun updateUnit(@PathVariable code:String,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:ResourceUnitInput)=
        service.saveUnit(code,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @GetMapping("/models") fun models(@RequestParam(required=false) q:String?,
        @RequestParam(required=false) page:Int?,@RequestParam(required=false) size:Int?,@RequestParam(required=false) sort:String?)=
        service.modelPage(q,page?:0,size?:25,sort)
    @GetMapping("/models/{id}") fun model(@PathVariable id:UUID)=service.modelById(id).let { Versioning.ok(it,it.version) }
    @PostMapping("/models") fun addModel(@RequestBody input:EquipmentModelInput):ResponseEntity<EquipmentModelDto> =
        ResponseEntity.status(201).body(service.saveModel(null,null,input))
    @PutMapping("/models/{id}") fun updateModel(@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:EquipmentModelInput)=
        service.saveModel(id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @GetMapping("/inventory") fun inventory()=service.inventory()
    @GetMapping("/inventory/{modelId}") fun inventoryUnits(@PathVariable modelId:UUID)=service.inventoryUnits(modelId)
    @GetMapping("/assets") fun assets(@RequestParam(required=false) q:String?,
        @RequestParam(required=false) page:Int?,@RequestParam(required=false) size:Int?,@RequestParam(required=false) sort:String?)=
        service.assets(q,page?:0,size?:25,sort)
    @GetMapping("/assets/{id}") fun asset(@PathVariable id:UUID)=service.asset(id).let { Versioning.ok(it,it.version) }
    @PostMapping("/assets") fun addAsset(@RequestBody input:AssetInput):ResponseEntity<AssetDto> =
        ResponseEntity.status(201).body(service.saveAsset(null,null,input))
    @PutMapping("/assets/{id}") fun updateAsset(@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:AssetInput)=
        service.saveAsset(id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @GetMapping("/personnel") fun people(@RequestParam(required=false) q:String?,
        @RequestParam(required=false) page:Int?,@RequestParam(required=false) size:Int?,@RequestParam(required=false) sort:String?)=
        service.peoplePage(q,page?:0,size?:25,sort)
    @PostMapping("/personnel") fun addPerson(@RequestBody input:PersonnelInput):ResponseEntity<PersonnelDto> =
        ResponseEntity.status(201).body(service.savePerson(null,null,input))
    @PutMapping("/personnel/{id}") fun updatePerson(@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:PersonnelInput)=
        service.savePerson(id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @GetMapping("/vendors") fun vendors(@RequestParam(required=false) q:String?,
        @RequestParam(required=false) page:Int?,@RequestParam(required=false) size:Int?,@RequestParam(required=false) sort:String?)=
        service.vendorPage(q,page?:0,size?:25,sort)
    @GetMapping("/vendors/{id}") fun vendor(@PathVariable id:UUID)=service.vendorById(id).let { Versioning.ok(it,it.version) }
    @PostMapping("/vendors") fun addVendor(@RequestBody input:VendorInput):ResponseEntity<VendorDto> =
        ResponseEntity.status(201).body(service.saveVendor(null,null,input))
    @PutMapping("/vendors/{id}") fun updateVendor(@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:VendorInput)=
        service.saveVendor(id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @GetMapping("/craft") fun craft(@RequestParam(required=false) q:String?,
        @RequestParam(required=false) page:Int?,@RequestParam(required=false) size:Int?,@RequestParam(required=false) sort:String?)=
        service.craftPage(q,page?:0,size?:25,sort)
    @PostMapping("/craft") fun addCraft(@RequestBody input:CraftInput):ResponseEntity<CraftDto> =
        ResponseEntity.status(201).body(service.saveCraft(null,null,input))
    @PutMapping("/craft/{id}") fun updateCraft(@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:CraftInput)=
        service.saveCraft(id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @GetMapping("/policy") fun policy()=service.policy()
    @PutMapping("/policy") fun policyUpdate(
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:ResourcePolicyInput)=
        service.savePolicy(Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }
    @GetMapping("/requirements") fun requirements()=service.requirements()
    @PostMapping("/requirements") fun requirement(
        @RequestBody input:ResourceRequirementInput):ResponseEntity<ResourceRequirementDto> =
        ResponseEntity.status(201).body(service.addRequirement(input))

    @GetMapping("/{kind:assets|personnel|craft}/{ownerId}/certificates")
    fun certificates(@PathVariable kind:String,@PathVariable ownerId:UUID)=service.certificates(ownerKind(kind),ownerId)
    @PostMapping("/{kind:assets|personnel|craft}/{ownerId}/certificates",consumes=[MediaType.MULTIPART_FORM_DATA_VALUE])
    fun certificate(@PathVariable kind:String,@PathVariable ownerId:UUID,@RequestPart("certificate") input:CertificateInput,
        @RequestPart("file",required=false) file:MultipartFile?):ResponseEntity<CertificateDto> =
        ResponseEntity.status(201).body(service.addCertificate(ownerKind(kind),ownerId,input,file))
    @PutMapping("/{kind:assets|personnel|craft}/{ownerId}/certificates/{id}")
    fun editCertificate(@PathVariable kind:String,@PathVariable ownerId:UUID,@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:CertificateEditInput)=
        service.editCertificate(ownerKind(kind),ownerId,id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }
    @PutMapping("/{kind:assets|personnel|craft}/{ownerId}/certificates/{id}",consumes=[MediaType.MULTIPART_FORM_DATA_VALUE])
    fun editCertificateWithFile(@PathVariable kind:String,@PathVariable ownerId:UUID,@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestPart("certificate") input:CertificateEditInput,
        @RequestPart("file",required=false) file:MultipartFile?)=
        service.editCertificate(ownerKind(kind),ownerId,id,Versioning.requireIfMatch(ifMatch),input,file)
            .let { Versioning.ok(it,it.version) }
    @GetMapping("/{kind:assets|personnel|craft}/{ownerId}/certificates/{id}/history")
    fun certificateHistory(@PathVariable kind:String,@PathVariable ownerId:UUID,@PathVariable id:UUID)=
        service.certificateHistory(ownerKind(kind),ownerId,id)

    @GetMapping("/assets/{assetId}/inspections") fun inspections(@PathVariable assetId:UUID)=service.inspections(assetId)
    @PostMapping("/assets/{assetId}/inspections") fun inspection(@PathVariable assetId:UUID,
        @RequestBody input:InspectionInput):ResponseEntity<InspectionDto> =
        ResponseEntity.status(201).body(service.addInspection(assetId,input))
    @PutMapping("/assets/{assetId}/inspections/{id}")
    fun editInspection(@PathVariable assetId:UUID,@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:InspectionEditInput)=
        service.editInspection(assetId,id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }
    @GetMapping("/assets/{assetId}/inspections/{id}/history")
    fun inspectionHistory(@PathVariable assetId:UUID,@PathVariable id:UUID)=service.inspectionHistory(assetId,id)

    private fun ownerKind(path:String)=when(path) {"assets"->"ASSET";"personnel"->"PERSON";else->"CRAFT"}
}
