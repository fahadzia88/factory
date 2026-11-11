---
feature: 006-feature-implementation
task: 08-api-question-store
layer: api
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/08-api-question-store
base: feat/006-feature-implementation/07-api-feature-complete
covers: [AC-18, AC-19]
---

# 08 — Question store

## Goal

The `feature_question` table, the validation of a question set, and the route the agent posts questions to.
Relaying them to the app and answering them are task 09.

## Contract

From task-breakdown.md, "Questions":

```json
{ "questions": [
  { "header": "Token", "question": "Which token does the API use for pull requests?", "multiSelect": false,
    "options": [ { "label": "Keep GIT_TOKEN", "description": "Add Pull requests read and write to it." },
                 { "label": "New token", "description": "A separate token only for pull requests." } ] } ] }
```

- Rules (`400 invalid_questions`, `detail` names the problem): 1–4 questions; `header` 1–12 characters; `question` 1–300;
  2–4 options, the first is the recommended one; `label` 1–60, unique within a question, never "Other" (any case);
  `description` 1–200. "Other" is not sent: the app adds it.
- `POST /api/projects/{id}/features/{featureId}/questions` → `201` `{ "id": 7 }`. Earlier pending sets of the feature
  become `superseded`.
- Table `feature_question`: `id`, `feature_id`, `payload` (JSON text), `status` (`pending`, `answered`, `superseded`),
  `answers` (JSON text, null), `after_message_id` (null), `answer_message_id` (null), `created_at`, `answered_at` (null).

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `domain/FeatureQuestion.kt`, `domain/FeatureQuestionRepository.kt` — new: entity; `findByFeatureIdAndStatus`,
  `findFirstByFeatureIdAndStatusOrderByIdDesc`, `findByFeatureIdOrderByIdAsc`, `findByIdAndFeatureId`.
- `domain/QuestionStatus.kt` — new enum `PENDING`, `ANSWERED`, `SUPERSEDED` (stored as string).
- `dto/QuestionDtos.kt` — new: `QuestionsRequest(questions: List<QuestionInput>?)`, `QuestionInput(header, question, multiSelect,
  options)`, `OptionInput(label, description)`, `QuestionsCreated(id)`.
- `service/FeatureQuestions.kt` — new `@Service`: `create(projectId, featureId, request): FeatureQuestion` (validates, supersedes
  pending sets, stores `payload` with `JsonMapper`); `QuestionValidator` as a private/internal object in the same file.
- `controller/FeatureQuestionController.kt` — new: `@PostMapping` on `/api/projects/{id}/features/{featureId}/questions`.
- `exception/FeatureExceptions.kt`, `controller/FeatureExceptionHandler.kt` — change: `InvalidQuestionsException(message)` →
  `400 invalid_questions`.

## Steps

1. Entity (tabs, KDoc), enum, repository.
2. Validator: null/empty body or fields → `InvalidQuestionsException` with a message that names the question number
   ("Question 2: give 2 to 4 options."). Trim strings before measuring. Labels compared case-insensitively for the
   duplicate and "Other" rules.
3. `create`: `@Transactional`; load the feature; validate; `UPDATE … SET status = SUPERSEDED WHERE feature_id = ? AND status = PENDING`
   (repository method); save the new set `PENDING` with `createdAt = clock.instant()`.
4. Controller returns `201 { "id": … }`. No archived-project check (as the other feature writes).

## Tests

- `QuestionValidatorTests` — valid minimal set; 0 and 5 questions; header of 13 characters; question of 301; 1 and 5 options;
  duplicate labels ("A", "a"); label "Other" and "other"; blank description; null body (AC-19).
- `FeatureQuestionsTests` (`@DataJpaTest` + service) — create stores payload; a second create supersedes the first;
  an answered set is not touched.
- `FeatureQuestionControllerTests` — `401` without a token (AC-18); `201` with `id`; `400 invalid_questions` with the detail;
  `404 feature_not_found`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.

## Out of scope

- Relaying to the app, answers, history (09). The agent tool (12).
