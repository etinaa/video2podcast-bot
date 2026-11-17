@T9
Feature: Transcribe a video link into a plain-text transcript
  Drive the bot through Telegram; observe Whisper HTTP requests, status edits and files.
  Media fixtures are served by WireMock without external DNS; no process mocks are used.
  JSON escapes denote decoded response characters; transcript doc strings omit the final LF.

  Background:
    Given an allowed Telegram user
    And real yt-dlp and ffmpeg are enabled with the local WireMock media proxy
    And WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper base URL points to the Whisper mock
    And the Ollama mock holds its first answer

  @R1 @R2
  Scenario: Send WAV bytes with the explicit English JSON contract
    Given WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns the T3 fixture "whisper-inference-ok.json" with status 200
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives 1 POST request to "/inference"
    And the request is multipart with "file" containing the bytes of "chunk-000.wav"
    And the request has multipart fields "response_format" equal to "json" and "language" equal to "en"
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Hello, this is a short test of the video to podcast but the meeting is on Tuesday at 10 o'clock, and we will discuss the budget.
      """

  @R1 @R2
  Scenario: Merge all chunks in the supplied playback order
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns these JSON responses in order:
      | response                |
      | {"text":"First part."}  |
      | {"text":"Second part."} |
      | {"text":"Final part."}  |
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives chunks in order "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And at most 1 chunk request is in flight at a time
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First part. Second part. Final part.
      """

  @R1 @R2
  Scenario: Include every chunk of a video at the three-hour limit
    Given WireMock serves an ffmpeg-generated 10800 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns text "part-00" through "part-17" for the corresponding chunks
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives all 18 chunks once in playback order
    And "transcript.txt" contains "part-00" through "part-17" in order separated by single spaces and followed by one LF newline

  @R1 @R2
  Scenario: Include the short final chunk
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns text "First", "Second" and "Remainder" for those chunks
    When the user sends "http://videos.example/video" in a private chat
    Then "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First Second Remainder
      """

  @R2
  Scenario: Collapse segment whitespace and preserve Unicode and punctuation
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns these JSON responses in order:
      | response                                         |
      | {"text":"  Café\n\tpart.  "}                     |
      | {"text":" Second\r\npart — it's ready! ","id":7} |
    When the user sends "http://videos.example/video" in a private chat
    Then "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Café part. Second part — it's ready!
      """

  @R2 @R8
  Scenario: Do not publish or clean up while a later answer is pending
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"First"} for "chunk-000.wav"
    And the Whisper mock holds the answer for "chunk-001.wav" within its answer timeout
    When the user sends "http://videos.example/video" in a private chat
    And the Whisper mock has received "chunk-001.wav" and holds its answer
    Then no "transcript.txt" exists in the job directory
    And "source.m4a", "audio.wav" and every chunk remain unchanged

  @R3 @R6
  Scenario: Skip empty speech without retry or extra separators
    Given WireMock serves an ffmpeg-generated 1850 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns these JSON responses in order:
      | response            |
      | {"text":""}         |
      | {"text":" First "}  |
      | {"text":" \t\r\n "} |
      | {"text":" Last "}   |
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives each chunk exactly once
    And the Telegram status edits report chunk progress "1/4, 2/4, 3/4, 4/4" in that order
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First Last
      """

  @R3 @R7 @R8
  Scenario Outline: Report entirely empty speech in chat
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns <response> with status 200 for both chunks
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "No speech was found in this video."
    And the Whisper mock receives each chunk exactly once

    Examples:
      | response            |
      | {"text":""}         |
      | {"text":" \t\r\n "} |

  @R3 @R4
  Scenario Outline: Broken successful responses are retried rather than called silence
    Given WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns status 200 with body <body> on the first attempt
    And the Whisper mock returns JSON {"text":"Recovered"} on the retry
    When the user sends "http://videos.example/video" in a private chat
    Then "chunk-000.wav" is sent exactly twice
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Recovered
      """

    Examples:
      | body          |
      | {broken       |
      | {}            |
      | {"text":null} |
      | {"text":42}   |
      | {"text":true} |
      | {"text":[]}   |
      | {"text":{}}   |
      | []            |
      | an empty body |

  @R4
  Scenario Outline: Recover a failed chunk without resending earlier work
    Given WHISPER_TIMEOUT is "PT1S"
    And WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"First"} for "chunk-000.wav"
    And the Whisper mock's first attempt for "chunk-001.wav" fails with <failure>
    And the Whisper mock returns JSON {"text":"Second"} on its retry
    And the Whisper mock returns JSON {"text":"Last"} for "chunk-002.wav"
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives chunk attempts in order "chunk-000.wav, chunk-001.wav, chunk-001.wav, chunk-002.wav"
    And every attempt uses the same multipart English JSON contract and corresponding chunk bytes
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First Second Last
      """

    Examples:
      | failure                                                      |
      | a connection refusal                                         |
      | status 400 with T3 fixture whisper-inference-bad-request.txt |
      | status 302 without a usable transcription response           |
      | status 500 with body {"text":"not speech"}                   |
      | status 200 with malformed JSON                               |
      | an answer timeout                                            |

  @R3 @R4
  Scenario: A valid empty answer on retry continues to the next chunk
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock's first attempt for "chunk-000.wav" fails with status 400
    And the Whisper mock returns JSON {"text":" "} on its retry
    And the Whisper mock returns JSON {"text":"Speech"} for "chunk-001.wav"
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock receives chunk attempts in order "chunk-000.wav, chunk-000.wav, chunk-001.wav"
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Speech
      """

  @R4 @R7 @R8
  Scenario Outline: Report the retry failure and stop later chunk requests
    Given WHISPER_TIMEOUT is "PT1S"
    And WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"Earlier success"} for "chunk-000.wav"
    And the Whisper mock's first attempt for "chunk-001.wav" fails with <first_failure>
    And the Whisper mock's retry fails with <retry_failure>
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "<message>"
    And the Whisper mock receives chunk attempts in order "chunk-000.wav, chunk-001.wav, chunk-001.wav"
    And "chunk-002.wav" is never sent

    Examples:
      | first_failure      | retry_failure      | message                                       |
      | connection refusal | connection refusal | The transcription server is unreachable.      |
      | status 400         | connection refusal | The transcription server is unreachable.      |
      | connection refusal | status 400         | Transcription failed. Please try again later. |
      | status 400         | status 400         | Transcription failed. Please try again later. |
      | status 503         | status 503         | Transcription failed. Please try again later. |
      | malformed JSON     | missing text field | Transcription failed. Please try again later. |
      | answer timeout     | answer timeout     | Transcription failed. Please try again later. |
      | status 204 no body | status 204 no body | Transcription failed. Please try again later. |

  @R4 @R5 @R7
  Scenario: Enforce the configured answer timeout on a connected server
    Given WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And WHISPER_TIMEOUT is "PT0.1S"
    And the Whisper mock accepts each connection but delays each answer for 2 seconds
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Transcription failed. Please try again later."
    And "chunk-000.wav" is attempted exactly twice

  @R4 @R5
  Scenario: Each attempt receives a fresh answer wait
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And WHISPER_TIMEOUT is "PT1S"
    And the Whisper mock's first attempt for "chunk-000.wav" exceeds the answer timeout
    And the Whisper mock returns JSON {"text":"First"} after 700 milliseconds
    And the Whisper mock returns for "chunk-001.wav" JSON {"text":"Last"} after 700 milliseconds
    When the user sends "http://videos.example/video" in a private chat
    Then "transcript.txt" exists in the job directory despite total elapsed time exceeding 1 second
    And the Whisper mock receives chunk attempts in order "chunk-000.wav, chunk-000.wav, chunk-001.wav"

  @R6
  Scenario: Report each chunk in Telegram before its first request
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock fails once for "chunk-001.wav" and then returns nonempty text on retry
    And the Whisper mock returns nonempty text for all other chunks
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram status edits report chunk progress "1/3, 2/3, 3/3" in that order
    And each Telegram status edit occurs before the Whisper mock receives that chunk's first request
    And no additional Telegram status edit is sent for a retry

  @R4 @R6
  Scenario: Stop Telegram progress at the terminally failing chunk
    Given WireMock serves an ffmpeg-generated 1250 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns nonempty text for "chunk-000.wav"
    And the Whisper mock returns status 500 for both attempts for "chunk-001.wav"
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram status edits report chunk progress "1/3, 2/3" in that order
    And no Telegram status edit reports chunk 3

  @R7
  Scenario: Raw server diagnostics do not enter the safe failure message
    Given WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns status 500 for both attempts with body "Internal error /private/jobs/secret.wav https://example.com/private-video"
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Transcription failed. Please try again later."

  @R2 @R8
  Scenario: Remove temporary audio after persisting a complete transcript
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns nonempty text for both chunks
    When the user sends "http://videos.example/video" in a private chat
    Then "transcript.txt" is readable and contains the text returned for both chunks
    And "audio.wav" does not exist in the job directory
    And the "chunks" directory does not exist in the job directory
    And "source.m4a" remains unchanged

  @R2
  Scenario: Collapse nonbreaking whitespace between words
    Given WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns JSON {"text":"\u00a0First\u00a0\u00a0part.\u00a0"}
    When the user sends "http://videos.example/video" in a private chat
    Then "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First part.
      """
