@TD2
Feature: Report the current queue position in Telegram
  Running and waiting jobs count; completed jobs do not.
  Callback, exception, concurrency and shutdown contracts remain in unit tests.
  T11 supplies the steps and enables these scenarios in Cucumber.

  Background:
    Given the yt-dlp and ffmpeg mocks can prepare one 300 second video
    And the Whisper and Ollama mocks return successful answers

  @R1
  Scenario: Queued status includes running and waiting jobs
    Given an allowed Telegram user
    And the user has sent "https://example.com/first" in a private chat
    And the Whisper mock holds the first job's answer
    And the user has sent "https://example.com/second" in a private chat
    When the user sends "https://example.com/third" in a private chat
    Then the status text shows position 3

  @R1 @R2
  Scenario Outline: Queued status excludes a finished job
    Given an allowed Telegram user
    And the user has sent "https://example.com/first" in a private chat
    And the first job has <outcome>
    When the user sends "https://example.com/second" in a private chat
    Then the status text shows position 1

    Examples:
      | outcome                          |
      | produced its summary in Telegram |
      | reported a failure in Telegram   |
