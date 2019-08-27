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
import org.everit.http.client.async.AsyncCallback;

public class HttpBodyAsyncContentProvider implements AsyncContentProvider, Typed, Closeable {

  private class HttpBodyAsyncContentProviderIterator implements Iterator<ByteBuffer>, Closeable {

    @Override
    public void close() throws IOException {
      HttpBodyAsyncContentProvider.this.close();
    }

    @Override
    public boolean hasNext() {
      synchronized (HttpBodyAsyncContentProvider.this.mutex) {
        return !HttpBodyAsyncContentProvider.this.completed
            || HttpBodyAsyncContentProvider.this.lastChunk != null;
      }
    }

    @Override
    public ByteBuffer next() {
      synchronized (HttpBodyAsyncContentProvider.this.mutex) {
        if (!hasNext()) {
          return null;
        }

        ByteBuffer item = HttpBodyAsyncContentProvider.this.lastChunk;
        HttpBodyAsyncContentProvider.this.lastChunk = null;

        AsyncCallback callback = HttpBodyAsyncContentProvider.this.lastAsyncCallback;
        HttpBodyAsyncContentProvider.this.lastAsyncCallback = null;

        if (callback != null) {
          HttpBodyAsyncContentProvider.this.executor.execute(callback::processed);
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

  public HttpBodyAsyncContentProvider(org.everit.http.client.async.AsyncContentProvider body,
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
