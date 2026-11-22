# TD8 brief

Found at Checkpoint 4 (2026-11-22). Owner: "Повторное нажатие выдает сообщение про expired link хотя еще не 14-27", then, after the diagnosis (the sweeper deletes the directory once both files are delivered, per SPEC): "Поведение нормальное, но нужно изменить текст нотификации, если файл уже был скачен. Не писать expired а писать чтобы выгружен".

Question: the bot cannot tell "already downloaded" from "expired" once `job.json` is deleted. Options: one common text, or keep a marker. Owner chose: one common text (no new stored data). Task type: technical (owner's choice).

Text: `Files already downloaded or expired, please resend the link.`

Agreed task: see TD8 in `tasks/todo.md`.
