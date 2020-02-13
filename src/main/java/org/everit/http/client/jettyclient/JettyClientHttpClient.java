/*
 * Copyright © 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.everit.http.client.jettyclient;

import java.net.HttpCookie;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.everit.http.client.HttpClient;
import org.everit.http.client.HttpRequest;
import org.everit.http.client.HttpResponse;
import org.everit.http.client.jettyclient.exception.JettyClientAbortRequestException;
import org.everit.http.client.jettyclient.internal.HttpRequestBodyAsyncContentProvider;
import org.everit.http.client.jettyclient.internal.JettyResponseListenerToEveritAsyncProvider;

import io.reactivex.Single;

/**
 * Jetty client based implementation of Everit {@link HttpClient}.
 */
public class JettyClientHttpClient implements HttpClient {

  /**
   * Helper functional interface that can throw an exception.
   */
  private interface ExceptionRunnable {
    void run() throws Exception;
  }

  private static void callTwoFunctionsWithErrorHandling(ExceptionRunnable action1,
      ExceptionRunnable action2) {

    Throwable error = null;
    try {
      action1.run();
    } catch (Throwable e) {
      error = e;
    } finally {
      try {
        action2.run();
      } catch (Throwable e) {
        if (error != null) {
          error.addSuppressed(e);
        } else {
          error = e;
        }

        if (error instanceof RuntimeException) {
          throw (RuntimeException) error;
        }
        if (error instanceof Error) {
          throw (Error) error;
        }
        throw new RuntimeException(error);
      }
    }
  }

  private ExecutorService executor = Executors.newCachedThreadPool();

  private final org.eclipse.jetty.client.HttpClient jettyClient;

  /**
   * Constructor.
   *
   * @param jettyClient
   *          Pre-configured Jetty client that is used for HTTP communication.
   */
  public JettyClientHttpClient(org.eclipse.jetty.client.HttpClient jettyClient) {
    if (!jettyClient.isStarted() && !jettyClient.isStarting()) {
      try {
        jettyClient.start();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    this.jettyClient = jettyClient;
  }

  @Override
  public void close() {
    JettyClientHttpClient.callTwoFunctionsWithErrorHandling(this.jettyClient::stop,
        this.executor::shutdown);
    try {
      this.jettyClient.stop();
    } catch (Exception e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else {
        throw new RuntimeException(e);
      }
    } finally {
      this.executor.shutdown();
    }
  }

  @Override
  public Single<HttpResponse> send(HttpRequest request) {
    org.eclipse.jetty.client.api.Request jettyRequest =
        this.jettyClient.newRequest(request.getUrl()).method(request.getMethod().toString());

    for (HttpCookie cookie : request.getCookies()) {
      jettyRequest.cookie(cookie);
    }

    for (Entry<String, String> headerEntry : request.getHeaders().entrySet()) {
      jettyRequest.header(headerEntry.getKey(), headerEntry.getValue());
    }

    if (request.getBody().isPresent()) {
      jettyRequest.content(
          new HttpRequestBodyAsyncContentProvider(request.getBody().get(), jettyRequest::abort,
              this.executor));
    }

    return Single.create((emitter) -> {

      JettyResponseListenerToEveritAsyncProvider listener =
          new JettyResponseListenerToEveritAsyncProvider(emitter);

      emitter.setCancellable(() -> {
        if (!listener.isHeaderProcessedOrFailed()) {
          jettyRequest.abort(new JettyClientAbortRequestException("Aborting request"));
        }
      });

      jettyRequest.send(listener);
    });
  }

}
