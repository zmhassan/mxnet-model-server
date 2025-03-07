/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.ml.mms.wlm;

import com.amazonaws.ml.mms.metrics.Dimension;
import com.amazonaws.ml.mms.metrics.Metric;
import com.amazonaws.ml.mms.util.ConfigManager;
import com.amazonaws.ml.mms.util.Connector;
import com.amazonaws.ml.mms.util.NettyUtils;
import com.amazonaws.ml.mms.util.codec.ModelRequestEncoder;
import com.amazonaws.ml.mms.util.codec.ModelResponseDecoder;
import com.amazonaws.ml.mms.util.messages.BaseModelRequest;
import com.amazonaws.ml.mms.util.messages.InputParameter;
import com.amazonaws.ml.mms.util.messages.ModelWorkerResponse;
import com.amazonaws.ml.mms.util.messages.RequestInput;
import com.amazonaws.ml.mms.util.messages.WorkerCommands;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkerThread implements Runnable {

    static final Logger logger = LoggerFactory.getLogger(WorkerThread.class);
    private static final org.apache.log4j.Logger loggerMmsMetrics =
            org.apache.log4j.Logger.getLogger(ConfigManager.MODEL_SERVER_METRICS_LOGGER);

    private Metric workerLoadTime;

    private static final int[] BACK_OFF = {
        0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597
    };

    static final long WORKER_TIMEOUT = ConfigManager.getInstance().isDebug() ? Long.MAX_VALUE : 2L;
    static final ModelRequestEncoder ENCODER = new ModelRequestEncoder();

    private EventLoopGroup backendEventGroup;
    private int port;
    private Model model;

    private Channel backendChannel;
    private AtomicBoolean running = new AtomicBoolean(true);

    private int backoffIdx;

    private BatchAggregator aggregator;
    private WorkerStateListener listener;
    ArrayBlockingQueue<ModelWorkerResponse> replies;
    private int gpuId;
    private long memory;
    private long startTime;
    private AtomicReference<Thread> currentThread = new AtomicReference<>();
    private String workerId;
    private String threadName;
    private BaseModelRequest req;
    private WorkerState state;

    private WorkerLifeCycle lifeCycle;
    private boolean serverThread;

    public WorkerState getState() {
        return state;
    }

    public WorkerLifeCycle getLifeCycle() {
        return lifeCycle;
    }

    public WorkerThread(
            ConfigManager configManager,
            EventLoopGroup backendEventGroup,
            int port,
            int gpuId,
            Model model,
            BatchAggregator aggregator,
            WorkerStateListener listener,
            int threadNumber,
            boolean serverThread) {
        this.workerId = String.valueOf(port); // Unique across all workers.
        this.backendEventGroup = backendEventGroup;
        this.port = port;
        this.model = model;
        this.aggregator = aggregator;
        this.gpuId = gpuId;
        this.listener = listener;
        startTime = System.currentTimeMillis();
        lifeCycle = new WorkerLifeCycle(configManager, model);
        replies = new ArrayBlockingQueue<>(1);
        this.serverThread = serverThread;
        this.threadName =
                !serverThread
                        ? "W-"
                                + model.getModelName()
                                        .substring(0, Math.min(model.getModelName().length(), 25))
                                + '-'
                                + threadNumber
                        : "BackendServer-" + model.getModelName();
        workerLoadTime =
                new Metric(
                        getWorkerName(),
                        String.valueOf(System.currentTimeMillis()),
                        "ms",
                        ConfigManager.getInstance().getHostName(),
                        new Dimension("Level", "Host"));
    }

    private void runWorker()
            throws WorkerInitializationException, InterruptedException, FileNotFoundException {
        int responseTimeout = model.getResponseTimeout();
        while (isRunning()) {
            req = aggregator.getRequest(backendChannel.id().asLongText(), state);
            backendChannel.writeAndFlush(req).sync();
            long begin = System.currentTimeMillis();
            // TODO: Change this to configurable param
            ModelWorkerResponse reply = replies.poll(responseTimeout, TimeUnit.MINUTES);
            long duration = System.currentTimeMillis() - begin;
            logger.info("Backend response time: {}", duration);

            if (reply != null) {
                aggregator.sendResponse(reply);
            } else {
                int val = model.incrFailedInfReqs();
                logger.error("Number or consecutive unsuccessful inference {}", val);
                throw new WorkerInitializationException(
                        "Backend worker did not respond in given time");
            }
            switch (req.getCommand()) {
                case PREDICT:
                    model.resetFailedInfReqs();
                    break;
                case LOAD:
                    String message = reply.getMessage();
                    String tmpdir = System.getProperty("java.io.tmpdir");
                    RandomAccessFile out =
                            new RandomAccessFile(
                                    tmpdir + '/' + backendChannel.id().asLongText() + "-stdout",
                                    "rw");
                    RandomAccessFile err =
                            new RandomAccessFile(
                                    tmpdir + '/' + backendChannel.id().asLongText() + "-stderr",
                                    "rw");
                    if (reply.getCode() == 200) {
                        setState(WorkerState.WORKER_MODEL_LOADED, HttpResponseStatus.OK);
                        lifeCycle.setPid(
                                Integer.parseInt(
                                        message.substring(
                                                message.indexOf("[PID]:") + 6, message.length())));
                        lifeCycle.attachIOStreams(
                                threadName,
                                Channels.newInputStream(out.getChannel()),
                                Channels.newInputStream(err.getChannel()));
                        backoffIdx = 0;
                    } else {
                        setState(
                                WorkerState.WORKER_ERROR,
                                HttpResponseStatus.valueOf(reply.getCode()));
                    }
                    break;
                case UNLOAD:
                case STATS:
                default:
                    break;
            }
            req = null;
        }
    }

    @Override
    public void run() {
        Process process = null;
        Thread thread = Thread.currentThread();
        thread.setName(getWorkerName());
        currentThread.set(thread);
        HttpResponseStatus status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
        try {
            if (!serverThread) {
                connect();
                runWorker();
            } else {
                // TODO: Move this logic to a seperate ServerThread class
                // This is server thread and shouldn't come out as long as process exists in CPU.
                model.setPort(port);
                lifeCycle.startBackendServer(port);
                setState(WorkerState.WORKER_MODEL_LOADED, HttpResponseStatus.OK);
                process = lifeCycle.getProcess();
                process.waitFor();
            }
        } catch (InterruptedException e) {
            if (state == WorkerState.WORKER_SCALED_DOWN) {
                logger.debug("Shutting down the thread .. Scaling down.");
            } else {
                logger.debug(
                        "Backend worker monitoring thread interrupted or backend worker process died.",
                        e);
            }
        } catch (WorkerInitializationException e) {
            logger.error("Backend worker error", e);
        } catch (OutOfMemoryError oom) {
            logger.error("Out of memory error when creating workers", oom);
            status = HttpResponseStatus.INSUFFICIENT_STORAGE;
        } catch (Throwable t) {
            logger.warn("Backend worker thread exception.", t);
        } finally {
            // WorkerThread is running in thread pool, the thread will be assigned to next
            // Runnable once this worker is finished. If currentThread keep holding the reference
            // of the thread, currentThread.interrupt() might kill next worker.
            currentThread.set(null);
            Integer exitValue = lifeCycle.getExitValue();

            if (exitValue != null && exitValue == 137) {
                status = HttpResponseStatus.INSUFFICIENT_STORAGE;
            }

            if (!serverThread && req != null) {
                aggregator.sendError(req, "Worker died.", status);
            } else if (serverThread) {
                model.setPort(-1);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        logger.warn(
                                "WorkerThread interrupted during waitFor, possible asynch resource cleanup.");
                    }
                }
            }
            setState(WorkerState.WORKER_STOPPED, status);
            lifeCycle.exit();
            retry();
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public long getMemory() {
        return memory;
    }

    public void setMemory(long memory) {
        this.memory = memory;
    }

    private void connect()
            throws WorkerInitializationException, InterruptedException, FileNotFoundException {
        if (!this.serverThread && (model.getPort() == -1)) {
            throw new WorkerInitializationException("Backend server is not runniing");
        }

        String modelName = model.getModelName();
        setState(WorkerState.WORKER_STARTED, HttpResponseStatus.OK);
        final CountDownLatch latch = new CountDownLatch(1);

        final int responseBufferSize = ConfigManager.getInstance().getMaxResponseSize();
        try {
            Connector connector = new Connector(port);
            Bootstrap b = new Bootstrap();
            b.group(backendEventGroup)
                    .channel(connector.getClientChannel())
                    .handler(
                            new ChannelInitializer<Channel>() {
                                @Override
                                public void initChannel(Channel ch) {
                                    ChannelPipeline p = ch.pipeline();
                                    p.addLast(ENCODER);
                                    p.addLast(new ModelResponseDecoder(responseBufferSize));
                                    p.addLast(new WorkerHandler());
                                }
                            });

            SocketAddress address = connector.getSocketAddress();
            logger.info("Connecting to: {}", address);
            backendChannel = b.connect(address).sync().channel();
            backendChannel
                    .closeFuture()
                    .addListener(
                            (ChannelFutureListener)
                                    future -> {
                                        latch.countDown();
                                        logger.info(
                                                "{} Worker disconnected. {}", getWorkerId(), state);
                                        Thread thread = currentThread.getAndSet(null);
                                        if (thread != null) {
                                            thread.interrupt();
                                        }
                                    });

            backendChannel
                    .newSucceededFuture()
                    .addListener(
                            (ChannelFutureListener)
                                    future -> {
                                        // TODO:
                                        // use gpu, batch size in load model command
                                        RequestInput input =
                                                new RequestInput(UUID.randomUUID().toString());
                                        if (gpuId >= 0) {
                                            input.addParameter(
                                                    new InputParameter(
                                                            "gpu", String.valueOf(gpuId)));
                                        }

                                        Job job =
                                                new Job(
                                                        null,
                                                        modelName,
                                                        WorkerCommands.LOAD,
                                                        input);
                                        model.addJob(backendChannel.id().asLongText(), job);
                                        latch.countDown();
                                    });

            if (!latch.await(WORKER_TIMEOUT, TimeUnit.MINUTES)) {
                throw new WorkerInitializationException(
                        "Worker failed to initialize within " + WORKER_TIMEOUT + " mins");
            }
            workerId = workerId + "-" + backendChannel.id();
            running.set(true);
        } catch (Throwable t) {
            // https://github.com/netty/netty/issues/2597
            if (t instanceof IOException) {
                throw new WorkerInitializationException("Failed to connect to worker.", t);
            }
            throw t;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getGpuId() {
        return gpuId;
    }

    public long getStartTime() {
        return startTime;
    }

    public int getPid() {
        return lifeCycle.getPid();
    }

    public void shutdown() {
        running.set(false);
        setState(WorkerState.WORKER_SCALED_DOWN, HttpResponseStatus.OK);
        if (backendChannel != null) {
            model.removeJobQueue(backendChannel.id().asLongText());
            backendChannel.close();
        }
        Thread thread = currentThread.getAndSet(null);
        if (thread != null) {
            thread.interrupt();
            aggregator.sendError(
                    null, "Worker scaled down.", HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public boolean isServerThread() {
        return serverThread;
    }

    private final String getWorkerName() {
        String modelName = model.getModelName();
        if (modelName.length() > 25) {
            modelName = modelName.substring(0, 25);
        }
        return "W-" + port + '-' + modelName;
    }

    void setState(WorkerState newState, HttpResponseStatus status) {
        listener.notifyChangeState(model.getModelName(), newState, status);
        logger.debug("{} State change {} -> {}", getWorkerName(), state, newState);
        long timeTaken = System.currentTimeMillis() - startTime;
        if (state != WorkerState.WORKER_SCALED_DOWN) {
            // Don't update the state if it was terminated on purpose.. Scaling in..
            this.state = newState;
        }
        if (state == WorkerState.WORKER_MODEL_LOADED) {
            workerLoadTime.setValue(String.valueOf(timeTaken));
            workerLoadTime.setTimestamp(
                    String.valueOf(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())));
            loggerMmsMetrics.info(workerLoadTime);
        }
    }

    void retry() {
        if (state == WorkerState.WORKER_SCALED_DOWN) {
            logger.debug("Worker terminated due to scale-down call.");
            return;
        }

        ModelManager manager = ModelManager.getInstance();

        if (backoffIdx < BACK_OFF.length - 1) {
            ++backoffIdx;
        }

        manager.getScheduler()
                .schedule(() -> manager.submitTask(this), BACK_OFF[backoffIdx], TimeUnit.SECONDS);
        logger.info("Retry worker: {} in {} seconds.", workerId, BACK_OFF[backoffIdx]);
    }

    @ChannelHandler.Sharable
    private class WorkerHandler extends SimpleChannelInboundHandler<ModelWorkerResponse> {

        @Override
        public void channelRead0(ChannelHandlerContext ctx, ModelWorkerResponse msg) {
            if (!replies.offer(msg)) {
                throw new IllegalStateException("Reply queue is full.");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Unknown exception", cause);
            if (cause instanceof OutOfMemoryError) {
                NettyUtils.sendError(ctx, HttpResponseStatus.INSUFFICIENT_STORAGE, cause);
            }
            ctx.close();
        }
    }
}
