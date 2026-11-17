# TD3 brief

## Owner's original words
"добавь мутационные тесты для main кода через pitest. Установи минимальное покрытие в 85%"

## Questions and answers
- Q: Where should PIT run with the 85% mutation score threshold?
  A: In a separate Maven profile (`mutation`), not in every `./mvnw verify`.
- Q: If the current mutation score is below 85%, what to do?
  A: Strengthen the unit tests in this same task.
- Q: PIT is computed from unit tests in `src/test` only (`src/it` is owned by the Tester and runs through Failsafe/Cucumber)?
  A: Yes, confirmed ("да, всё верно").

## Agreed task
See TD3 in `tasks/todo.md`.

## Owner decision during implementation (TD3 is a technical task)
- The owner asked why the Tester writes tests for mutation testing; PIT is a build tool, so it needs no acceptance tests in `src/it`.
- TD3 goes without the Tester and without Gherkin scenarios: `src/features/TD3-mutation.feature` is removed, and nothing is added to `src/it`.
- Evidence is kept by the Developer in `tasks/TD3/` (baseline, final, red run with a raised threshold). Both reviewers still review the change.
- Rule from the owner: ask the owner for the task type at intake. A technical task does not need every role.
