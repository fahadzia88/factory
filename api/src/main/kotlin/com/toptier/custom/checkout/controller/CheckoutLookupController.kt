package com.toptier.custom.checkout.controller

import com.toptier.custom.checkout.dto.CheckoutResponse
import com.toptier.custom.checkout.service.CheckoutService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Finds a checkout by its project's repository URL; used by the agent's workflow tools. */
@RestController
@RequestMapping("/api/checkouts")
class CheckoutLookupController(private val checkoutService: CheckoutService) {

	/** The checkout of the project whose `repositoryUrl` is exactly [repositoryUrl]. */
	@GetMapping
	fun find(@RequestParam repositoryUrl: String): CheckoutResponse =
		CheckoutResponse.from(checkoutService.findByRepositoryUrl(repositoryUrl))
}
