package com.toptier.custom

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CustomApplication

fun main(args: Array<String>) {
	runApplication<CustomApplication>(*args)
}
