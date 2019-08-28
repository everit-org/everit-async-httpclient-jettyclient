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
package org.everit.http.client.jettyclient.internal;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.AsyncContentListener;
import org.eclipse.jetty.client.api.Response.CompleteListener;
import org.eclipse.jetty.client.api.Response.FailureListener;
import org.eclipse.jetty.client.api.Response.HeadersListener;
import org.eclipse.jetty.client.api.Response.SuccessListener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.Callback;
import org.everit.http.client.HttpResponse;
import org.everit.http.client.MediaType;
import org.everit.http.client.async.AsyncCallback;
import org.everit.http.client.async.AsyncContentProvider;

import io.reactivex.SingleEmitter;

/**
 * Listener of Jetty response that passes the data to the {@link AsyncContentProvider} of the Everit
 * HTTP response body.
 */
public class JettyResponseListenerToEveritAsyncProvider
    implements HeadersListener, AsyncContentListener, CompleteListener, FailureListener,
    SuccessListener {

  /**
   * Helper class that implements the {@link AsyncCallback} of Everit HTTP Client and notifies the
   * original Jetty response about the callback events.
   */
  public static class JettyCallbackToEveritAsyncCallback implements AsyncCallback {
    Callback callback;

    JettyCallbackToEveritAsyncCallback(Callback callback) {
      this.callback = callback;
    }

    @Override
    public void failed(Throwable e) {
      this.callback.failed(e);
    }

    @Override
    public void processed() {
      this.callback.succeeded();
    }

  }

  /**
   * The Jetty connected implementation of Everit {@link AsyncContentProvider} that can be served by
   * the Everit HTTP response.
   */
  private class JettyToHttpResponseAsyncContentProvider implements AsyncContentProvider {

    Callback callbackForUnprocessedContent = null;

    private AtomicBoolean closed = new AtomicBoolean();

    boolean completed = false;

    Runnable completeListener;

    private Optional<Long> contentLength;

    private org.everit.http.client.async.AsyncContentListener contentListener;

    private Optional<MediaType> contentType;

    Throwable error = null;

    Consumer<Throwable> errorListener = null;

    ByteBuffer unprocessedContent = null;

    /**
     * Constructor.
     *
     * @param contentLength
     *          The length of the response if known.
     * @param contentType
     *          The content type of the response if known.
     */
    JettyToHttpResponseAsyncContentProvider(Optional<Long> contentLength,
        Optional<MediaType> contentType) {

      this.contentLength = contentLength;
      this.contentType = contentType;
    }

    @Override
    public void close() {
      this.closed.set(true);
      if (this.completed) {
        return;
      }

      JettyResponseListenerToEveritAsyncProvider.this.response
          .abort(new RuntimeException("Abort response"));
    }

    @Override
    public Optional<Long> getContentLength() {
      return this.contentLength;
    }

    @Override
    public Optional<MediaType> getContentType() {
      return this.contentType;
    }

    @Override
    public Optional<Throwable> getFailure() {
      synchronized (JettyResponseListenerToEveritAsyncProvider.this.mutex) {
        return Optional.ofNullable(this.error);
      }
    }

    @Override
    public boolean isClosed() {
      return this.closed.get();
    }

    @Override
    public AsyncContentProvider onContent(
        org.everit.http.client.async.AsyncContentListener listener) {

      ByteBuffer tmpUnProcessedContent;
      Callback tmpCallback;

      synchronized (JettyResponseListenerToEveritAsyncProvider.this.mutex) {
        this.contentListener = listener;
        tmpUnProcessedContent = this.unprocessedContent;
        this.unprocessedContent = null;
        tmpCallback = this.callbackForUnprocessedContent;
        this.callbackForUnprocessedContent = null;
      }
      if (tmpUnProcessedContent != null) {
        listener.onContent(tmpUnProcessedContent,
            new JettyCallbackToEveritAsyncCallback(tmpCallback));
      }
      return this;
    }

    @Override
    public AsyncContentProvider onError(Consumer<Throwable> action) {
      Throwable error = null;
      synchronized (JettyResponseListenerToEveritAsyncProvider.this.mutex) {
        this.errorListener = action;
        error = this.error;
      }
      if (error != null) {
        action.accept(error);
      }
      return this;
    }

    @Override
    public AsyncContentProvider onSuccess(Runnable action) {
      boolean callAction;
      synchronized (JettyResponseListenerToEveritAsyncProvider.this.mutex) {
        this.completeListener = action;
        callAction = this.completed && this.error == null;
      }

      if (callAction) {
        action.run();
      }
      return this;
    }

  }

  private static Map<String, String> convertHeaderFieldToHeaderMap(HttpFields headers) {
    Map<String, String> result = new HashMap<>();
    for (HttpField header : headers) {
      result.put(header.getName(), header.getValue());
    }

    return result;
  }

  private JettyToHttpResponseAsyncContentProvider contentProvider;

  private boolean errorBeforeHeaders = false;

  private Object mutex = new Object();

  private Response response;

  private final SingleEmitter<HttpResponse> singleEmitter;

  public JettyResponseListenerToEveritAsyncProvider(SingleEmitter<HttpResponse> singleEmitter) {
    this.singleEmitter = singleEmitter;
  }

  public boolean isHeaderProcessedOrFailed() {
    return this.contentProvider != null || this.errorBeforeHeaders;
  }

  @Override
  public void onComplete(Result result) {
  }

  @Override
  public void onContent(Response response, ByteBuffer content, Callback callback) {

    synchronized (this.mutex) {
      this.response = response;
      if (this.contentProvider.contentListener == null) {
        this.contentProvider.unprocessedContent = content;
        this.contentProvider.callbackForUnprocessedContent = callback;
        return;
      }
    }

    try {
      this.contentProvider.contentListener.onContent(content,
          new JettyCallbackToEveritAsyncCallback(callback));
    } catch (Throwable e) {
      callback.failed(e);
    }
  }

  @Override
  public void onFailure(Response response, Throwable failure) {
    Consumer<Throwable> errorListener = null;
    synchronized (failure) {
      this.response = response;
      if (this.contentProvider == null) {
        this.errorBeforeHeaders = true;
      } else {
        this.contentProvider.error = failure;
        errorListener = this.contentProvider.errorListener;
      }
    }

    if (this.errorBeforeHeaders) {
      this.singleEmitter.onError(failure);
    } else if (errorListener != null) {
      errorListener.accept(failure);
    }
  }

  @Override
  public void onHeaders(Response response) {

    Map<String, String> headers =
        JettyResponseListenerToEveritAsyncProvider
            .convertHeaderFieldToHeaderMap(response.getHeaders());

    Optional<Long> contentLength = resolveContentLength(headers);
    Optional<MediaType> contentType = resolveContentType(headers);

    this.response = response;
    this.contentProvider = new JettyToHttpResponseAsyncContentProvider(contentLength, contentType);

    HttpResponse httpResponse = HttpResponse.builder()
        .status(response.getStatus())
        .headers(headers)
        .body(this.contentProvider)
        .build();

    this.singleEmitter.onSuccess(httpResponse);
  }

  @Override
  public void onSuccess(Response response) {
    Runnable completeListener = null;
    synchronized (this.mutex) {
      this.contentProvider.completed = true;
      if (this.contentProvider.completeListener != null) {
        completeListener = this.contentProvider.completeListener;
      }
    }
    if (completeListener != null) {
      completeListener.run();
    }
  }

  private Optional<Long> resolveContentLength(Map<String, String> headers) {
    String contentLengthHeader = headers.get("Content-Length");
    if (contentLengthHeader == null) {
      return Optional.empty();
    }

    return Optional.of(Long.parseLong(contentLengthHeader));
  }

  private Optional<MediaType> resolveContentType(Map<String, String> headers) {
    String contentTypeHeader = headers.get("Content-Type");
    if (contentTypeHeader == null) {
      return Optional.empty();
    }
    return Optional.of(MediaType.parse(contentTypeHeader));
  }

}
