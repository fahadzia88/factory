package com.toptier.custom.checkout.domain

/** Where a project's clone is in its life; [json] is the value the API shows. */
enum class CheckoutState(val json: String) {
	CLONING("cloning"),
	READY("ready"),
	FAILED("failed"),
}
