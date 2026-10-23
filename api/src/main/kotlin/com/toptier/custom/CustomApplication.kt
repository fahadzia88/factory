package com.toptier.custom

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CustomApplication

fun main(args: Array<String>) {
	runApplication<CustomApplication>(*args)
}
