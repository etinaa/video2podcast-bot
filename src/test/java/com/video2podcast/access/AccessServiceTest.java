package com.video2podcast.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.video2podcast.access.AccessService.RemoveResult;
import com.video2podcast.access.AccessService.RequestOutcome;
import com.video2podcast.support.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessServiceTest {

  private static final long OWNER = 1;
  private static final long ANN = 100;
  private static final long BOB = 200;
  private static final Duration COOLDOWN = Duration.ofHours(24);

  @TempDir Path dir;

  MutableClock clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
  Path file;
  AccessService service;

  @BeforeEach
  void setUp() {
    file = dir.resolve("allowed-users.json");
    service = newService();
  }

  private AccessService newService() {
    return new AccessService(new AccessStore(file), OWNER, COOLDOWN, clock);
  }

  @Test
  void ownerIsAlwaysAllowedAndUnknownUsersAreNot() {
    assertThat(service.isOwner(OWNER)).isTrue();
    assertThat(service.isAllowed(OWNER)).isTrue();
    assertThat(service.isOwner(ANN)).isFalse();
    assertThat(service.isAllowed(ANN)).isFalse();
  }

  @Test
  void allowAddsUserAndPersistsAcrossRestart() {
    assertThat(service.allow(ANN, "ann", OWNER)).isTrue();

    assertThat(service.isAllowed(ANN)).isTrue();
    assertThat(newService().isAllowed(ANN)).isTrue();
    assertThat(service.users())
        .singleElement()
        .isEqualTo(new AllowedUser(ANN, "ann", clock.instant(), OWNER));
  }

  @Test
  void allowingAnAlreadyAllowedUserOrTheOwnerChangesNothing() {
    service.allow(ANN, "ann", OWNER);

    assertThat(service.allow(ANN, "other", OWNER)).isFalse();
    assertThat(service.allow(OWNER, "owner", OWNER)).isFalse();
    assertThat(service.users()).singleElement().extracting(AllowedUser::username).isEqualTo("ann");
  }

  @Test
  void allowTakesTheUsernameFromAPendingRequest() {
    service.requestAccess(ANN, "ann", "Ann");

    assertThat(service.allow(ANN, null, OWNER)).isTrue();

    assertThat(service.users()).singleElement().extracting(AllowedUser::username).isEqualTo("ann");
    assertThat(service.pending()).isEmpty();
  }

  @Test
  void requestFromUnknownUserCreatesOnePendingRequest() {
    RequestOutcome first = service.requestAccess(ANN, "ann", "Ann");
    RequestOutcome second = service.requestAccess(ANN, "ann", "Ann");

    PendingRequest request = new PendingRequest(ANN, "ann", "Ann", clock.instant(), null);
    assertThat(first).isEqualTo(new RequestOutcome.Created(request));
    assertThat(second).isEqualTo(new RequestOutcome.AlreadyPending(request));
    assertThat(service.pending()).containsExactly(request);
  }

  @Test
  void requestFromAllowedUserOrOwnerIsRecognised() {
    service.allow(ANN, "ann", OWNER);

    assertThat(service.requestAccess(ANN, "ann", "Ann"))
        .isEqualTo(new RequestOutcome.AlreadyAllowed());
    assertThat(service.requestAccess(OWNER, "o", "Owner"))
        .isEqualTo(new RequestOutcome.AlreadyAllowed());
    assertThat(service.pending()).isEmpty();
  }

  @Test
  void pendingRequestAndItsMessageIdSurviveRestart() {
    service.requestAccess(ANN, "ann", "Ann");
    service.recordRequestMessage(ANN, 555);

    assertThat(newService().pending())
        .singleElement()
        .extracting(PendingRequest::requestMessageId)
        .isEqualTo(555);
  }

  @Test
  void recordingAMessageIdForAnUnknownRequestIsIgnored() {
    service.recordRequestMessage(ANN, 555);

    assertThat(service.pending()).isEmpty();
  }

  @Test
  void recordingAMessageIdKeepsOtherRequestsUntouched() {
    service.requestAccess(ANN, "ann", "Ann");
    service.requestAccess(BOB, "bob", "Bob");

    service.recordRequestMessage(BOB, 9);

    assertThat(service.pending())
        .extracting(PendingRequest::id, PendingRequest::requestMessageId)
        .containsExactly(tuple(ANN, null), tuple(BOB, 9));
  }

  @Test
  void denyingAPendingRequestRemovesItAndStartsTheCooldown() {
    service.requestAccess(ANN, "ann", "Ann");

    assertThat(service.deny(ANN, OWNER)).isEqualTo(RemoveResult.REMOVED);

    assertThat(service.pending()).isEmpty();
    assertThat(service.requestAccess(ANN, "ann", "Ann"))
        .isEqualTo(new RequestOutcome.CoolingDown(clock.instant().plus(COOLDOWN)));
  }

  @Test
  void cooldownEndsAfterTheConfiguredTimeAndSurvivesRestart() {
    service.requestAccess(ANN, "ann", "Ann");
    service.deny(ANN, OWNER);

    clock.advance(COOLDOWN.minusSeconds(1));
    assertThat(newService().requestAccess(ANN, "ann", "Ann"))
        .isInstanceOf(RequestOutcome.CoolingDown.class);

    clock.advance(Duration.ofSeconds(1));
    assertThat(newService().requestAccess(ANN, "ann", "Ann"))
        .isInstanceOf(RequestOutcome.Created.class);
  }

  @Test
  void denyingAnAllowedUserRemovesThemAndStartsTheCooldown() {
    service.allow(ANN, "ann", OWNER);
    service.allow(BOB, "bob", OWNER);

    assertThat(service.deny(ANN, OWNER)).isEqualTo(RemoveResult.REMOVED);

    assertThat(service.isAllowed(ANN)).isFalse();
    assertThat(service.isAllowed(BOB)).isTrue();
    assertThat(service.requestAccess(ANN, "ann", "Ann"))
        .isInstanceOf(RequestOutcome.CoolingDown.class);
  }

  @Test
  void denyingAnUnknownUserReportsNotFoundAndLeavesNoCooldown() {
    assertThat(service.deny(ANN, OWNER)).isEqualTo(RemoveResult.NOT_FOUND);

    assertThat(service.requestAccess(ANN, "ann", "Ann")).isInstanceOf(RequestOutcome.Created.class);
  }

  @Test
  void theOwnerCannotBeRemoved() {
    assertThat(service.deny(OWNER, OWNER)).isEqualTo(RemoveResult.OWNER);

    assertThat(service.isAllowed(OWNER)).isTrue();
  }

  @Test
  void allowingADeniedUserClearsTheDenial() {
    service.requestAccess(ANN, "ann", "Ann");
    service.deny(ANN, OWNER);
    assertThat(new AccessStore(file).load().denials()).hasSize(1);

    service.allow(ANN, "ann", OWNER);

    assertThat(new AccessStore(file).load().denials()).isEmpty();
  }

  @Test
  void refusalRepliesAreLimitedToOnePerMinutePerUser() {
    assertThat(service.shouldSendRefusal(ANN)).isTrue();
    assertThat(service.shouldSendRefusal(ANN)).isFalse();
    assertThat(service.shouldSendRefusal(BOB)).isTrue();

    clock.advance(Duration.ofSeconds(59));
    assertThat(service.shouldSendRefusal(ANN)).isFalse();

    clock.advance(Duration.ofSeconds(1));
    assertThat(service.shouldSendRefusal(ANN)).isTrue();
  }

  @Test
  void refusalBookkeepingIsPrunedSoItCannotGrowWithoutBound() {
    for (long id = 1000; id < 1000 + 1500; id++) {
      service.shouldSendRefusal(id);
    }
    clock.advance(Duration.ofMinutes(2));

    service.shouldSendRefusal(ANN);

    assertThat(service.refusalEntries()).isEqualTo(1);
  }
}
