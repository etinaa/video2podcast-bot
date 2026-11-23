@T18
Feature: Finish deferred video links after local model services recover
  Drive Telegram updates and scheduled time; observe HTTP requests and durable files.
  Scheduled retries are distinct from Whisper's immediate per-chunk retry.
  Refused TCP connections are not counted through an HTTP request journal.

  Background:
    Given an allowed Telegram user
    And the application clock is "2026-11-23T12:00:00Z"
    And real yt-dlp and ffmpeg are enabled with the local WireMock media proxy
    And WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper and Ollama mocks begin returning successful answers

  @R1 @R2 @R3 @R4
  Scenario Outline: Park a link when preflight finds an unavailable service
    Given the <service> health HTTP mock refuses connections for the next accepted link
    When the user sends "http://videos.example/video" in a private chat
    Then the original queued status is edited exactly once to "Service temporarily unavailable. We will send the result after it recovers."
    And no separate service-failure sendMessage or "Failed." edit is sent for this submission
    And deferred-jobs.json beside allowed-users.json contains exactly one entry for this URL and the sending user's id, chat id and queued status message id
    And the entry records 0 scheduled attempts and parked time "2026-11-23T12:00:00Z"
    And after this preflight finishes no working directory for this submission remains
    And this submission produces no yt-dlp, ffmpeg, Whisper inference or Ollama chat request
    And the Telegram mock receives no sendAudio or sendDocument request
    Examples:
      | service |
      | Whisper |
      | Ollama  |

  @R1 @R2 @R4
  Scenario: Both unavailable services create one deferred entry and one notice
    Given both health HTTP mocks refuse connections for the next accepted link
    When the user sends "http://videos.example/video" in a private chat
    Then deferred-jobs.json contains exactly one entry for this submission
    And the original queued status is edited exactly once to "Service temporarily unavailable. We will send the result after it recovers."
    And no separate service-failure sendMessage or "Failed." edit is sent for this submission

  @R1 @R3 @R4
  Scenario Outline: An outage after preflight also discards all working files
    Given both service health HTTP mocks answer HTTP 200
    And the <service> base URL refuses inference connections after its successful health response
    When the user sends "http://videos.example/video" in a private chat
    Then deferred-jobs.json contains exactly one entry for this submission
    And the original queued status is edited to "Service temporarily unavailable. We will send the result after it recovers."
    And after this submission finishes no working directory for it remains
    And no successful Telegram summary or download keyboard is sent for it
    Examples:
      | service |
      | Whisper |
      | Ollama  |

  @R3 @R5 @R6
  Scenario: The scheduler completes a parked Whisper job without another Telegram update
    Given the Whisper health HTTP mock refuses connections for the next accepted link
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    When both health HTTP mocks begin answering HTTP 200
    And the application clock advances by two hours and the next deferred retry interval elapses without any Telegram update
    Then the media proxy receives a new download for "http://videos.example/video"
    And the Whisper mock receives the recording's first chunk
    And the Ollama mock receives a POST request to "/api/chat"
    And the Telegram mock receives the usual HTML summary in the stored private chat with "Download audio" then "Download transcript"
    And deferred-jobs.json contains no entry for this submission
    And the successful job directory contains "source.m4a", "transcript.txt" and "job.json"
    And both retained files expire one hour after successful summary publication
    And the Telegram mock receives no sendAudio or sendDocument request
    When the next deferred retry interval elapses without any Telegram update
    Then no second summary or media download is produced for this submission

  @R3 @R6
  Scenario: An Ollama outage repeats transcription from scratch after recovery
    Given both service health HTTP mocks answer HTTP 200
    And the Ollama base URL refuses chat connections after its successful health response
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    And the Whisper mock has already returned text "First transcription"
    And after this submission finishes no working directory for it remains
    When both health HTTP mocks begin answering HTTP 200
    And the Whisper mock begins returning text "Fresh transcription"
    And the Ollama mock begins returning successful answers
    And the application clock advances by two hours and the next deferred retry interval elapses without any Telegram update
    Then the media proxy receives another download for "http://videos.example/video"
    And the Whisper mock receives the recording's first chunk again
    And the Ollama mock receives a chat request containing "Fresh transcription"
    And the Telegram mock receives the usual summary with both download buttons
    And deferred-jobs.json contains no entry for this submission

  @R2 @R4 @R5
  Scenario: A repeated outage updates the existing entry silently
    Given the Whisper health HTTP mock refuses connections
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    And the parked entry and Telegram request journal have been recorded
    When the application clock advances by two hours and the next deferred retry interval elapses without any Telegram update
    Then deferred-jobs.json contains that same entry with 1 scheduled attempt and unchanged URL, owner, chat, status message id and parked time
    And no new Telegram message or status edit is sent for this submission
    And no working directory for this submission remains

  @R1 @R2 @R4 @R5
  Scenario: A different unavailable service does not create a second entry
    Given the Whisper health HTTP mock refuses connections for the next accepted link
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    When the Whisper health HTTP mock begins answering HTTP 200
    And the Ollama health HTTP mock begins refusing connections
    And the application clock advances by two hours and the next deferred retry interval elapses without any Telegram update
    Then deferred-jobs.json contains exactly one entry for this submission with 1 scheduled attempt
    And no new Telegram message or status edit is sent for this submission

  @R2 @R3 @R5 @R7
  Scenario: Restart preserves the queue and attempt budget beyond job retention
    Given the Whisper health HTTP mock refuses connections
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    And one scheduled retry has completed with an outage
    And a snapshot of deferred-jobs.json has been recorded
    When the application restarts using the same work directory and access store
    Then deferred-jobs.json retains the URL, owner, chat, status message id, parked time and count of 1
    And no working directory for this submission remains
    When both health HTTP mocks begin answering HTTP 200
    And the next deferred retry interval elapses without any Telegram update
    Then the Telegram mock receives the usual summary in the original stored chat with both download buttons
    And deferred-jobs.json contains no entry for this submission

  @R2
  Scenario: Invalid JSON is quarantined without destroying access data
    Given deferred-jobs.json beside allowed-users.json contains exactly "{broken"
    And a snapshot of allowed-users.json has been recorded
    When the application restarts using the same work directory and access store
    Then a sibling file named "deferred-jobs.json.corrupt-<time>" contains exactly "{broken"
    And the deferred queue is empty
    And allowed-users.json matches its snapshot
    When the user sends "/whoami" in a private chat
    Then the bot replies with the user's Telegram id

  @R2
  Scenario: A missing deferred queue starts empty
    Given deferred-jobs.json does not exist beside allowed-users.json
    When the application restarts using the same work directory and access store
    Then the deferred queue is empty
    And startup sends no deferred-job Telegram notification

  @R5 @R7
  Scenario: Default budget closes after twelve scheduled outages
    Given DEFERRED_MAX_ATTEMPTS is not overridden
    And the Whisper health HTTP mock refuses connections
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    When eleven successive two-hour deferred retry intervals elapse without any Telegram update
    Then deferred-jobs.json contains that entry with 11 scheduled attempts
    And no new Telegram message or status edit is sent for this submission
    When the twelfth two-hour deferred retry interval elapses without any Telegram update
    Then the stored chat receives exactly one terminal notification "We could not process this link. Please send it again later."
    And deferred-jobs.json contains no entry for this submission
    When another two-hour deferred retry interval elapses without any Telegram update
    Then no further health request or terminal notification is produced for this submission

  @R5 @R7
  Scenario: A configured shorter interval and budget take effect across restart
    Given DEFERRED_RETRY_INTERVAL is "PT10M"
    And DEFERRED_MAX_ATTEMPTS is 2
    And the Whisper health HTTP mock refuses connections
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    When the next ten-minute deferred retry interval elapses without any Telegram update
    Then deferred-jobs.json contains that entry with 1 scheduled attempt
    When the application restarts using the same work directory and access store
    And the next ten-minute deferred retry interval elapses without any Telegram update
    Then the stored chat receives exactly one terminal notification "We could not process this link. Please send it again later."
    And deferred-jobs.json contains no entry for this submission

  @R6 @R7
  Scenario: Recovery on the last permitted attempt still succeeds
    Given DEFERRED_MAX_ATTEMPTS is 2
    And the Whisper health HTTP mock refuses connections
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    And one scheduled retry has completed with an outage
    When both health HTTP mocks begin answering HTTP 200
    And the next deferred retry interval elapses without any Telegram update
    Then the Telegram mock receives the usual summary with both download buttons
    And no terminal notification says "We could not process this link. Please send it again later."
    And deferred-jobs.json contains no entry for this submission

  @R1 @R8
  Scenario Outline: A non-outage failure on retry closes immediately with its ordinary safe error
    Given the Whisper health HTTP mock refuses connections for the next accepted link
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    When both health HTTP mocks begin answering HTTP 200
    And the next attempt encounters <failure>
    And the next deferred retry interval elapses without any Telegram update
    Then the stored chat receives the normal error "<message>"
    And deferred-jobs.json contains no entry for this submission
    And after this submission finishes no working directory for it remains
    And no successful Telegram summary or download keyboard is sent for it
    And no Telegram message contains raw stderr, server diagnostic bodies or job filesystem paths
    Examples:
      | failure                                     | message                                               |
      | private metadata from yt-dlp                 | This video is private or needs a sign-in.             |
      | unsupported metadata from yt-dlp             | This link is not a supported video.                   |
      | metadata duration of 10801 seconds           | The video is longer than 3 hours.                     |
      | live stream metadata                        | Live streams are not supported.                       |
      | yt-dlp download failure after partial output | Could not process this video. Please try again later. |
      | ffmpeg WAV conversion failure               | Could not process this video. Please try again later. |
      | a download timeout                          | Processing took too long and was stopped.             |
      | empty speech in every Whisper chunk         | No speech was found in this video.                    |
      | status 500 on both Whisper attempts          | Transcription failed. Please try again later.         |
      | an exhausted connected Whisper answer timeout | Transcription failed. Please try again later.       |
      | Ollama empty content                        | Summary failed. Please try again later.               |
      | Ollama HTTP 500                              | Summary failed. Please try again later.               |
      | an Ollama answer timeout                    | Processing took too long and was stopped.             |

  @R5 @R8
  Scenario: An earlier failed retry does not prevent the next entry from succeeding
    Given allowed users 101 and 202 have separately parked links in that order
    And both service health HTTP mocks answer HTTP 200
    And user 101's next download fails with private metadata
    When the next deferred retry interval elapses without any Telegram update
    Then user 101 receives "This video is private or needs a sign-in."
    And user 202 receives the usual summary with both download buttons
    And the deferred queue is empty

  @R2 @R5
  Scenario: Deferred FIFO order survives another outage and restart
    Given allowed users 101 and 202 have separately parked links in that order
    And the Whisper health HTTP mock refuses connections
    When the next deferred retry interval elapses without any Telegram update
    Then deferred-jobs.json records one scheduled attempt for each entry in order 101 then 202
    When the application restarts using the same work directory and access store
    And both health HTTP mocks begin answering HTTP 200
    And the next deferred retry interval elapses without any Telegram update
    Then user 101's summary is sent before user 202's summary
    And no two model requests or media processes are in flight concurrently
    And the deferred queue is empty

  @R5
  Scenario: A tick during a held retry does not submit that entry twice
    Given the user has one durably parked link
    And both service health HTTP mocks answer HTTP 200
    And the Whisper mock holds its first answer within its configured timeout
    When the next deferred retry interval elapses without any Telegram update
    And the Whisper mock has received the retry's first chunk and holds its answer
    And another deferred retry interval elapses while that answer is held
    Then the Whisper mock has received that chunk exactly once for the deferred attempt
    And no second download for that deferred attempt is recorded
    When the Whisper mock releases the successful response
    Then exactly one usual summary is sent for this deferred entry
    And the deferred queue is empty

  @R5 @R9
  Scenario: Revoke access while a retry waits behind another user's running link
    Given user 202 has one durably parked link
    And user 101 has a fresh link waiting for a held Whisper answer
    When the next deferred retry interval elapses without any Telegram update
    And the owner sends "/deny 202" in a private chat
    And the access-removal acknowledgement has been received
    And the Whisper mock releases the successful response for user 101
    Then user 101 receives the usual summary with both download buttons
    And user 202's deferred entry is removed
    And no health, media download, inference or chat request is made for user 202's deferred retry
    And the removal produces no Telegram notification to user 202

  @R9
  Scenario: A revoked user is dropped without processing or notification
    Given user 202 has one durably parked link
    And the owner has removed user 202 with "/deny 202"
    When the next deferred retry interval elapses without any Telegram update
    Then user 202's deferred entry is removed
    And no health, media download, inference or chat request is made for user 202's deferred retry
    And the removal produces no Telegram notification to user 202

  @R9
  Scenario: The owner remains eligible without an allowlist entry
    Given the configured owner has one durably parked link and no explicit allowlist entry
    And both service health HTTP mocks answer HTTP 200
    When the next deferred retry interval elapses without any Telegram update
    Then the owner receives the usual summary with both download buttons
    And the deferred queue is empty

  @R2 @R4 @R6
  Scenario: A rejected initial parking edit does not lose the deferred job
    Given the Whisper health HTTP mock refuses connections for the next accepted link
    And the Telegram mock rejects the next temporarily-unavailable status edit with HTTP 400
    When the user sends "http://videos.example/video" in a private chat
    Then deferred-jobs.json contains exactly one entry for this submission
    And no fallback outage sendMessage is attempted
    When both health HTTP mocks begin answering HTTP 200
    And the next deferred retry interval elapses without any Telegram update
    Then the Telegram mock receives the usual summary with both download buttons
    And deferred-jobs.json contains no entry for this submission

  @R7
  Scenario: A rejected exhaustion notification cannot retain an exhausted entry
    Given DEFERRED_MAX_ATTEMPTS is 1
    And the Whisper health HTTP mock refuses connections
    And the user has submitted "http://videos.example/video" and its deferred entry is durably parked
    And the Telegram mock rejects the exhaustion notification with HTTP 400
    When the next deferred retry interval elapses without any Telegram update
    Then the Telegram mock records exactly one exhaustion notification attempt
    And deferred-jobs.json contains no entry for this submission
    When another deferred retry interval elapses without any Telegram update
    Then no further notification or health request is produced for this submission

  @R1 @R8
  Scenario: A connected HTTP failure on the original submission is not deferred
    Given both service health HTTP mocks answer HTTP 200
    And the Whisper mock returns status 500 for both attempts
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Transcription failed. Please try again later."
    And the deferred queue is empty
    And after this submission finishes no working directory for it remains

  @R1
  Scenario: An unsafe URL never enters deferred storage
    When the user sends "http://127.0.0.1/video" in a private chat
    Then the bot replies with "This link is not a supported video."
    And the deferred queue is empty
    And no health, media download, inference or chat request is made for this submission

  @R1
  Scenario: A non-allowlisted sender cannot create deferred work
    Given user 202 is not allowlisted
    When user 202 sends "http://videos.example/video" in a private chat
    Then the deferred queue is empty
    And no health, media download, inference or chat request is made for this submission
    And user 202 receives only the existing access-refusal reply

  @R1
  Scenario: A group link does not create deferred work
    When the user sends "http://videos.example/video" in a group chat
    Then the bot sends nothing
    And the deferred queue is empty
    And no health, media download, inference or chat request is made for this submission

  @R3 @R6
  Scenario: Retry success still requires a transcript button tap before sending the file
    Given the user has one durably parked link
    And both service health HTTP mocks answer HTTP 200
    When the next deferred retry interval elapses without any Telegram update
    Then the Telegram mock receives the usual summary with both download buttons
    And the Telegram mock receives no sendAudio or sendDocument request
    When the user taps the successful summary's transcript button in the stored private chat
    Then the Telegram mock receives one sendDocument containing the fresh transcript and targeting the stored chat
    And the Telegram mock receives no sendAudio request
