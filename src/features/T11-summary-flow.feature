@T11
Feature: Receive a summary and download buttons after sending a video link
  A link produces progress, a summary and retained files, with no automatic uploads.
  Requirement tags in this feature refer to tasks/T11/requirements.md.

  Background:
    Given an allowed Telegram user
    And real yt-dlp and ffmpeg are enabled with the local WireMock media proxy
    And WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper and Ollama mocks return successful answers

  @R2
  Scenario: Acknowledge a new link while the worker is occupied
    Given the Whisper mock holds the first job's answer
    And the user has sent "http://videos.example/video" in a private chat
    And the Whisper mock has received the first job's chunk and holds its answer
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram mock receives the second link's single queued acknowledgement "Queued: position 2" within 5 seconds of its update
    And the second link's status text shows position 2 before the held Whisper answer is released

  @R2
  Scenario: Start at position one in an empty queue
    Given the Whisper mock holds its first answer
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram mock receives the single queued acknowledgement "Queued: position 1" within 5 seconds of the update
    And the status text shows position 1

  @R2 @R1
  Scenario: Commands remain responsive during transcription
    Given the Whisper mock holds the first job's answer
    And the user has sent "http://videos.example/video" in a private chat
    And the Whisper mock has received the first job's chunk and holds its answer
    When the user sends "/whoami" in a private chat
    Then the bot replies with the user's Telegram id before the held Whisper answer is released

  @R3 @R4
  Scenario: Edit one status message through every processing stage
    Given WireMock serves an ffmpeg-generated 650 second m4a recording at "http://videos.example/video"
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram mock receives exactly one initial sendMessage with "Queued: position 1" acknowledging the link
    And the status edits contain "Downloading…", "Transcribing 1/2…", "Transcribing 2/2…" and "Summarizing…" in order
    And every processing status edit targets the initial status message id in the originating chat
    And each transcription edit precedes the corresponding chunk's first Whisper request
    And the Ollama mock receives no request before both Whisper chunks have succeeded
    And the bot sends one successful Telegram summary with a different message id from the status
    And the status message is finally edited to "Done."

  @R5 @R6
  Scenario: Publish HTML content and matching download buttons without uploads
    Given the media page title is "Budget meeting"
    And the Ollama mock returns valid prompt counts and content "- The budget meeting is on Tuesday."
    When the user sends "http://videos.example/video" in a private chat
    Then the summary sendMessage request targets the originating private chat with HTML parse mode and disabled link previews
    And the summary contains the bold title "Budget meeting", the duration line "Duration: 59s" and bullet "The budget meeting is on Tuesday."
    And the summary keyboard contains exactly "Download audio" and "Download transcript"
    And the callback data is "audio:<jobId>" and "transcript:<jobId>" using the published job's id
    And each callback_data value is at most 64 UTF-8 bytes and contains no filesystem path or source URL
    And the Telegram mock receives no sendAudio or sendDocument request
    And no delivery audio file is created before a button tap

  @R5
  Scenario: Escape markup in both the video title and model output
    Given the media page title is "R&D <b>Budget</b>"
    And the Ollama mock returns valid prompt counts and content "- Use <a href='https://example.com'>notes</a> & compare > 2 plans."
    When the user sends "http://videos.example/video" in a private chat
    Then the HTML summary title is "<b>R&amp;D &lt;b&gt;Budget&lt;/b&gt;</b>"
    And the summary body contains escaped literal text for the model's link tags, ampersand and greater-than sign
    And no user-controlled title or summary fragment becomes an active HTML tag
    And the displayed title and bullet text contain the original characters without double escaping

  @R5
  Scenario: Keep the complete message inside Telegram's text limit
    Given the media page title contains 5000 copies of "&"
    And the Ollama mock returns valid prompt counts and English bullet content of 1500 characters
    When the user sends "http://videos.example/video" in a private chat
    Then the bot sends one successful Telegram summary with at most 4096 characters after HTML entity parsing
    And the summary has valid HTML, a nonempty bold title, a duration and both download buttons
    And the displayed bold title is exactly 199 ampersands followed by "…"
    And job.json retains the full original title of 5000 ampersands
    And the summary body retains the full 1500 characters independently of title bounding

  @R7 @R6
  Scenario: Save the published job with both undelivered files
    Given the Telegram mock returns message id 701 for the summary
    When the user sends "http://videos.example/video" in a private chat
    Then the published job's "job.json" records its jobId, the sender's user id and originating chat id
    And "job.json" records summaryMessageId 701, the metadata title and durationSeconds 59
    And "job.json" records createdAt no later than summary publication
    And the AUDIO file entry points to "source.m4a" with state "AVAILABLE" and null sentAt
    And the TRANSCRIPT file entry points to "transcript.txt" with state "AVAILABLE" and null sentAt
    And both file entries have expiresAt equal to the successful summary-send time plus 1 hour
    And "source.m4a" has the same hash as the m4a fixture served by WireMock
    And "transcript.txt" is readable and contains the text returned by the Whisper mock
    And "audio.wav" and the "chunks" directory do not exist in the job directory
    And the Telegram mock receives no sendAudio or sendDocument request

  @R7
  Scenario: Start retention at summary publication after a long wait
    Given the application clock is "2026-11-19T10:00:00Z"
    And the Ollama mock holds its first answer
    And the user has sent "http://videos.example/video" in a private chat
    And the Ollama mock has received its first request and holds its answer
    When the application clock advances to "2026-11-19T12:00:00Z"
    And the Ollama mock releases its successful answer
    Then the bot sends one successful Telegram summary
    And both stored file entries expire at "2026-11-19T13:00:00Z"
    And "source.m4a" and "transcript.txt" remain readable in the published job directory

  @R7
  Scenario: Apply configured retention rather than a hard-coded hour
    Given JOB_RETENTION is "PT30M"
    When the user sends "http://videos.example/video" in a private chat
    Then both file entries have expiresAt equal to the successful summary-send time plus 30 minutes

  @R1
  Scenario: Refuse a URL from a user without access before starting work
    Given the Telegram user is not allowed and has not received a refusal in the last minute
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with the existing no-access refusal containing the sender's Telegram id
    And the media, Whisper and Ollama mocks receive no requests for this update
    And no new job directory or summary message is created

  @R1
  Scenario Outline: Ignore URLs outside private chats
    When the user sends "http://videos.example/video" in a <chat_type>
    Then the bot sends nothing
    And the media, Whisper and Ollama mocks receive no requests for this update
    And no new job directory is created

    Examples:
      | chat_type  |
      | group chat |
      | supergroup |
      | channel    |

  @R8 @R6
  Scenario Outline: Remove failed jobs and preserve the safe stage error
    Given the external fixture for this submission causes <failure>
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "<message>"
    And after this submission finishes no working directory for it remains
    And no successful Telegram summary or download keyboard is sent for it
    And the Telegram mock receives no sendAudio or sendDocument request
    And no Telegram message contains raw stderr, server diagnostic bodies or job filesystem paths

    Examples:
      | failure                                  | message                                               |
      | private metadata from yt-dlp              | This video is private or needs a sign-in.             |
      | unsupported metadata from yt-dlp          | This link is not a supported video.                   |
      | metadata duration of 10801 seconds        | The video is longer than 3 hours.                     |
      | live stream metadata                     | Live streams are not supported.                       |
      | yt-dlp download failure after partial output | Could not process this video. Please try again later. |
      | ffmpeg WAV conversion failure            | Could not process this video. Please try again later. |
      | ffmpeg split failure                     | Could not process this video. Please try again later. |
      | a download timeout                       | Processing took too long and was stopped.             |
      | empty speech in every Whisper chunk      | No speech was found in this video.                    |
      | connection refusal on both Whisper attempts | The transcription server is unreachable.            |
      | status 500 on both Whisper attempts      | Transcription failed. Please try again later.         |
      | Ollama connection refusal                | The summary server is unreachable.                    |
      | Ollama empty content                     | Summary failed. Please try again later.               |
      | Ollama silently truncated prompt         | Summary failed. Please try again later.               |
      | an Ollama answer timeout                 | Processing took too long and was stopped.             |

  @R8
  Scenario: Reject an unsafe URL without retaining a job
    Given process mocks are enabled for the rejected link
    When the user sends "http://127.0.0.1/video" in a private chat
    Then the bot replies with "This link is not a supported video."
    And the yt-dlp mock records no invocation
    And the Whisper and Ollama mocks receive no requests
    And no working directory for this submission remains
    And no queued status message is sent for this submission

  @R4
  Scenario: Process queued submissions in order without overlapping jobs
    Given WireMock serves ffmpeg-generated 300 second m4a recordings at "http://videos.example/first", "http://videos.example/second" and "http://videos.example/third"
    And the Whisper mock holds the first job's answer
    And the user has sent "http://videos.example/first" in a private chat
    And the Whisper mock has received the first job's chunk and holds its answer
    And the user has sent "http://videos.example/second" in a private chat
    And the user has sent "http://videos.example/third" in a private chat
    Then no media HTTP request for the second or third job is received while the first answer is held
    When the Whisper mock releases the first job's successful answer
    Then successful Telegram summaries arrive for first, second and third in that order
    And no media request for a later job precedes the preceding job's final summary send
    And the 3 summaries use distinct job ids and matching job.json files

  @R4 @R8
  Scenario: Continue after a failed job without deleting another job or access data
    Given a completed job has readable source, transcript and job.json files
    And the access file contains the allowed user's persisted entry
    And the Whisper mock holds the first job's answer
    And the user has sent "http://videos.example/video" in a private chat
    And the Whisper mock has received the first job's chunk and holds its answer
    And the user has sent "http://videos.example/video" in a private chat
    When the Whisper mock releases status 500 for both attempts of the first job and successful text for the second job
    Then the bot replies with "Transcription failed. Please try again later." for the first job
    And after the first submission finishes its working directory is absent
    And the second job produces its summary in Telegram and retains its source, transcript and job.json
    And the previously completed job's files and the access file remain unchanged

  @R7 @R1
  Scenario: Keep two allowed users' summaries and retained files associated with their own chats
    Given two allowed Telegram users Alice and Bob have distinct private chats
    When Alice sends "http://videos.example/video" and then Bob sends "http://videos.example/video" in their private chats
    Then each chat receives exactly its own single queued acknowledgement/status and its own summary
    And the summary buttons in each chat refer to that user's distinct stored job id
    And each job.json records its own sender's user id and originating chat id
    And neither summary references the other user's job id

  @R1
  Scenario: Reject non-link text without entering the queue
    When the user sends "hello" in a private chat
    Then the bot replies with "This link is not a supported video."
    And no queued status message is sent for this submission
    And the media, Whisper and Ollama mocks receive no requests for this update
    And no new job directory is created

  @R5
  Scenario: Collapse title whitespace only for display
    Given the media page title is "  Budget\t review\n  meeting  " with decoded whitespace
    When the user sends "http://videos.example/video" in a private chat
    Then the HTML summary title is "<b>Budget review meeting</b>"
    And job.json retains the original title including its whitespace

  @R8
  Scenario: Stop before media work when Telegram rejects the queued acknowledgement
    Given the Telegram mock rejects the queued sendMessage with status 500
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram mock receives exactly one queued sendMessage attempt for this submission
    And the media, Whisper and Ollama mocks receive no requests for this update
    And no new job directory is created
    And no retry or error-message send is attempted for this submission
    When the Telegram mock accepts requests again
    And the user sends "http://videos.example/video" in a private chat
    Then the next submission shows "Queued: position 1" and produces its summary in Telegram

  @R8 @R3
  Scenario: Continue processing after Telegram rejects a progress edit
    Given the Telegram mock rejects the "Downloading…" editMessageText with status 500
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram mock receives exactly one "Downloading…" edit attempt
    And the Telegram mock receives subsequent "Transcribing 1/1…" and "Summarizing…" edits
    And the bot sends one successful Telegram summary
    And the status message is finally edited to "Done."
    And the published job retains its source, transcript and job.json

  @R8
  Scenario: Clean the job when Telegram rejects the summary send
    Given the Telegram mock rejects the summary sendMessage with status 500 and body "private /tmp/secret"
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram mock receives exactly one summary send attempt
    And the bot replies with "Could not process this video. Please try again later."
    And the status message is finally edited to "Failed."
    And after this submission finishes no working directory for it remains
    And no Telegram message contains "private /tmp/secret"

  @R8
  Scenario: Clean the job when its initial job.json cannot be written
    Given the Ollama mock holds its first answer
    And the user has sent "http://videos.example/video" in a private chat
    And the Ollama mock has received its first request and holds its answer
    And a nonempty directory occupies the job's "job.json" path
    When the Ollama mock releases its successful answer
    Then the bot replies with "Could not process this video. Please try again later."
    And no summary sendMessage is attempted
    And after this submission finishes no working directory for it remains
    And the status message is finally edited to "Failed."

  @R8 @R7
  Scenario Outline: Attempt to withdraw buttons when publication cannot be persisted
    Given the Telegram mock holds the successful response to the summary sendMessage
    And the Telegram mock returns status <withdrawal_status> for the summary withdrawal edit
    And the user has sent "http://videos.example/video" in a private chat
    And the Telegram mock has received the summary sendMessage and holds its response
    And a nonempty directory replaces the job's "job.json" file
    When the Telegram mock releases the successful summary response with message id 701
    Then the Telegram mock receives an editMessageText for message 701 with the same summary HTML and no buttons
    And the bot replies with "Could not process this video. Please try again later."
    And after this submission finishes no working directory for it remains
    And the status message is finally edited to "Failed."

    Examples:
      | withdrawal_status |
      | 200               |
      | 500               |

  @R8 @R4
  Scenario Outline: Release the queue when Telegram rejects a terminal notification
    Given the external answers cause <outcome> for the first submission
    And the Telegram mock rejects <notification> with status 500
    And the user has sent "http://videos.example/video" in a private chat
    And the Telegram mock has received the terminal notification attempt
    When the Telegram mock accepts requests again
    And the Whisper and Ollama mocks return successful answers
    And the user sends "http://videos.example/video" in a private chat
    Then the next submission shows "Queued: position 1" and produces its summary in Telegram
    And the first submission's rejected notification is not retried

    Examples:
      | outcome                      | notification                 |
      | a successful summary         | the final "Done." status edit |
      | a terminal Whisper failure   | the failure sentence send    |
      | a terminal Whisper failure   | the final "Failed." status edit |

  @TD10 @R5
  Scenario Outline: The summary shows the duration with seconds
    Given the media page title is "Budget meeting"
    And the media page duration is <seconds> seconds
    And the Ollama mock returns valid prompt counts and content "- The budget meeting is on Tuesday."
    When the user sends "http://videos.example/video" in a private chat
    Then the summary contains the duration line "Duration: <line>"

    Examples:
      | seconds | line      |
      | 45      | 45s       |
      | 114     | 1m 54s    |
      | 120     | 2m        |
      | 3600    | 1h        |
      | 3659    | 1h 59s    |
      | 9390    | 2h 36m 30s |
      | 10800   | 3h        |
