/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pekkohttp.v1_0.client;

import io.opentelemetry.instrumentation.api.instrumenter.http.HttpClientAttributesGetter;
import io.opentelemetry.javaagent.instrumentation.pekkohttp.v1_0.PekkoHttpUtil;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

class PekkoHttpClientAttributesGetter
    implements HttpClientAttributesGetter<HttpRequest, HttpResponse> {

  @Override
  public String getUrlFull(HttpRequest httpRequest) {
    return httpRequest.uri().toString();
  }

  @Override
  public String getHttpRequestMethod(HttpRequest httpRequest) {
    return httpRequest.method().value();
  }

  @Override
  public List<String> getHttpRequestHeader(HttpRequest httpRequest, String name) {
    return PekkoHttpUtil.requestHeader(httpRequest, name);
  }

  @Override
  public Integer getHttpResponseStatusCode(
      HttpRequest httpRequest, HttpResponse httpResponse, @Nullable Throwable error) {
    return httpResponse.status().intValue();
  }

  @Override
  public List<String> getHttpResponseHeader(
      HttpRequest httpRequest, HttpResponse httpResponse, String name) {
    return PekkoHttpUtil.responseHeader(httpResponse, name);
  }

  @Nullable
  @Override
  public String getNetworkProtocolName(
      HttpRequest httpRequest, @Nullable HttpResponse httpResponse) {
    return PekkoHttpUtil.protocolName(httpRequest);
  }

  @Nullable
  @Override
  public String getNetworkProtocolVersion(
      HttpRequest httpRequest, @Nullable HttpResponse httpResponse) {
    return PekkoHttpUtil.protocolVersion(httpRequest);
  }

  @Override
  public String getServerAddress(HttpRequest httpRequest) {
    return httpRequest.uri().authority().host().address();
  }

  @Override
  public Integer getServerPort(HttpRequest httpRequest) {
    return httpRequest.uri().authority().port();
  }
}
