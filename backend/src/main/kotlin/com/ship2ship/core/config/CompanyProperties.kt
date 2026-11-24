package com.ship2ship.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Ship-2-Ship's own details: the name and address on documents, the base currency for costs and the home time zone. */
@ConfigurationProperties("ship2ship.company")
data class CompanyProperties(
    val name: String = "Ship-2-Ship",
    val billingAddress: String? = null,
    val taxRegistrationNo: String? = null,
    val baseCurrency: String = "USD",
    val timezone: String = "Asia/Kolkata",
)
