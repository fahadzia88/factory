package com.toptier.custom.homelab.service

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** The mapper for controller bodies: tolerant of fields this client doesn't know. */
object HomelabJson {

	val mapper: JsonMapper = JsonMapper.builder()
		.findAndAddModules()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build()
}
