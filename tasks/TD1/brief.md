# TD1 brief

Owner's request: "Начни TD1".

Q: Task type? A: Technical (Recommended).
Q: How to remove the class-wide COMMAND_INJECTION exclusion? A: A precise `@SuppressFBWarnings` (Recommended): remove the XML filter and its pom wiring, add one annotation with a justification on the one method that starts the process (new dependency `spotbugs-annotations`, scope provided). Newer find-sec-bugs/SpotBugs releases do not exist (1.14.0 / 4.10.5 are the latest).

Owner approval of the suppression: this one `@SuppressFBWarnings(COMMAND_INJECTION)` on `DefaultProcessRunner.Child.start` is approved in advance by the owner's answer above. Any other suppression still needs a new approval.

Agreed task: see TD1 in `tasks/todo.md`.
