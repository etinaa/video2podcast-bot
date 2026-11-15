package com.video2podcast.access;

import java.util.List;

/** The persisted content of {@code allowed-users.json}. */
public record AccessState(
    List<AllowedUser> users, List<PendingRequest> pending, List<Denial> denials) {

  public AccessState {
    users = users == null ? List.of() : List.copyOf(users);
    pending = pending == null ? List.of() : List.copyOf(pending);
    denials = denials == null ? List.of() : List.copyOf(denials);
  }

  public static AccessState empty() {
    return new AccessState(List.of(), List.of(), List.of());
  }
}
