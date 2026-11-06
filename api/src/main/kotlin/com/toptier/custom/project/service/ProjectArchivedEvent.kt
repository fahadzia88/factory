package com.toptier.custom.project.service

/**
 * Published inside [ProjectService.archive]'s transaction on every archive, including a repeat one,
 * so features that keep data per project can clean it up. Listeners run in that transaction.
 */
data class ProjectArchivedEvent(val projectId: Long)
