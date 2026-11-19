@TD2
Feature: Report the current queue position in Telegram
  Running and waiting jobs count; completed jobs do not.
  Callback, exception, concurrency and shutdown contracts remain in unit tests.
  T11 supplies the steps and enables these scenarios in Cucumber.

  Background:
    Given real yt-dlp and ffmpeg are enabled with the local WireMock media proxy
    And WireMock serves ffmpeg-generated 300 second m4a recordings at "http://videos.example/first", "http://videos.example/second" and "http://videos.example/third"
    And the Whisper and Ollama mocks return successful answers

  @R1
  Scenario: Queued status includes running and waiting jobs
    Given an allowed Telegram user
    And the Whisper mock holds the first job's answer
    And the user has sent "http://videos.example/first" in a private chat
    And the Whisper mock has received the first job's chunk and holds its answer
    And the user has sent "http://videos.example/second" in a private chat
    When the user sends "http://videos.example/third" in a private chat
    Then the status text shows position 3

  @R1 @R2
  Scenario Outline: Queued status excludes a finished job
    Given an allowed Telegram user
    And the Whisper mock returns <response> for every attempt of the first job
    And the user has sent "http://videos.example/first" in a private chat
    And the first job has <outcome>
    When the user sends "http://videos.example/second" in a private chat
    Then the status text shows position 1

    Examples:
      | response                    | outcome                          |
      | status 200 with nonempty text | completed with Telegram status "Done." |
      | status 500                    | reported a failure in Telegram   |
