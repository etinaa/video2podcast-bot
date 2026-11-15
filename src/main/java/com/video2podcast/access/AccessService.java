package com.video2podcast.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who may use the bot: the owner, the allowlisted users, and everyone else. All state changes are
 * serialized by one lock and written through {@link AccessStore} before they become visible.
 */
public class AccessService {

  private static final Logger LOG = LoggerFactory.getLogger(AccessService.class);

  private static final Duration REFUSAL_INTERVAL = Duration.ofMinutes(1);
  private static final int REFUSAL_PRUNE_THRESHOLD = 1000;

  /** The result of {@link #requestAccess}. */
  public sealed interface RequestOutcome {
    /** The user already has access (allowlisted or the owner). */
    record AlreadyAllowed() implements RequestOutcome {}

    /** A new request was stored. */
    record Created(PendingRequest request) implements RequestOutcome {}

    /** The user already has a request waiting for the owner. */
    record AlreadyPending(PendingRequest request) implements RequestOutcome {}

    /** The user was denied recently and may ask again at {@code until}. */
    record CoolingDown(Instant until) implements RequestOutcome {}
  }

  /** The result of {@link #deny}. */
  public enum RemoveResult {
    REMOVED,
    NOT_FOUND,
    OWNER
  }

  private final AccessStore store;
  private final long ownerId;
  private final Duration cooldown;
  private final Clock clock;
  private final Map<Long, Instant> lastRefusal = new HashMap<>();

  private AccessState state;

  public AccessService(AccessStore store, long ownerId, Duration cooldown, Clock clock) {
    this.store = store;
    this.ownerId = ownerId;
    this.cooldown = cooldown;
    this.clock = clock;
    this.state = store.load();
  }

  public long ownerId() {
    return ownerId;
  }

  public boolean isOwner(long userId) {
    return userId == ownerId;
  }

  public synchronized boolean isAllowed(long userId) {
    return isOwner(userId) || state.users().stream().anyMatch(u -> u.id() == userId);
  }

  public synchronized List<AllowedUser> users() {
    return state.users();
  }

  public synchronized List<PendingRequest> pending() {
    return state.pending();
  }

  public synchronized Optional<PendingRequest> pendingRequest(long userId) {
    return findPending(userId);
  }

  /** Stores an access request from an unknown user, unless one is pending or cooling down. */
  public synchronized RequestOutcome requestAccess(
      long userId, String username, String displayName) {
    if (isAllowed(userId)) {
      return new RequestOutcome.AlreadyAllowed();
    }
    Optional<PendingRequest> existing = findPending(userId);
    if (existing.isPresent()) {
      return new RequestOutcome.AlreadyPending(existing.get());
    }
    Instant now = clock.instant();
    Optional<Instant> until =
        state.denials().stream()
            .filter(d -> d.userId() == userId)
            .map(d -> d.deniedAt().plus(cooldown))
            .filter(end -> end.isAfter(now))
            .findFirst();
    if (until.isPresent()) {
      return new RequestOutcome.CoolingDown(until.get());
    }
    PendingRequest request = new PendingRequest(userId, username, displayName, now, null);
    List<PendingRequest> pending = new ArrayList<>(state.pending());
    pending.add(request);
    update(new AccessState(state.users(), pending, withoutDenial(userId)));
    LOG.info("Access requested by user {} (@{}) at {}", userId, username, now);
    return new RequestOutcome.Created(request);
  }

  /** Remembers the id of the owner's Approve/Deny message so it can be edited later. */
  public synchronized void recordRequestMessage(long userId, int messageId) {
    if (findPending(userId).isEmpty()) {
      return;
    }
    List<PendingRequest> pending =
        state.pending().stream()
            .map(p -> p.id() == userId ? p.withRequestMessageId(messageId) : p)
            .toList();
    update(new AccessState(state.users(), pending, state.denials()));
  }

  /**
   * Adds a user to the allowlist and clears any pending request or denial. The username falls back
   * to the one from the pending request.
   *
   * @return false if the user already had access
   */
  public synchronized boolean allow(long userId, String username, long byUserId) {
    if (isAllowed(userId)) {
      return false;
    }
    String name =
        username != null
            ? username
            : findPending(userId).map(PendingRequest::username).orElse(null);
    Instant now = clock.instant();
    List<AllowedUser> users = new ArrayList<>(state.users());
    users.add(new AllowedUser(userId, name, now, byUserId));
    update(new AccessState(users, withoutPending(userId), withoutDenial(userId)));
    LOG.info("User {} allowed by {} at {}", userId, byUserId, now);
    return true;
  }

  /**
   * Removes a user from the allowlist or rejects their pending request, and starts the cooldown
   * before they can ask again. The owner can't be removed.
   */
  public synchronized RemoveResult deny(long userId, long byUserId) {
    if (isOwner(userId)) {
      return RemoveResult.OWNER;
    }
    boolean wasAllowed = state.users().stream().anyMatch(u -> u.id() == userId);
    if (!wasAllowed && findPending(userId).isEmpty()) {
      return RemoveResult.NOT_FOUND;
    }
    Instant now = clock.instant();
    List<AllowedUser> users = state.users().stream().filter(u -> u.id() != userId).toList();
    List<Denial> denials = new ArrayList<>(withoutDenial(userId));
    denials.add(new Denial(userId, now));
    update(new AccessState(users, withoutPending(userId), denials));
    LOG.info("User {} denied by {} at {}", userId, byUserId, now);
    return RemoveResult.REMOVED;
  }

  /** True at most once per minute per user, so a noisy stranger gets one refusal, not a flood. */
  public synchronized boolean shouldSendRefusal(long userId) {
    Instant now = clock.instant();
    if (lastRefusal.size() > REFUSAL_PRUNE_THRESHOLD) {
      lastRefusal.values().removeIf(last -> !last.plus(REFUSAL_INTERVAL).isAfter(now));
    }
    Instant last = lastRefusal.get(userId);
    if (last != null && last.plus(REFUSAL_INTERVAL).isAfter(now)) {
      return false;
    }
    lastRefusal.put(userId, now);
    return true;
  }

  synchronized int refusalEntries() {
    return lastRefusal.size();
  }

  private Optional<PendingRequest> findPending(long userId) {
    return state.pending().stream().filter(p -> p.id() == userId).findFirst();
  }

  private List<PendingRequest> withoutPending(long userId) {
    return state.pending().stream().filter(p -> p.id() != userId).toList();
  }

  private List<Denial> withoutDenial(long userId) {
    return state.denials().stream().filter(d -> d.userId() != userId).toList();
  }

  private void update(AccessState next) {
    store.save(next);
    state = next;
  }
}
