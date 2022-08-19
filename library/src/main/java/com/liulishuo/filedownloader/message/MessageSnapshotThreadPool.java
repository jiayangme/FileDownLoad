/*
 * Copyright (c) 2015 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.liulishuo.filedownloader.message;

import android.app.ActivityManager;
import android.app.Application;
import android.os.Build;
import android.os.Looper;
import android.os.Process;

import com.liulishuo.filedownloader.util.FileDownloadExecutors;
import com.liulishuo.filedownloader.util.FileDownloadLog;
import com.liulishuo.filedownloader.util.FileDownloadUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * For guaranteeing only one-thread-pool for one-task, the task will be identified by its ID, make
 * sure the same task will be invoked in FIFO.
 */
public class MessageSnapshotThreadPool {

    private final List<FlowSingleExecutor> executorList;

    private final MessageSnapshotFlow.MessageReceiver receiver;

    MessageSnapshotThreadPool(@SuppressWarnings("SameParameterValue") final int poolCount,
                              MessageSnapshotFlow.MessageReceiver receiver) {
        this.receiver = receiver;
        executorList = new ArrayList<>();
        for (int i = 0; i < poolCount; i++) {
            executorList.add(new FlowSingleExecutor(i));
        }
    }

//    必须要加锁控制并发
    public void execute(final MessageSnapshot snapshot) {
        FlowSingleExecutor targetPool = null;
        try {
            synchronized (executorList) {
                final int id = snapshot.getId();
                // Case 1. already had same task in executorList, so execute this event after
                // before-one.
                for (FlowSingleExecutor executor : executorList) {
                    if (executor.enQueueTaskIdList.contains(id)) {
                        targetPool = executor;
                        break;
                    }
                }

                // Case 2. no same task in executorList, so execute in executor which has the count
                // of active task is least.
                if (targetPool == null) {
                    int leastTaskCount = 0;
                    for (FlowSingleExecutor executor : executorList) {
                        if (executor.enQueueTaskIdList.size() <= 0) {
                            targetPool = executor;
                            break;
                        }

                        if (leastTaskCount == 0
                                || executor.enQueueTaskIdList.size() < leastTaskCount) {
                            leastTaskCount = executor.enQueueTaskIdList.size();
                            targetPool = executor;
                        }
                    }
                }

                //noinspection ConstantConditions
                targetPool.enqueue(id);
            }
        } finally {
            //noinspection ConstantConditions
            targetPool.execute(snapshot);
        }
    }

    public class FlowSingleExecutor {
        private final List<Integer> enQueueTaskIdList = new ArrayList<>();
        private final Executor mExecutor;

        public FlowSingleExecutor(int index) {
//            一个线程的线程池
            mExecutor = FileDownloadExecutors.newDefaultThreadPool(1, "Flow-" + index);
        }

        public void enqueue(final int id) {
            enQueueTaskIdList.add(id);
        }

//        执行在子线程的子线程中
//        同时开5个线程池处理消息的流动，每个线程池处理相同id的消息，
//        一个线程池无法应付到来的这么多子线程带来消息
//        @android.support.annotation.RequiresApi(api = Build.VERSION_CODES.P)
        public void execute(final MessageSnapshot snapshot) {
//            FileDownloadLog.e(this,"execute process = %s",
//                    Application.getProcessName());
//            FileDownloadLog.e(this,"execute = %b",
//                    Thread.currentThread().getId() == Looper.getMainLooper().getThread().getId());
//            FileDownloadLog.e(this,"execute main thread = %s",
//                    Looper.getMainLooper().getThread().getName());
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
//                    FileDownloadLog.e(this,"run = %s",Thread.currentThread().getName());
//                    FileDownloadLog.e(this,"run2 = %s",receiver.toString());
                    receiver.receive(snapshot);
                    enQueueTaskIdList.remove((Integer) snapshot.getId());
                }
            });
        }

    }

}
