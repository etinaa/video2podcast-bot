@TD2 @unit_queue
Feature: Queue count is current when a task completion is published
  A queue caller sees waiting and running work in the count.
  A task stops counting before the queue publishes its normal or exceptional completion.
  These are unit-level contract examples covered by JobQueueTest, not functional IT steps.
  Shutdown cancellation and caller-initiated future completion have no new callback-order guarantee.
  The functional runner excludes @TD2 as agreed in tasks/TD2/comms/questions.md.

  @R1
  Scenario: Waiting and running tasks both count
    Given an empty single-worker job queue
    And task "A" is running and held until released
    And task "B" is submitted and held until released
    When the caller reads the queue size
    Then the queue size is 2
    And task "B" has not started

  @R1
  Scenario: A completed task leaves remaining work counted
    Given an empty single-worker job queue
    And task "A" is running and held until released
    And task "B" is submitted and held until released
    When task "A" is released
    And the caller waits for task "A" to complete
    And task "B" has started
    Then the queue size is 1

  @R1 @R2 @R3 @R4 @R7
  Scenario Outline: A caller sees zero after observing completion of the last task
    Given an empty single-worker job queue
    And the last submitted task will <outcome>
    When the caller waits for the last task to complete
    Then the caller observes <completion>
    And the queue size is 0 without waiting for count cleanup

    Examples:
      | outcome                               | completion                                                         |
      | return normally                       | normal completion with a null result                               |
      | throw IllegalStateException "boom"    | ExecutionException caused by the original IllegalStateException     |
      | throw AssertionError "boom"           | ExecutionException caused by IllegalStateException "Queued task ended abnormally" |

  @R2 @R3 @R4 @R6
  Scenario Outline: The last task callback observes the updated count
    Given an empty single-worker job queue
    And task "A" is running and held until released
    And the last submitted task will <outcome>
    And a non-async completion callback is registered on the last task before task "A" is released
    When task "A" is released
    And the caller waits for the callback to finish
    Then the queue size recorded inside the callback is 0
    And the callback observes <completion>

    Examples:
      | outcome                            | completion                                                     |
      | return normally                    | a null result and no exception                                  |
      | throw IllegalStateException "boom" | the original IllegalStateException "boom"                        |
      | throw AssertionError "boom"        | IllegalStateException "Queued task ended abnormally"              |

  @R3 @R4 @R5
  Scenario Outline: Failure does not prevent the next task from running
    Given an empty single-worker job queue
    And task "A" is running and held until released
    And task "A" will <outcome> after release
    And task "B" is submitted to record "after"
    When task "A" is released
    And the caller waits for task "B" to complete
    Then task "B" has recorded "after" exactly once
    And task "A" has an exceptionally completed future
    And the queue size is 0

    Examples:
      | outcome                            |
      | throw IllegalStateException "boom" |
      | throw AssertionError "boom"        |

  @R3 @R5
  Scenario: A runtime failure keeps the existing error log
    Given an empty single-worker job queue
    And the last submitted task will throw IllegalStateException "boom"
    When the caller waits for the last task to complete
    Then the error log contains "Queued task failed" with the thrown exception

  @R5
  Scenario: Tasks execute one at a time in submission order
    Given an empty single-worker job queue
    When the caller submits tasks numbered 0 through 9 in that order
    And the caller waits for all tasks to complete
    Then the recorded execution order is 0 through 9
    And at most 1 task body executed at the same time

  @R5
  Scenario: Graceful close finishes accepted work and rejects new work
    Given an empty single-worker job queue
    And tasks "A" and "B" are submitted in that order
    And both tasks can finish within the shutdown wait
    When the caller closes the queue
    Then tasks "A" and "B" have completed normally in that order
    And the queue size is 0
    And a new submission is rejected with RejectedExecutionException

  @R5
  Scenario: Forced close cancels unfinished work
    Given an empty single-worker job queue with a shutdown wait of 200 milliseconds
    And task "A" is running and cannot finish within the shutdown wait
    And tasks "B" and "C" are submitted in that order
    When the caller closes the queue
    Then the futures of tasks "A", "B" and "C" are cancelled
    And tasks "B" and "C" have not executed
    And the queue size is 0
    And a new submission is rejected with RejectedExecutionException

  @R5
  Scenario: Interrupted close preserves the interrupt flag
    Given an empty single-worker job queue
    And task "A" is running and held until released
    And the calling thread has its interrupt flag set
    When the caller closes the queue
    Then the calling thread still has its interrupt flag set
    And the future of task "A" is cancelled
    And the queue size is 0
