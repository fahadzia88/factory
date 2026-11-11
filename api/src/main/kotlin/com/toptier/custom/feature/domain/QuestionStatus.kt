package com.toptier.custom.feature.domain

/** Where a [FeatureQuestion] set stands: waiting for answers, answered, or replaced by a newer set. */
enum class QuestionStatus { PENDING, ANSWERED, SUPERSEDED }
