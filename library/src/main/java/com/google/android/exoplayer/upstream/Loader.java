/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.upstream;

import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.Util;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Manages the background loading of {@link Loadable}s.
 */
public final class Loader {

  /**
   * Thrown when an unexpected exception is encountered during loading.
   */
  public static final class UnexpectedLoaderException extends IOException {

    public UnexpectedLoaderException(Exception cause) {
      super("Unexpected " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
    }

  }

  /**
   * Interface definition of an object that can be loaded using a {@link Loader}.
   */
  public interface Loadable {

    /**
     * Cancels the load.
     */
    void cancelLoad();

    /**
     * Whether the load has been canceled.
     *
     * @return True if the load has been canceled. False otherwise.
     */
    boolean isLoadCanceled();

    /**
     * Performs the load, returning on completion or cancelation.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    void load() throws IOException, InterruptedException;

  }

  /**
   * Interface definition for a callback to be notified of {@link Loader} events.
   */
  public interface Callback {

    /**
     * Invoked when loading has been canceled.
     *
     * @param loadable The loadable whose load has been canceled.
     */
    void onLoadCanceled(Loadable loadable);

    /**
     * Invoked when the data source has been fully loaded.
     *
     * @param loadable The loadable whose load has completed.
     */
    void onLoadCompleted(Loadable loadable);

    /**
     * Invoked when the data source is stopped due to an error.
     *
     * @param loadable The loadable whose load has failed.
     */
    void onLoadError(Loadable loadable, IOException exception);

  }

  private static final int MSG_END_OF_SOURCE = 0;
  private static final int MSG_ERROR = 1;

  private final ExecutorService downloadExecutorService;

  private LoadTask currentTask;
  private boolean loading;

  /**
   * @param threadName A name for the loader's thread.
   */
  public Loader(String threadName) {
    this.downloadExecutorService = Util.newSingleThreadExecutor(threadName);
  }

  /**
   * Invokes {@link #startLoading(Looper, Loadable, Callback)}, using the {@link Looper}
   * associated with the calling thread.
   *
   * @param loadable The {@link Loadable} to load.
   * @param callback A callback to invoke when the load ends.
   * @throws IllegalStateException If the calling thread does not have an associated {@link Looper}.
   */
  public void startLoading(Loadable loadable, Callback callback) {
    Looper myLooper = Looper.myLooper();
    Assertions.checkState(myLooper != null);
    startLoading(myLooper, loadable, callback);
  }

  /**
   * Invokes {@link #startLoading(Looper, Loadable, Callback)}, using the {@link Looper}
   * associated with the calling thread. Loading is delayed by {@code delayMs}.
   *
   * @param loadable The {@link Loadable} to load.
   * @param callback A callback to invoke when the load ends.
   * @param delayMs Number of milliseconds to wait before calling {@link Loadable#load()}.
   * @throws IllegalStateException If the calling thread does not have an associated {@link Looper}.
   */
  public void startLoading(Loadable loadable, Callback callback, int delayMs) {
    Looper myLooper = Looper.myLooper();
    Assertions.checkState(myLooper != null);
    startLoading(myLooper, loadable, callback, delayMs);
  }

  /**
   * Start loading a {@link Loadable}.
   * <p>
   * A {@link Loader} instance can only load one {@link Loadable} at a time, and so this method
   * must not be called when another load is in progress.
   *
   * @param looper The looper of the thread on which the callback should be invoked.
   * @param loadable The {@link Loadable} to load.
   * @param callback A callback to invoke when the load ends.
   */
  public void startLoading(Looper looper, Loadable loadable, Callback callback) {
    startLoading(looper, loadable, callback, 0);
  }

  /**
   * Start loading a {@link Loadable} after {@code delayMs} has elapsed.
   * <p>
   * A {@link Loader} instance can only load one {@link Loadable} at a time, and so this method
   * must not be called when another load is in progress.
   *
   * @param looper The looper of the thread on which the callback should be invoked.
   * @param loadable The {@link Loadable} to load.
   * @param callback A callback to invoke when the load ends.
   * @param delayMs Number of milliseconds to wait before calling {@link Loadable#load()}.
   */
  public void startLoading(Looper looper, Loadable loadable, Callback callback, int delayMs) {
    Assertions.checkState(!loading);
    loading = true;
    currentTask = new LoadTask(looper, loadable, callback, delayMs);
    downloadExecutorService.submit(currentTask);
  }

  /**
   * Whether the {@link Loader} is currently loading a {@link Loadable}.
   *
   * @return Whether the {@link Loader} is currently loading a {@link Loadable}.
   */
  public boolean isLoading() {
    return loading;
  }

  /**
   * Cancels the current load.
   * <p>
   * This method should only be called when a load is in progress.
   */
  public void cancelLoading() {
    Assertions.checkState(loading);
    currentTask.quit();
  }

  /**
   * Releases the {@link Loader}.
   * <p>
   * This method should be called when the {@link Loader} is no longer required.
   */
  public void release() {
    if (loading) {
      cancelLoading();
    }
    downloadExecutorService.shutdown();
  }

  @SuppressLint("HandlerLeak")
  private final class LoadTask extends Handler implements Runnable {

    private static final String TAG = "LoadTask";

    private final Loadable loadable;
    private final Loader.Callback callback;
    private final int delayMs;

    private volatile Thread executorThread;

    public LoadTask(Looper looper, Loadable loadable, Loader.Callback callback, int delayMs) {
      super(looper);
      this.loadable = loadable;
      this.callback = callback;
      this.delayMs = delayMs;
    }

    public void quit() {
      loadable.cancelLoad();
      if (executorThread != null) {
        executorThread.interrupt();
      }
    }

    @Override
    public void run() {
      try {
        executorThread = Thread.currentThread();
        if (delayMs > 0) {
          Thread.sleep(delayMs);
        }
        if (!loadable.isLoadCanceled()) {
          loadable.load();
        }
        sendEmptyMessage(MSG_END_OF_SOURCE);
      } catch (IOException e) {
        obtainMessage(MSG_ERROR, e).sendToTarget();
      } catch (InterruptedException e) {
        // The load was canceled.
        Assertions.checkState(loadable.isLoadCanceled());
        sendEmptyMessage(MSG_END_OF_SOURCE);
      } catch (Exception e) {
        // This should never happen, but handle it anyway.
        Log.e(TAG, "Unexpected error loading stream", e);
        obtainMessage(MSG_ERROR, new UnexpectedLoaderException(e)).sendToTarget();
      }
    }

    @Override
    public void handleMessage(Message msg) {
      onFinished();
      if (loadable.isLoadCanceled()) {
        callback.onLoadCanceled(loadable);
        return;
      }
      switch (msg.what) {
        case MSG_END_OF_SOURCE:
          callback.onLoadCompleted(loadable);
          break;
        case MSG_ERROR:
          callback.onLoadError(loadable, (IOException) msg.obj);
          break;
      }
    }

    private void onFinished() {
      loading = false;
      currentTask = null;
    }

  }

}
