@T19
Feature: Keep Russian and English video content in its detected language
  Requirement tags refer to tasks/T19/requirements.md.
  Drive Telegram updates and observe HTTP calls, retained files and deferred-jobs.json.
  Mock language names match real Whisper verbose output; codes are accepted too.
  Owner answer A: Q-analyst-2 confirms detection on the first nonempty chunk.
  Negative inference/queue assertions are checked after the submission has finished.

  Background:
    Given an allowed Telegram user
    And real yt-dlp and ffmpeg are enabled with the local WireMock media proxy
    And WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper base URL points to the Whisper mock
    And the Ollama base URL points to the Ollama mock
    And the configured Ollama model is "qwen3:32b"
    And the configured Ollama context window is 8192 tokens
    And the Telegram mock accepts audio uploads, document uploads, messages and keyboard edits

  @R1 @R3 @R4
  Scenario Outline: Detect and publish a short video in its source language
    Given the media page title is "<title>"
    And the Whisper mock returns JSON {"text":"<transcript>","language":"<detected>"}
    And the Ollama mock returns valid prompt counts and content "- <summary>"
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives 1 POST request to "/inference"
    And the request is multipart with "file" containing the bytes of "chunk-000.wav"
    And the request has multipart fields "response_format" equal to "verbose_json" and "language" equal to "auto"
    And every Whisper request has multipart fields "translate" equal to "false", "detect_language" equal to "false" and "temperature" equal to "0.0"
    And "transcript.txt" contains exactly "<transcript>" followed by one LF newline in UTF-8
    And the Ollama mock receives exactly 1 POST request to "/api/chat"
    And the Ollama request contains the complete transcript and instructions for <output> bullet points
    And the Telegram summary contains "<summary>"

    Examples:
      | title           | detected | transcript                    | output  | summary                       |
      | Russian lesson  | ru       | Встреча состоится во вторник. | Russian | Встреча назначена на вторник. |
      | English lesson  | en       | The meeting is on Tuesday.    | English | The meeting is on Tuesday.    |
      | English lesson  | russian  | Встреча состоится во вторник. | Russian | Встреча назначена на вторник. |
      | Русская лекция  | english  | The meeting is on Tuesday.    | English | The meeting is on Tuesday.    |
      | Language lesson | RuSsIaN  | Встреча состоится во вторник. | Russian | Встреча назначена на вторник. |
      | Language lesson | english  | The meeting is on Tuesday.    | English | The meeting is on Tuesday.    |

  @R2 @R3
  Scenario Outline: Pin later chunks and include the final short chunk
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns these JSON responses in order:
      | response                                   |
      | {"text":"<first>","language":"<detected>"} |
      | {"text":"<second>","language":"german"}    |
      | {"text":"<last>"}                          |
    And the Ollama mock returns valid prompt counts and content "- <summary>"
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives chunks in order "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And their multipart language fields are "auto, <code>, <code>" in order
    And their multipart response_format fields are "verbose_json, json, json" in order
    And at most 1 chunk request is in flight at a time
    And "transcript.txt" contains exactly "<first> <second> <last>" followed by one LF newline in UTF-8
    And every Ollama request contains instructions for <output> bullet points

    Examples:
      | detected | code | first         | second        | last        | output  | summary                 |
      | russian  | ru   | Первая часть. | Вторая часть. | Заключение. | Russian | Все три части включены. |
      | english  | en   | First part.   | Second part.  | Conclusion. | English | All parts are included. |

  @R2 @R3
  Scenario: Include every Russian chunk at the three-hour limit
    Given WireMock serves an ffmpeg-generated 10800 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns text "Часть-00" through "Часть-17" for the corresponding chunks with first language "russian"
    And the Ollama mock returns valid prompt counts and content "- Включены все восемнадцать частей."
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives all 18 chunks once in playback order
    And the first Whisper request uses language "auto" and every later request uses language "ru"
    And "transcript.txt" contains "Часть-00" through "Часть-17" in order separated by single spaces and followed by one LF newline
    And "audio.wav" does not exist in the job directory
    And the "chunks" directory does not exist in the job directory
    And "source.m4a" remains unchanged

  @R2
  Scenario: Detect again for a new job after a Russian job
    Given the Whisper mock returns language "russian" and text "Русская речь." for the first submission
    And the Ollama mock returns valid prompt counts and content "- Русское резюме." for the first submission
    And the user has sent "http://videos.example/video" in a private chat and received its summary
    And the Whisper mock returns language "english" and text "English speech." for the second submission
    And the Ollama mock returns valid prompt counts and content "- English summary." for the second submission
    When the user sends "http://videos.example/video" in a private chat
    Then the first Whisper request for each submission uses language "auto"
    And the first submission's Ollama request asks for Russian bullet points
    And the second submission's Ollama request asks for English bullet points
    And the second Telegram summary contains "English summary."

  @R2 @R6
  Scenario: Keep the selected language when retrying a later chunk
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"Первая часть.","language":"russian"} for "chunk-000.wav"
    And the Whisper mock's first attempt for "chunk-001.wav" fails with status 500
    And the Whisper mock returns JSON {"text":"Вторая часть."} on its retry
    And the Whisper mock returns JSON {"text":"Заключение."} for "chunk-002.wav"
    And the Ollama mock returns valid prompt counts and content "- Все части включены."
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives chunk attempts in order "chunk-000.wav, chunk-001.wav, chunk-001.wav, chunk-002.wav"
    And their multipart language fields are "auto, ru, ru, ru" in order
    And the Telegram status edits report chunk progress "1/3, 2/3, 3/3" in that order
    And "transcript.txt" contains exactly "Первая часть. Вторая часть. Заключение." followed by one LF newline in UTF-8

  @R4
  Scenario Outline: Carry the selected language through all map and reduction prompts
    Given WireMock serves an ffmpeg-generated 10800 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns language "<detected>" and an ordered <output> transcript that fills exactly 8 summary input chunks at context 8192
    And the Ollama mock returns valid prompt counts and one oversized <output> bullet for each of the first 8 requests
    And each oversized bullet begins with its section number and exceeds the entire reduction input budget
    And the ninth and tenth Ollama answers are valid short <output> bullet summaries
    And the eleventh Ollama answer is a valid short final <output> bullet summary
    When the user sends "http://videos.example/video" in a private chat
    Then the Ollama mock receives exactly 11 POST requests to "/api/chat"
    And those requests include map, intermediate reduction and final reduction prompts
    And every Ollama request contains instructions for <output> bullet points
    And every Ollama request contains model "qwen3:32b", stream false, think false and options.num_ctx 8192
    And the map requests contain the entire transcript in order without omissions or duplication
    And each recorded Ollama request fits its context including instructions, chat overhead and reserved output
    And the final Telegram summary contains the final <output> bullets returned by the Ollama mock

    Examples:
      | detected | output  |
      | russian  | Russian |
      | english  | English |

  @R4 @R7
  Scenario: Cap Russian summary content and escape markup
    Given the media page title is "Наука & <урок>"
    And the Whisper mock returns JSON {"text":"Научная лекция.","language":"russian"}
    And the Ollama mock returns valid prompt counts and Russian bullet content longer than 1500 characters beginning with "- Сравнить <планы> & выводы."
    When the user sends "http://videos.example/video" in a private chat
    Then the displayed summary body has at most 1500 characters under the existing summary counting rule
    And the HTML summary title is "<b>Наука &amp; &lt;урок&gt;</b>"
    And the summary body displays literal "Сравнить <планы> & выводы."
    And no user-controlled title or summary fragment becomes an active HTML tag
    And the summary sendMessage request has valid HTML and at most 4096 characters after HTML entity parsing
    And the summary keyboard contains exactly "Download audio" and "Download transcript"

  @R7
  Scenario: Keep the Russian job interface in English and wait for button taps
    Given the Whisper mock returns JSON {"text":"Русская лекция.","language":"russian"}
    And the Ollama mock returns valid prompt counts and content "- Краткое содержание лекции."
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram mock receives exactly one initial sendMessage with "Queued: position 1" acknowledging the link
    And the status edits contain "Downloading…", "Transcribing 1/1…" and "Summarizing…" in order
    And the status message is finally edited to "Done."
    And the summary contains the duration line "Duration: 59s"
    And the summary keyboard contains exactly "Download audio" and "Download transcript"
    And the callback data is "audio:<jobId>" and "transcript:<jobId>" using the published job's id
    And each callback_data value is at most 64 UTF-8 bytes and contains no filesystem path or source URL
    And the Telegram mock receives no sendAudio or sendDocument request

  @R3 @R8 @R7
  Scenario Outline: Deliver title-derived Cyrillic filenames and unchanged transcript bytes
    Given the media page title is "<title>"
    And the Whisper mock returns JSON {"text":"Русская лекция.","language":"russian"}
    And the Ollama mock returns valid prompt counts and content "- Краткое содержание лекции."
    And the user has sent "http://videos.example/video" in a private chat and received its summary
    When the user taps this summary's Download transcript button
    Then the Telegram mock receives one sendDocument with filename "<stem>.txt", unchanged retained transcript bytes and no caption
    And the transcript bytes decode as UTF-8 "Русская лекция." followed by one LF newline
    And the transcript button changes through "Sending transcript…" to "Transcript sent"
    When the user taps this summary's Download audio button
    Then the Telegram mock receives one sendAudio with filename "<stem>.m4a" and the unchanged retained source bytes
    And the sendAudio multipart caption is exactly "Original audio, not compressed."
    And neither button tap sends a new Whisper or Ollama request

    Examples:
      | title            | stem             |
      | Русская лекция   | Русская лекция   |
      | Лекция: Часть/1? | Лекция_ Часть_1_ |

  @R8
  Scenario: Bound a long Cyrillic title for both uploads
    Given the media page title contains 101 copies of "Я"
    And the Whisper mock returns JSON {"text":"Русская лекция.","language":"russian"}
    And the Ollama mock returns valid prompt counts and content "- Краткое содержание лекции."
    And the user has sent "http://videos.example/video" in a private chat and received its summary
    When the user taps this summary's Download transcript button
    Then the sendDocument multipart filename is exactly 100 copies of "Я" followed by ".txt"
    When the user taps this summary's Download audio button
    Then the sendAudio multipart filename is exactly 100 copies of "Я" followed by ".m4a"
    And both upload filenames are valid Unicode and each is 204 UTF-8 bytes

  @R5
  Scenario Outline: Refuse unsupported language before any later chunk or summary inference
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"<speech>","language":"<detected>"} for "chunk-000.wav"
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Only English and Russian videos are supported. Detected language: <code>."
    And "chunk-000.wav" is sent exactly once
    And "chunk-001.wav" is never sent
    And "chunk-002.wav" is never sent
    And the Ollama mock receives no POST request to "/api/chat"
    And after this submission finishes no working directory for it remains
    And no successful Telegram summary or download keyboard is sent for it
    And the Telegram mock receives no sendAudio or sendDocument request
    And deferred-jobs.json contains no entry for this submission
    And no recovery-promise message is sent for this submission

    Examples:
      | detected  | code    | speech                       |
      | de        | de      | Das Treffen ist am Dienstag. |
      | german    | de      | Das Treffen ist am Dienstag. |
      | spanish   | es      | La reunión es el martes.     |
      | klingon   | unknown | Unrecognized speech.         |
      | zz        | zz      | Unrecognized speech.         |
      | <b>de</b> | unknown | Unrecognized speech.         |

  @R5
  Scenario: Remove a deferred submission when recovery discovers unsupported speech
    Given a previously parked submission for "http://videos.example/video" exists in deferred-jobs.json with its original status message
    And both service health endpoints now answer HTTP 200
    And the Whisper mock returns JSON {"text":"Das Treffen ist am Dienstag.","language":"german"}
    When the deferred retry interval elapses
    Then the original status is edited to "Only English and Russian videos are supported. Detected language: de."
    And deferred-jobs.json contains no entry for this submission
    And the Ollama mock receives no POST request to "/api/chat"
    And after this submission finishes no working directory for it remains
    When another deferred retry interval elapses
    Then no further Whisper inference or unsupported-language notification occurs for this submission

  @R5 @R2
  Scenario: A refused job does not block the next supported job
    Given the Whisper mock returns language "german" and text "Deutsche Rede." for the first submission
    And the user has sent "http://videos.example/video" in a private chat and received the unsupported-language refusal
    And the Whisper mock returns language "russian" and text "Русская речь." for the next submission
    And the Ollama mock returns valid prompt counts and content "- Русское резюме."
    When the user sends "http://videos.example/video" in a private chat
    Then the next submission shows "Queued: position 1" and produces its summary in Telegram
    And the next submission's first Whisper request uses language "auto"
    And the Telegram summary contains "Русское резюме."

  @R1 @R6
  Scenario Outline: Retry unusable first-response metadata without defaulting to English
    Given the Whisper mock returns status 200 with body <body> on the first attempt
    And the Whisper mock returns JSON {"text":"Русская речь.","language":"russian"} on the retry
    And the Ollama mock returns valid prompt counts and content "- Русское резюме."
    When the user sends "http://videos.example/video" in a private chat
    Then "chunk-000.wav" is sent exactly twice
    And both requests use language "auto" and response_format "verbose_json"
    And "transcript.txt" contains exactly "Русская речь." followed by one LF newline in UTF-8
    And the Ollama request contains the complete transcript and instructions for Russian bullet points

    Examples:
      | body                               |
      | {broken                            |
      | {"text":"Речь."}                   |
      | {"text":"Речь.","language":null}   |
      | {"text":"Речь.","language":42}     |
      | {"text":"Речь.","language":true}   |
      | {"text":"Речь.","language":[]}     |
      | {"text":"Речь.","language":{}}     |
      | {"text":"Речь.","language":""}     |
      | {"text":"Речь.","language":" \t"}  |
      | {"text":null,"language":"russian"} |

  @R6
  Scenario: Exhausted metadata retry is a transcription failure rather than an outage
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"Речь.","language":null} on both attempts for "chunk-000.wav"
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Transcription failed. Please try again later."
    And "chunk-000.wav" is sent exactly twice
    And "chunk-001.wav" is never sent
    And the Ollama mock receives no POST request to "/api/chat"
    And after this submission finishes no working directory for it remains
    And deferred-jobs.json contains no entry for this submission

  @R6
  Scenario: A detected language does not turn a later outage into an unsupported-language refusal
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"Русская речь.","language":"russian"} for "chunk-000.wav"
    And the Whisper endpoint refuses connections for both attempts of "chunk-001.wav"
    When the user sends "http://videos.example/video" in a private chat
    Then the original queued status is edited to "Service temporarily unavailable. We will send the result after it recovers."
    And deferred-jobs.json contains exactly one entry for this submission
    And the Ollama mock receives no POST request to "/api/chat"
    And after this submission finishes no working directory for it remains

  @R2 @R3
  Scenario: Detect Russian speech after a silent intro without using the intro language
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns these JSON responses in order:
      | response                                      |
      | {"text":" \t ","language":"german"}           |
      | {"text":"Русская речь.","language":"russian"} |
      | {"text":"Заключение."}                        |
    And the Ollama mock returns valid prompt counts and content "- Русское резюме."
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives chunks in order "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And their multipart language fields are "auto, auto, ru" in order
    And their multipart response_format fields are "verbose_json, verbose_json, json" in order
    And "transcript.txt" contains exactly "Русская речь. Заключение." followed by one LF newline in UTF-8
    And no unsupported-language refusal is sent

  @R2 @R5
  Scenario: Stop on unsupported speech after a silent intro
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"","language":"english"} for "chunk-000.wav"
    And the Whisper mock returns JSON {"text":"Deutsche Rede.","language":"german"} for "chunk-001.wav"
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Only English and Russian videos are supported. Detected language: de."
    And the first two Whisper requests both use language "auto"
    And "chunk-002.wav" is never sent
    And the Ollama mock receives no POST request to "/api/chat"
    And after this submission finishes no working directory for it remains
    And deferred-jobs.json contains no entry for this submission

  @R2 @R6
  Scenario: Report no speech when every chunk is empty regardless of language metadata
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns these JSON responses in order:
      | response                        |
      | {"text":"","language":"german"} |
      | {"text":" \t ","language":null} |
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "No speech was found in this video."
    And the Whisper mock receives each chunk exactly once
    And both requests use language "auto" and response_format "verbose_json"
    And the Ollama mock receives no POST request to "/api/chat"
    And after this submission finishes no working directory for it remains
    And deferred-jobs.json contains no entry for this submission

  @R1 @R3
  Scenario: Normalize detection metadata without changing Russian words or punctuation
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns these JSON responses in order:
      | response                                                   |
      | {"text":"  Первая\n\tчасть.  ","language":" \tRuSsIaN\n "} |
      | {"text":" Вторая\u00a0\u00a0часть — всё готово! "}         |
    And the Ollama mock returns valid prompt counts and content "- Обе части включены."
    When the user sends "http://videos.example/video" in a private chat
    Then "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Первая часть. Вторая часть — всё готово!
      """
    And the second Whisper request uses language "ru"
