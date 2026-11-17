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
