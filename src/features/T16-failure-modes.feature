@T16
Feature: Detect unavailable local services before downloading a video
  Startup service checks are informational. Each accepted link gets a fresh check.
  Observe Telegram HTTP calls, service HTTP probes, process invocations and durable files.
  Reuse T8-T14 media, delivery, retention and mid-job failure scenarios.

  @R1
  Scenario Outline: Start the bot even when a service cannot be reached
    Given the Whisper health HTTP mock answers with <whisper>
    And the Ollama health HTTP mock answers with <ollama>
    When a fresh bot application starts against the local HTTP mocks
    Then each responding service HTTP mock records one startup health request
    And the captured startup logs contain one health-result line for Whisper reporting "<whisperResult>"
    And the captured startup logs contain one health-result line for Ollama reporting "<ollamaResult>"
    And unreachable health-result lines include a safe failure reason without a stack trace
    And startup sends no service-failure Telegram message and invokes no media process
    When user 202 sends "/whoami" in a private chat after startup
    Then the Telegram mock receives a reply containing Telegram id 202
    Examples:
      | whisper            | ollama             | whisperResult | ollamaResult |
      | HTTP 200           | HTTP 200           | reachable     | reachable    |
      | connection refusal | HTTP 200           | unreachable   | reachable    |
      | HTTP 200           | connection refusal | reachable     | unreachable  |
      | connection refusal | connection refusal | unreachable   | unreachable  |
      | HTTP 503           | HTTP 500           | reachable     | reachable    |

  @R1 @R4
  Scenario: Finish startup checks when both servers accept connections without answering
    Given both health HTTP mocks hold their responses beyond the configured short health deadline
    When a fresh bot application starts against the local HTTP mocks
    Then both startup probes finish within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance
    And the captured startup logs report a timeout for both services without a stack trace
    When user 202 sends "/whoami" in a private chat after startup
    Then the Telegram mock receives a reply containing Telegram id 202

  @R1
  Scenario: Redact diagnostic secrets from startup health logs
    Given the configured health URLs contain user info "probe-user:probe-secret"
    And both health HTTP mocks answer HTTP 503 with body "secret-body\n/private/model"
    When a fresh bot application starts against the local HTTP mocks
    Then the captured health logs identify both services as reachable
    And the captured health logs contain none of "probe-user", "probe-secret", "secret-body" or "/private/model"
    And each health result occupies one line without a stack trace

  @R2
  Scenario: Probe healthy services before processing an accepted link
    Given an allowed Telegram user
    And process mocks are enabled for tool argument checks
    And the Whisper and Ollama mocks return successful answers
    And both health HTTP mocks answer HTTP 200 for the next accepted link
    When the user sends "http://videos.example/video" in a private chat
    Then the Whisper mock records GET "/" with no request body
    And the Ollama mock records GET "/api/version" with no request body
    And both service health HTTP requests finish before this submission's first yt-dlp invocation
    And this submission produces a queued acknowledgement and a summary with both download buttons
    And the Telegram mock receives no sendAudio or sendDocument request

  @R2 @R3
  Scenario Outline: Reject an unavailable service before downloading
    Given an allowed Telegram user
    And process mocks are enabled for tool argument checks
    And the bot has completed healthy startup checks
    And the Whisper health HTTP mock answers with <whisper> for the next accepted link
    And the Ollama health HTTP mock answers with <ollama> for the next accepted link
    When the user sends "http://videos.example/video" in a private chat
    Then the responding peer service HTTP mock records a new health request for this submission
    And the bot replies with "<message>"
    And this submission receives a queued acknowledgement before its failure message and a "Failed." status edit afterwards
    And after this preflight finishes no working directory for this submission remains
    And the yt-dlp mock records no invocation for this submission
    And the ffmpeg mock records no invocation for this submission
    And this submission produces no Whisper inference or Ollama chat request
    And no successful Telegram summary or download keyboard is sent for it
    And the Telegram mock receives no sendAudio or sendDocument request
    Examples:
      | whisper            | ollama             | message                                  |
      | connection refusal | HTTP 200           | The transcription server is unreachable. |
      | HTTP 200           | connection refusal | The summary server is unreachable.       |

  @R2 @R3
  Scenario: Report both unavailable services for the same link
    Given an allowed Telegram user
    And process mocks are enabled for tool argument checks
    And the bot has completed healthy startup checks
    And both health HTTP mocks refuse connections for the next accepted link
    When the user sends "http://videos.example/video" in a private chat
    Then this submission's Telegram failure notification is "The transcription server is unreachable. The summary server is unreachable."
    And after this preflight finishes no working directory for this submission remains
    And this submission produces no yt-dlp, ffmpeg, Whisper inference or Ollama chat request
    And this submission produces no summary keyboard, sendAudio or sendDocument request

  @R3 @R4
  Scenario Outline: Treat a stalled health response as unavailable rather than a processing timeout
    Given an allowed Telegram user
    And process mocks are enabled for tool argument checks
    And the bot has completed healthy startup checks
    And the <service> health HTTP mock holds its response beyond the configured short health deadline
    And the other health HTTP mock answers HTTP 200
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "<message>" within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance
    And this submission sends no message "Processing took too long and was stopped."
    And after this preflight finishes no working directory for this submission remains
    And this submission produces no yt-dlp, ffmpeg, Whisper inference or Ollama chat request
    Examples:
      | service | message                                  |
      | Whisper | The transcription server is unreachable. |
      | Ollama  | The summary server is unreachable.       |

  @R2 @R3
  Scenario: A fresh submission can succeed after services recover without replaying a rejected link
    Given an allowed Telegram user
    And process mocks are enabled for tool argument checks
    And both health HTTP mocks refuse connections for the next accepted link
    When the user sends "http://videos.example/rejected" in a private chat
    Then this submission's Telegram failure notifications contain each existing unreachable message exactly once
    When both health HTTP mocks begin answering HTTP 200
    And the Whisper and Ollama mocks begin returning successful answers
    And the user sends "http://videos.example/recovered" in a private chat
    Then both service HTTP mocks record a fresh health request for the recovered submission
    And only "http://videos.example/recovered" is downloaded and receives a successful summary
    And no working directory for the rejected submission remains

  @R3
  Scenario: Failure cleanup leaves another user's retained files and access data intact
    Given user 101 has a retained unexpired published job with audio and transcript files
    And user 202 is allowed and has a distinct private chat
    And snapshots of user 101's job files and the access file have been recorded
    And both health HTTP mocks refuse connections for the next accepted link
    When user 202 sends "http://videos.example/video" in a private chat
    Then user 202 receives both existing unreachable messages and user 101 receives neither
    And after this preflight finishes no working directory for this submission remains
    And user 101's retained files and the access file match their snapshots

  @R3
  Scenario: A rejected failure notification does not prevent a later submission
    Given an allowed Telegram user
    And process mocks are enabled for tool argument checks
    And the Whisper health HTTP mock refuses its next connection
    And the Ollama health HTTP mock answers HTTP 200
    And the Telegram mock rejects the next service-failure notification with HTTP 500
    When the user sends "http://videos.example/rejected" in a private chat
    Then after this preflight finishes no working directory for this submission remains
    And no audio download is recorded for "http://videos.example/rejected"
    When both health HTTP mocks begin answering HTTP 200
    And the Whisper and Ollama mocks begin returning successful answers
    And the user sends "http://videos.example/recovered" in a private chat
    Then only "http://videos.example/recovered" is downloaded and receives a successful summary

  @R4
  Scenario: Another user's command is answered while a health request is pending
    Given two allowed Telegram users Alice and Bob have distinct private chats
    And both services answered the startup health requests successfully
    And the Whisper health HTTP mock holds Alice's next health response
    And the Ollama health HTTP mock answers HTTP 200
    When Alice sends "http://videos.example/video" in her private chat
    And the Whisper health HTTP mock has received Alice's request
    And Bob sends "/whoami" in his private chat
    Then Bob's Telegram id reply arrives before Alice's held health response is released
    And when that reply arrives Alice's health check is still pending before its configured deadline with no terminal failure notification
    And Alice's submission has not downloaded any audio

  @R4
  Scenario: A retained transcript can be delivered while another user's preflight is pending
    Given user 101 has a retained unexpired published job with audio and transcript files
    And user 202 is allowed and has a distinct private chat
    And the Whisper health HTTP mock holds user 202's next health response
    And the Ollama health HTTP mock answers HTTP 200
    When user 202 sends "http://videos.example/video" in a private chat
    And the Whisper health HTTP mock has received user 202's request
    And user 101 taps the retained job's transcript button in its private chat
    Then the Telegram mock receives one sendDocument targeting stored chat 101 before the held health response is released
    And when that sendDocument arrives user 202's health check is still pending before its configured deadline with no terminal failure notification
    And user 202's submission has not downloaded any audio

  @R3 @R4
  Scenario: A stalled check releases the shared worker at its deadline
    Given two allowed Telegram users Alice and Bob have distinct private chats
    And the bot has completed healthy startup checks
    And HEALTH_TIMEOUT is "PT1S"
    And the Whisper health HTTP mock holds only Alice's next health response
    And all other health, inference and chat HTTP responses are successful
    And process mocks are enabled for tool argument checks
    When Alice sends "http://videos.example/stalled" in her private chat
    And the Whisper health HTTP mock has received Alice's request
    And Bob sends "http://videos.example/following" in his private chat
    Then Alice receives "The transcription server is unreachable." within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance
    And Alice's queued status is edited to "Failed."
    And Bob's first yt-dlp invocation occurs after Alice's preflight ends and before the held HTTP response is released
    And Bob receives a successful summary
    And no yt-dlp invocation or working directory exists for Alice's submission

  @R2 @R4
  Scenario: Check a waiting job when its turn arrives rather than when its link arrives
    Given an allowed Telegram user has submitted "http://videos.example/first" with healthy preflight
    And the Whisper inference HTTP mock holds the first job's answer
    And a second allowed user has a distinct private chat
    When the second user sends "http://videos.example/waiting" in a private chat
    Then the second user receives a queued acknowledgement
    And neither service HTTP mock records a health request for the waiting submission yet
    When the Ollama health HTTP mock begins refusing connections
    And the Whisper inference HTTP mock releases a successful answer for the first job
    Then the first job receives a successful summary
    And the waiting submission receives "The summary server is unreachable."
    And the waiting submission's queued status is edited to "Failed."
    And no yt-dlp invocation or working directory exists for the waiting submission

  @R6
  Scenario Outline: An unknown user cannot resolve a pending access request
    Given user 303 has requested access through a private "/start" message
    And the Telegram mock delivered the owner's request notification as message 701
    And user 202 is not allowlisted
    And a snapshot of the access file has been recorded
    When user 202 taps "access:<action>:303" on private message 701
    Then that callback produces only answerCallbackQuery with "No access."
    And the access file matches its snapshot
    And no approval or denial notification, request-message edit, upload or model request is sent for that callback
    Examples:
      | action  |
      | approve |
      | deny    |

  @R7
  Scenario Outline: Original owner request buttons work after a full application restart
    Given user 303 has requested access through a private "/start" message
    And the Telegram mock delivered the owner's request notification as message 701
    When the bot application is closed and a fresh application starts with the same access file
    Then startup sends no duplicate owner notification for user 303
    And the access file still records user 303's pending request with message id 701
    When the owner taps "access:<action>:303" on the original private message 701
    Then user 303 receives "<notification>"
    And the access file has no pending request for user 303 and records allowed membership as <allowed>
    When the owner taps "access:<action>:303" again on the original private message 701
    Then that callback produces only answerCallbackQuery with "This request is no longer active."
    Examples:
      | action  | notification                                         | allowed |
      | approve | Access granted. Send me a video link to get started. | true    |
      | deny    | Your access request was declined.                    | false   |

  @R7
  Scenario: A denied request keeps its cooldown after another restart
    Given user 303 requested access and the owner denied it through the original callback
    When the bot application is closed and a fresh application starts with the same access file
    And user 303 sends "/start" in a private chat before the PT24H cooldown ends
    Then user 303 receives a reply starting with "Your request was declined. You can ask again after "
    And no new access request notification is sent to the owner for user 303
    And the access file has no pending request for user 303

  @R7
  Scenario: A different request message cannot be used after restart
    Given user 303 has requested access through a private "/start" message
    And the Telegram mock delivered the owner's request notification as message 701
    When the bot application is closed and a fresh application starts with the same access file
    And the owner taps "access:approve:303" on private message 702
    Then that callback produces only answerCallbackQuery with "This request is no longer active."
    And the access file still records user 303's pending request with message id 701
    And the access file does not allow user 303

  @R1 @R7
  Scenario: Retry a missing owner notification at startup despite unavailable model services
    Given the Telegram mock rejected the owner notification for user 303's private "/start" request
    And the access file records user 303's pending request without a notification message id
    And both health HTTP mocks refuse connections during the next startup
    When the bot application is closed and a fresh application starts with the same access file
    Then the Telegram mock receives one retried owner notification for user 303 with Approve and Deny buttons
    And its successful message id is persisted in the access file
    When the owner taps the retried notification's "access:approve:303" button
    Then user 303 receives "Access granted. Send me a video link to get started."
    And the access file allows user 303

  @R7
  Scenario: A successful notification retry is not sent again at the next startup
    Given the access file records user 303's pending request without a notification message id
    When a fresh bot application starts with that access file and successfully notifies the owner as message 701
    And the bot application is closed and a fresh application starts with the same access file
    Then the second startup sends no duplicate owner notification for user 303
    And the access file still records user 303's pending request with message id 701

  @R7
  Scenario: Failed startup notification retry preserves the pending request
    Given the access file records user 303's pending request without a notification message id
    And the Telegram mock rejects the next owner notification with HTTP 500
    When a fresh bot application starts with that access file
    Then the access file still records user 303's pending request without a notification message id
    When the Telegram mock begins accepting owner notifications
    And user 303 sends "/start" in a private chat
    Then the Telegram mock receives a successful owner notification for user 303 with Approve and Deny buttons
    And its successful message id is persisted in the access file

  @R1 @R4
  Scenario: Startup polling responds before held health requests finish
    Given both health HTTP mocks hold their startup responses
    When a fresh bot application starts against the local HTTP mocks
    And both health HTTP mocks have received the startup requests
    And user 202 sends "/whoami" in a private chat before the held responses are released
    Then the Telegram mock receives a reply containing Telegram id 202 before the held responses are released
    And when that reply arrives both startup health checks are still pending before their configured deadlines with no health-result log lines

  @R2
  Scenario Outline: An HTTP error status still establishes service reachability
    Given an allowed Telegram user
    And process mocks are enabled for tool argument checks
    And the Whisper and Ollama mocks return successful inference and chat answers
    And the Whisper health HTTP mock answers with <whisper> for the next accepted link
    And the Ollama health HTTP mock answers with <ollama> for the next accepted link
    When the user sends "http://videos.example/video" in a private chat
    Then both service health HTTP requests finish before this submission's first yt-dlp invocation
    And this submission produces a queued acknowledgement and a summary with both download buttons
    And this submission sends no service-unreachable message
    Examples:
      | whisper  | ollama   |
      | HTTP 404 | HTTP 404 |
      | HTTP 503 | HTTP 500 |
