package org.everit.http.client.jettyclient;

import java.net.HttpCookie;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.everit.http.client.HttpClient;
import org.everit.http.client.HttpRequest;
import org.everit.http.client.HttpResponse;
import org.everit.http.client.jettyclient.internal.HttpBodyAsyncContentProvider;
import org.everit.http.client.jettyclient.internal.JettyToHttpResponseListener;

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
          new HttpBodyAsyncContentProvider(request.getBody().get(), jettyRequest::abort,
              this.executor));
    }

    return Single.create((emitter) -> {
      JettyToHttpResponseListener listener = new JettyToHttpResponseListener(emitter);
      emitter.setCancellable(() -> {
        if (!listener.isHeaderProcessedOrFailed()) {
          jettyRequest.abort(new RuntimeException("Aborting request"));
        }
      });

      jettyRequest.send(listener);
    });
  }

}
