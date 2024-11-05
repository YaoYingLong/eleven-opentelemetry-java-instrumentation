/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.http;

import io.opentelemetry.instrumentation.api.instrumenter.network.internal.FallbackAddressPortExtractor;
import java.util.Locale;

final class ClientAddressAndPortExtractor<REQUEST>
    implements FallbackAddressPortExtractor<REQUEST> {

  private final HttpServerAttributesGetter<REQUEST, ?> getter;

  ClientAddressAndPortExtractor(HttpServerAttributesGetter<REQUEST, ?> getter) {
    this.getter = getter;
  }

  /**
   * 从Header中获取forwarded或x-forwarded-for中对应的地址
   */
  @Override
  public void extract(AddressPortSink sink, REQUEST request) {
    // try Forwarded
    for (String forwarded : getter.getHttpRequestHeader(request, "forwarded")) {
      if (extractFromForwardedHeader(sink, forwarded)) {
        return;
      }
    }

    // try X-Forwarded-For
    for (String forwardedFor : getter.getHttpRequestHeader(request, "x-forwarded-for")) {
      if (extractFromForwardedForHeader(sink, forwardedFor)) {
        return;
      }
    }
  }

  private static boolean extractFromForwardedHeader(AddressPortSink sink, String forwarded) {
    int start = forwarded.toLowerCase(Locale.ROOT).indexOf("for=");
    if (start < 0) {
      return false;
    }
    start += 4; // start is now the index after for=
    if (start >= forwarded.length() - 1) { // the value after for= must not be empty
      return false;
    }
    // find the end of the `for=<address>` section
    int end = forwarded.indexOf(';', start);
    if (end < 0) {
      end = forwarded.length();
    }
    return extractClientInfo(sink, forwarded, start, end);
  }

  private static boolean extractFromForwardedForHeader(AddressPortSink sink, String forwardedFor) {
    return extractClientInfo(sink, forwardedFor, 0, forwardedFor.length());
  }

  // from https://www.rfc-editor.org/rfc/rfc7239
  //  "Note that IPv6 addresses may not be quoted in
  //   X-Forwarded-For and may not be enclosed by square brackets, but they
  //   are quoted and enclosed in square brackets in Forwarded"
  // and also (applying to Forwarded but not X-Forwarded-For)
  //  "It is important to note that an IPv6 address and any nodename with
  //   node-port specified MUST be quoted, since ':' is not an allowed
  //   character in 'token'."
  private static boolean extractClientInfo(AddressPortSink sink, String forwarded, int start, int end) {
    if (start >= end) {
      return false;
    }

    // skip quotes
    if (forwarded.charAt(start) == '"') {
      // try to find the end of the quote
      int quoteEnd = forwarded.indexOf('"', start + 1);
      if (notFound(quoteEnd, end)) {
        // malformed header value
        return false;
      }
      return extractClientInfo(sink, forwarded, start + 1, quoteEnd);
    }

    // ipv6 address enclosed in square brackets case
    if (forwarded.charAt(start) == '[') {
      int ipv6End = forwarded.indexOf(']', start + 1);
      if (notFound(ipv6End, end)) {
        // malformed header value
        return false;
      }
      sink.setAddress(forwarded.substring(start + 1, ipv6End));

      int portStart = ipv6End + 1;
      if (portStart < end && forwarded.charAt(portStart) == ':') {
        int portEnd = findPortEnd(forwarded, portStart + 1, end);
        setPort(sink, forwarded, portStart + 1, portEnd);
      }

      return true;
    }

    // try to match either ipv4 or ipv6 without brackets
    boolean inIpv4 = false;
    for (int i = start; i < end; ++i) {
      char c = forwarded.charAt(i);

      // dots only appear in ipv4
      if (c == '.') {
        inIpv4 = true;
      }

      // find the character terminating the address
      boolean isIpv4PortSeparator = inIpv4 && c == ':';
      if (c == ',' || c == ';' || c == '"' || isIpv4PortSeparator) {
        // empty string
        if (i == start) {
          return false;
        }

        sink.setAddress(forwarded.substring(start, i));

        if (isIpv4PortSeparator) {
          int portStart = i;
          int portEnd = findPortEnd(forwarded, portStart + 1, end);
          setPort(sink, forwarded, portStart + 1, portEnd);
        }

        return true;
      }
    }

    // just an address without a port
    sink.setAddress(forwarded.substring(start, end));
    return true;
  }

  private static boolean notFound(int pos, int end) {
    return pos < 0 || pos >= end;
  }

  private static int findPortEnd(String header, int start, int end) {
    int numberEnd = start;
    while (numberEnd < end && Character.isDigit(header.charAt(numberEnd))) {
      ++numberEnd;
    }
    return numberEnd;
  }

  private static void setPort(AddressPortSink sink, String header, int start, int end) {
    if (start == end) {
      return;
    }
    try {
      sink.setPort(Integer.parseInt(header.substring(start, end)));
    } catch (NumberFormatException ignored) {
      // malformed port, ignoring
    }
  }
}
