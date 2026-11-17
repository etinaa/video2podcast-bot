@T8
Feature: Prepare audio after a video link message
  Drive the bot through Telegram; observe process mocks, Whisper requests and files.
  Manual cases use real tools and a controlled downloadable video, never a direct wrapper call.

  Background:
    Given an allowed Telegram user
    And the yt-dlp and ffmpeg mocks can prepare one 300 second video
    And the Whisper and Ollama mocks return successful answers

  @R1 @R2
  Scenario Outline: Pass public link messages unchanged to yt-dlp
    When the user sends "<url>" in a private chat
    Then the yt-dlp mock records "<url>" as one final argument immediately after "--"

    Examples:
      | url                                          |
      | https://www.youtube.com/watch?v=abc&list=xyz |
      | http://example.com/video                     |
      | HTTPS://example.com/video                    |
      | https://8.8.8.8/video                        |
      | https://[2606:4700:4700::1111]/video         |
      | https://unresolvable.example/video           |
      | http://172.15.1.1/video                      |
      | http://172.32.1.1/video                      |
      | http://192.169.1.1/video                     |

  @R2
  Scenario Outline: Reject restricted link messages before invoking yt-dlp
    When the user sends "http://<host>/video" in a private chat
    Then the bot replies with "This link is not a supported video."
    And the yt-dlp mock records no invocation

    Examples:
      | host                 |
      | localhost            |
      | LOCALHOST.           |
      | a.localhost          |
      | 127.0.0.1            |
      | 127.0.0.2            |
      | 10.1.2.3             |
      | 172.16.0.1           |
      | 172.31.255.254       |
      | 192.168.1.1          |
      | 169.254.1.1          |
      | [::1]                |
      | [fc00::1]            |
      | [fd12::1]            |
      | [fe80::1]            |
      | [::ffff:127.0.0.1]   |
      | [::ffff:192.168.1.1] |
      | 0.0.0.0              |
      | [::]                 |
      | 100.64.0.1           |
      | 127.1                |
      | 2130706433           |

  @R3
  Scenario: Pass URL punctuation literally to yt-dlp
    When the user sends "https://example.com/video?q=a;b&value=$(id)" in a private chat
    Then the yt-dlp mock records the complete URL as one final argument immediately after "--" for metadata and download

  @R4
  Scenario Outline: Report media stage timeouts in chat
    Given the process mock reports a deadline expiry for "<stage>"
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "Processing took too long and was stopped."

    Examples:
      | stage    |
      | metadata |
      | download |
      | WAV      |
      | split    |

  @R5
  Scenario Outline: Enforce the duration limit before downloading
    Given MAX_VIDEO_DURATION is "<limit>"
    And the yt-dlp mock returns title "Example talk" and duration <seconds> seconds
    When the user sends "https://example.com/video" in a private chat
    Then the yt-dlp mock records <downloads> audio download invocations

    Examples:
      | limit | seconds | downloads |
      | PT3H  | 10800   | 1         |
      | PT3H  | 10800.1 | 0         |
      | PT3H  | 10801   | 0         |
      | PT2H  | 7200    | 1         |
      | PT2H  | 7201    | 0         |

  @R5 @R10
  Scenario: Explain the configured duration limit
    Given MAX_VIDEO_DURATION is "PT2H"
    And the yt-dlp mock returns title "Example talk" and duration 7201 seconds
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "The video is longer than 2 hours."

  @R5
  Scenario Outline: Reject live or unusable metadata in chat
    Given the yt-dlp mock exits with code 0 and metadata <metadata>
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "<message>"
    And the yt-dlp mock records no audio download invocation

    Examples:
      | metadata                                       | message                                               |
      | {"title":"Live"}                               | This link is not a supported video.                   |
      | {"title":"Live","duration":null}               | This link is not a supported video.                   |
      | {"title":"Live","duration":300,"is_live":true} | Live streams are not supported.                       |
      | {"title":"Talk","duration":"unknown"}          | This link is not a supported video.                   |
      | {"title":"Talk","duration":0}                  | This link is not a supported video.                   |
      | {"title":"Talk","duration":-1}                 | This link is not a supported video.                   |
      | not-json                                       | Could not process this video. Please try again later. |
      | []                                             | Could not process this video. Please try again later. |
      | {"duration":300}                               | Could not process this video. Please try again later. |
      | {"title":" ","duration":300}                   | Could not process this video. Please try again later. |

  @R6
  Scenario Outline: Retain downloaded source audio
    Given the yt-dlp mock writes "<file>" for a 300 second video
    And the Whisper mock holds its first answer
    When the user sends "https://example.com/video" in a private chat
    Then the yt-dlp mock records format selector "bestaudio[ext=m4a]/bestaudio"
    And "<file>" exists in the job directory with the bytes written by the mock
    And the process mock records no audio delivery re-encoding invocation

    Examples:
      | file        |
      | source.m4a  |
      | source.webm |

  @R6
  Scenario: Process one video when its URL also includes playlist context
    When the user sends "https://www.youtube.com/watch?v=abc&list=xyz" in a private chat
    Then the yt-dlp mock records "--no-playlist" for metadata and download
    And the yt-dlp mock records one audio download invocation

  @R5 @R6
  Scenario: Do not expand a playlist-only URL into audio downloads
    Given the yt-dlp mock returns metadata for a playlist rather than one video
    When the user sends "https://www.youtube.com/playlist?list=xyz" in a private chat
    Then the bot replies with "This link is not a supported video."
    And the yt-dlp mock records no audio download invocation

  @R6 @R10
  Scenario Outline: Report a download without a completed source file
    Given the yt-dlp mock exits with code 0 and leaves <files> after download
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "Could not process this video. Please try again later."

    Examples:
      | files                |
      | no files             |
      | only source.m4a.part |
      | only source.m4a.ytdl |

  @R7 @manual_media
  Scenario: Produce a Whisper-compatible WAV while retaining the source
    Given real yt-dlp and ffmpeg are enabled for the controlled video URL "https://example.com/video"
    And that video supplies source audio in WebM format
    And the Whisper mock holds its first answer
    When the user sends "https://example.com/video" in a private chat
    Then "audio.wav" in the job directory contains 16000 Hz mono PCM signed 16-bit little-endian audio
    And "source.webm" has the same hash as the downloaded source

  @R8 @manual_media
  Scenario: Split audio into ordered chunks including the final remainder
    Given real yt-dlp and ffmpeg are enabled for the controlled video URL "https://example.com/video"
    And that video contains 1250 seconds of known audio
    And the Whisper mock holds its first answer
    When the user sends "https://example.com/video" in a private chat
    Then "chunks/chunk-000.wav", "chunks/chunk-001.wav" and "chunks/chunk-002.wav" contain approximately 600, 600 and 50 seconds of 16000 Hz mono PCM audio
    And concatenating their audio in filename order reproduces "audio.wav" without dropped or duplicated intervals

  @R8 @manual_media
  Scenario Outline: Keep short recordings as one nonempty chunk
    Given real yt-dlp and ffmpeg are enabled for the controlled video URL "https://example.com/video"
    And that video contains <seconds> seconds of known audio
    And the Whisper mock holds its first answer
    When the user sends "https://example.com/video" in a private chat
    Then "chunks/chunk-000.wav" contains the full recording
    And the chunks directory contains no other WAV files

    Examples:
      | seconds |
      | 59      |
      | 45      |
      | 600     |

  @R8
  Scenario: Send numbered chunk files in playback order
    Given the ffmpeg mock creates "chunk-002.wav", "chunk-000.wav" and "chunk-001.wav" in that order
    When the user sends "https://example.com/video" in a private chat
    Then the Whisper mock receives chunks in order "chunk-000.wav, chunk-001.wav, chunk-002.wav"

  @R8 @R10
  Scenario: Report a split without chunks
    Given the ffmpeg mock exits with code 0 but creates no chunk files
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "Could not process this video. Please try again later."

  @R7 @R8 @R10
  Scenario Outline: Report ffmpeg errors safely in chat
    Given the ffmpeg mock for "<stage>" exits with code 1 and stderr "private /tmp/secret"
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "Could not process this video. Please try again later."
    And no Telegram message contains "private /tmp/secret"

    Examples:
      | stage |
      | WAV   |
      | split |

  @R9 @R10
  Scenario Outline: Explain yt-dlp failures safely in chat
    Given the yt-dlp mock exits with code 1 and stderr "<stderr>"
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "<message>"

    Examples:
      | stderr                    | message                                               |
      | PRIVATE content           | This video is private or needs a sign-in.             |
      | Please SIGN IN            | This video is private or needs a sign-in.             |
      | MEMBERS-ONLY video        | This video is private or needs a sign-in.             |
      | AGE restriction           | This video is private or needs a sign-in.             |
      | Unsupported URL           | This link is not a supported video.                   |
      | PRIVATE: Unsupported URL  | This video is private or needs a sign-in.             |
      | Network connection failed | Could not process this video. Please try again later. |
      |                           | Could not process this video. Please try again later. |

  @R9
  Scenario Outline: Explain sign-in failures at either yt-dlp stage
    Given the yt-dlp mock for "<stage>" exits with code 1 and stderr "Please sign in"
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "This video is private or needs a sign-in."

    Examples:
      | stage    |
      | metadata |
      | download |

  @R9
  Scenario: Continue after successful metadata with warning text
    Given the yt-dlp mock returns title "Example talk", duration 300 seconds and stderr "private warning text" with exit code 0
    When the user sends "https://example.com/video" in a private chat
    Then the yt-dlp mock records one audio download invocation

  @R7
  Scenario: Report conversion without the expected WAV
    Given the ffmpeg mock exits with code 0 but creates no "audio.wav"
    When the user sends "https://example.com/video" in a private chat
    Then the bot replies with "Could not process this video. Please try again later."

  @R7 @stubbed_media
  Scenario: Request Whisper WAV options for a link message
    Given the ffmpeg mock creates an output fixture at "audio.wav" with exit code 0
    And the Whisper mock holds its first answer
    When the user sends "https://example.com/video" in a private chat
    Then the ffmpeg mock records separate source and output path arguments
    And the ffmpeg mock records "-vn", "-ar 16000", "-ac 1" and "-c:a pcm_s16le" as options and values
    And "audio.wav" in the job directory contains the fixture bytes

  @R8 @stubbed_media
  Scenario: Request ten-minute segments for a link message
    Given the ffmpeg mock creates "chunk-002.wav", "chunk-000.wav" and "chunk-001.wav" fixtures with exit code 0
    When the user sends "https://example.com/video" in a private chat
    Then the ffmpeg mock records a segment duration of 600 seconds and audio stream copy
    And the Whisper mock receives chunks in order "chunk-000.wav, chunk-001.wav, chunk-002.wav"
