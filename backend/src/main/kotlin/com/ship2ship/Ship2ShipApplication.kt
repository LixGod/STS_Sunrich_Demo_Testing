package com.ship2ship

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class Ship2ShipApplication

fun main(args: Array<String>) {
	runApplication<Ship2ShipApplication>(*args)
}
