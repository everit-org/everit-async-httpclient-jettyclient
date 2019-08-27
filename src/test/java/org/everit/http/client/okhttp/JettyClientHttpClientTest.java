package org.everit.http.client.okhttp;

import org.everit.http.client.HttpClient;
import org.everit.http.client.jettyclient.JettyClientHttpClient;
import org.everit.http.client.testbase.HttpClientTest;

public class JettyClientHttpClientTest extends HttpClientTest {

  @Override
  protected HttpClient createHttpClient() {
    return new JettyClientHttpClient(new org.eclipse.jetty.client.HttpClient());
  }

}
