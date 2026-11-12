package com.toptier.custom.ticket.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/** Stores a [TicketType] as its lowercase JSON value. */
@Converter
class TicketTypeConverter : AttributeConverter<TicketType, String> {

	override fun convertToDatabaseColumn(attribute: TicketType?): String? = attribute?.value

	override fun convertToEntityAttribute(dbData: String?): TicketType? = dbData?.let(TicketType::fromValue)
}

/** Stores a [TicketPriority] as its lowercase JSON value. */
@Converter
class TicketPriorityConverter : AttributeConverter<TicketPriority, String> {

	override fun convertToDatabaseColumn(attribute: TicketPriority?): String? = attribute?.value

	override fun convertToEntityAttribute(dbData: String?): TicketPriority? = dbData?.let(TicketPriority::fromValue)
}

/** Stores a [TicketStatus] as its JSON value, like `in_progress`. */
@Converter
class TicketStatusConverter : AttributeConverter<TicketStatus, String> {

	override fun convertToDatabaseColumn(attribute: TicketStatus?): String? = attribute?.value

	override fun convertToEntityAttribute(dbData: String?): TicketStatus? = dbData?.let(TicketStatus::fromValue)
}
