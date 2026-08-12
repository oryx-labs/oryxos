package io.oryxos.core.session;

public record SessionStats(int active, int archived) {

  public int total() {
    return active + archived;
  }
}
