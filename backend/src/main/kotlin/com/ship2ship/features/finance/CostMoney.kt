package com.ship2ship.features.finance

import java.math.BigDecimal
import java.math.RoundingMode

/** Allocate a frozen document conversion without losing a rounding cent between cost heads. */
internal fun convertedCostLines(lines:List<CostLine>,rate:BigDecimal,gross:BigDecimal,minor:Int):List<Pair<BigDecimal,BigDecimal>> {
    val net=lines.fold(BigDecimal.ZERO) { t,l->t+l.netAmount }
    val tax=lines.fold(BigDecimal.ZERO) { t,l->t+l.taxAmount }
    val convertedNet=net.multiply(rate).setScale(minor,RoundingMode.HALF_UP)
    val convertedTax=gross-convertedNet
    var cumulativeNet=BigDecimal.ZERO; var cumulativeTax=BigDecimal.ZERO
    var allocatedNet=BigDecimal.ZERO; var allocatedTax=BigDecimal.ZERO
    return lines.map { line->
        cumulativeNet+=line.netAmount; cumulativeTax+=line.taxAmount
        val nextNet=if(net.signum()==0) BigDecimal.ZERO else convertedNet.multiply(cumulativeNet).divide(net,minor,RoundingMode.HALF_UP)
        val nextTax=if(tax.signum()==0) BigDecimal.ZERO else convertedTax.multiply(cumulativeTax).divide(tax,minor,RoundingMode.HALF_UP)
        val result=(nextNet-allocatedNet) to (nextTax-allocatedTax)
        allocatedNet=nextNet; allocatedTax=nextTax
        result
    }
}
