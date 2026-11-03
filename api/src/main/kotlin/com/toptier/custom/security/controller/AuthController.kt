package com.toptier.custom.security.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController {

	/** Lets clients check a token: the security chain answers 401 before this runs if it is invalid. */
	@GetMapping("/verify")
	fun verify(): ResponseEntity<Unit> = ResponseEntity.noContent().build()
}
