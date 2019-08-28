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

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.jetty.client.AsyncContentProvider;
import org.eclipse.jetty.client.api.ContentProvider.Typed;
import org.everit.http.client.HttpRequest;
import org.everit.http.client.async.AsyncCallback;

/**
 * Helper class to convert the body AsyncContentProvider of the {@link HttpRequest} of Everit HTTP
 * Client to Jetty Client AsyncContentProvider.
 */
public class HttpRequestBodyAsyncContentProvider implements AsyncContentProvider, Typed, Closeable {

  /**
   * Helper class to convert Everit HTTP Client AsyncContentProvider to Jetty Client
   * AsyncContentProvider. Jetty uses an Iterator inside.
   */
  private class HttpBodyAsyncContentProviderIterator implements Iterator<ByteBuffer>, Closeable {

    @Override
    public void close() throws IOException {
      HttpRequestBodyAsyncContentProvider.this.close();
    }

    @Override
    public boolean hasNext() {
      synchronized (HttpRequestBodyAsyncContentProvider.this.mutex) {
        return !HttpRequestBodyAsyncContentProvider.this.completed
            || HttpRequestBodyAsyncContentProvider.this.lastChunk != null;
      }
    }

    @Override
    public ByteBuffer next() {
      synchronized (HttpRequestBodyAsyncContentProvider.this.mutex) {
        if (!hasNext()) {
          return null;
        }

        ByteBuffer item = HttpRequestBodyAsyncContentProvider.this.lastChunk;
        HttpRequestBodyAsyncContentProvider.this.lastChunk = null;

        AsyncCallback callback = HttpRequestBodyAsyncContentProvider.this.lastAsyncCallback;
        HttpRequestBodyAsyncContentProvider.this.lastAsyncCallback = null;

        if (callback != null) {
          HttpRequestBodyAsyncContentProvider.this.executor.execute(callback::processed);
        }
        return item;
      }
    }

  }

  private final org.everit.http.client.async.AsyncContentProvider body;

  private boolean completed = false;

  private Throwable errorFromProvider = null;

  private Executor executor;

  private AtomicBoolean iteratorServed = new AtomicBoolean(false);

  private AsyncCallback lastAsyncCallback = null;

  private ByteBuffer lastChunk = null;

  private Listener listener = null;

  private Object mutex = new Object();

  private Consumer<Throwable> requestAbortAction;

  /**
   * Constructor.
   *
   * @param body
   *          The body of the {@link HttpRequest}.
   * @param requestAbortAction
   *          The action that should be executed in case the request should be aborted.
   * @param executor
   *          The provider uses the executor to open new thread to notify the Everit
   *          {@link AsyncContentProvider} that new data can be sent.
   */
  public HttpRequestBodyAsyncContentProvider(org.everit.http.client.async.AsyncContentProvider body,
      Consumer<Throwable> requestAbortAction, Executor executor) {

    this.body = body;
    this.requestAbortAction = requestAbortAction;
    this.executor = executor;

    body.onSuccess(() -> {
      Listener tmpListener;
      synchronized (this.mutex) {
        this.completed = true;
        tmpListener = this.listener;
      }

      if (tmpListener != null) {
        tmpListener.onContent();
      }
    });
    body.onError(this::handleErrorFromProvider);
    body.onContent((buffer, callback) -> {
      Listener tmpListener;
      synchronized (this.mutex) {
        this.lastAsyncCallback = callback;
        this.lastChunk = buffer;
        tmpListener = this.listener;

      }
      if (tmpListener != null) {
        tmpListener.onContent();
      }
    });

  }

  @Override
  public void close() throws IOException {
    this.body.close();

  }

  @Override
  public String getContentType() {
    if (this.body.getContentType().isPresent()) {
      return this.body.getContentType().get().toString();
    }

    return null;
  }

  @Override
  public long getLength() {
    if (this.body.getContentLength().isPresent()) {
      return this.body.getContentLength().get();
    }

    return -1;
  }

  private void handleErrorFromProvider(Throwable error) {
    boolean callAbort = false;
    synchronized (this.mutex) {
      if (this.errorFromProvider == null) {
        this.errorFromProvider = error;
        callAbort = true;
      }
    }

    if (callAbort) {
      this.requestAbortAction.accept(error);
    }
  }

  @Override
  public synchronized Iterator<ByteBuffer> iterator() {
    if (this.iteratorServed.compareAndSet(false, true)) {
      return new HttpBodyAsyncContentProviderIterator();
    }
    return Collections.emptyIterator();
  }

  @Override
  public void setListener(Listener listener) {
    ByteBuffer tmpLastChunk;
    boolean tmpCompleted;
    synchronized (this.mutex) {
      this.listener = listener;
      tmpLastChunk = this.lastChunk;
      tmpCompleted = this.completed;
    }
    if (tmpLastChunk != null || tmpCompleted) {
      listener.onContent();
    }
  }

}
