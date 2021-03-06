/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.shell;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public abstract class WorkerProcessPool {

  private final Semaphore available;
  private final int capacity;
  private final LinkedBlockingQueue<WorkerProcess> workerProcesses;

  public WorkerProcessPool(int maxWorkers) {
    capacity = maxWorkers;
    available = new Semaphore(capacity, true);
    workerProcesses = new LinkedBlockingQueue<>();
  }

  public WorkerProcess borrowWorkerProcess()
      throws IOException, InterruptedException {
    available.acquire();
    WorkerProcess workerProcess = workerProcesses.poll();
    return workerProcess != null ? workerProcess : startWorkerProcess();
  }

  public void returnWorkerProcess(WorkerProcess workerProcess) throws InterruptedException {
    workerProcesses.put(workerProcess);
    available.release();
  }

  public void close() {
    for (WorkerProcess process : workerProcesses) {
      process.close();
    }
  }

  public int getCapacity() {
    return capacity;
  }

  protected abstract WorkerProcess startWorkerProcess() throws IOException;
}
