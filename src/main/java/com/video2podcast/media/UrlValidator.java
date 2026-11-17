package com.video2podcast.media;

import com.video2podcast.job.JobFailure;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks a link before any process sees it. Only http(s) links to public hosts pass. No DNS lookup
 * is made: a host name is accepted or rejected by its spelling alone.
 */
public class UrlValidator {

  private static final int MAX_LENGTH = 2048;
  private static final Pattern DIGITS_AND_DOTS = Pattern.compile("[0-9.]+");
  private static final Pattern DOTTED_QUAD =
      Pattern.compile(
          "(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

  /**
   * Returns the URL unchanged when valid.
   *
   * @throws JobFailure {@code UNSUPPORTED} otherwise
   */
  public String validate(String url) {
    if (url == null || url.isBlank() || url.length() > MAX_LENGTH || hasForbiddenChar(url)) {
      throw reject();
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw JobFailure.of(JobFailure.Kind.UNSUPPORTED, e);
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    if (scheme == null
        || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
        || host == null
        || uri.getUserInfo() != null
        || isForbiddenHost(host)) {
      throw reject();
    }
    return url;
  }

  private static boolean hasForbiddenChar(String url) {
    return url.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
  }

  private static boolean isForbiddenHost(String rawHost) {
    String host = rawHost.toLowerCase(Locale.ROOT);
    while (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (host.isEmpty() || "localhost".equals(host) || host.endsWith(".localhost")) {
      return true;
    }
    if (host.startsWith("[") && host.endsWith("]")) {
      return isForbiddenAddress(host.substring(1, host.length() - 1));
    }
    if (DIGITS_AND_DOTS.matcher(host).matches()) {
      return !DOTTED_QUAD.matcher(host).matches() || isForbiddenAddress(host);
    }
    return false;
  }

  /** {@code literal} is an IP literal, so {@code getByName} parses it without a lookup. */
  private static boolean isForbiddenAddress(String literal) {
    if (literal.indexOf('%') >= 0) {
      return true;
    }
    byte[] bytes;
    InetAddress address;
    try {
      address = InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      return true;
    }
    bytes = address.getAddress();
    if (address.isLoopbackAddress()
        || address.isAnyLocalAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()) {
      return true;
    }
    if (bytes.length == 16) {
      return (bytes[0] & 0xFE) == 0xFC;
    }
    // 100.64.0.0/10
    return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
  }

  private static JobFailure reject() {
    return JobFailure.of(JobFailure.Kind.UNSUPPORTED);
  }
}
