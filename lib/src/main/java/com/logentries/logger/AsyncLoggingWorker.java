package com.logentries.logger;

import android.content.Context;
import android.util.Log;

import com.logentries.misc.Utils;
import com.logentries.net.LogentriesClient;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AsyncLoggingWorker {

    /*
     * Constants
     */
    private static final String TAG = "LogentriesAndroidLogger";

    private static final int RECONNECT_WAIT = 100; // milliseconds.
    private static final int MAX_QUEUE_POLL_TIME = 1000; // milliseconds.
    /**
     * Size of the internal event queue.
     */
    private static final int QUEUE_SIZE = 32768;
    /**
     * Limit on individual log length ie. 2^16
     */
    public static final int LOG_LENGTH_LIMIT = 65536;

    private static final int MAX_NETWORK_FAILURES_ALLOWED = 3;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    /**
     * Error message displayed when invalid API key is detected.
     */
    private static final String INVALID_TOKEN = "Given Token does not look right!";

    /**
     * Error message displayed when queue overflow occurs
     */
    private static final String QUEUE_OVERFLOW = "Logentries Buffer Queue Overflow. Message Dropped!";

    /**
     * Indicator if the socket appender has been started.
     */
    private boolean started = false;

    /**
     * Whether should send logs with or without meta data
     */
    private boolean sendRawLogMessage = false;

    /**
     * Asynchronous socket appender.
     */
    private final SocketAppender appender;

    /**
     * Message queue.
     */
    private final ArrayBlockingQueue<AndroidLogger.LogItem> queue;

    /**
     * Logs queue storage
     */
    private final LogStorage localStorage;

    private final String deviceId;

    public AsyncLoggingWorker(Context context, boolean useSsl, boolean useHttpPost, boolean printTraceId,
                              boolean printDeviceId, String deviceId, boolean printPriority, boolean useDataHub,
                              String logToken, String dataHubAddress, int dataHubPort, boolean logHostName)
            throws IOException {
        if (!checkTokenFormat(logToken)) {
            throw new IllegalArgumentException(INVALID_TOKEN);
        }

        queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        localStorage = new LogStorage(context);
        appender = new SocketAppender(useHttpPost, useSsl, useDataHub, dataHubAddress, dataHubPort, logToken, logHostName, this.sendRawLogMessage, printTraceId, printDeviceId, printPriority);
        appender.start();
        started = true;
        this.deviceId = deviceId;
    }

    public void setSendRawLogMessage(boolean sendRawLogMessage) {
        this.sendRawLogMessage = sendRawLogMessage;
    }

    public boolean getSendRawLogMessage() {
        return sendRawLogMessage;
    }

    public void addLineToQueue(int priorityLevel, String line) {

        // Check that we have all parameters set and socket appender running.
        if (!this.started) {
            appender.start();
            started = true;
        }

        if (line.length() > LOG_LENGTH_LIMIT) {
            for (String logChunk : Utils.splitStringToChunks(line, LOG_LENGTH_LIMIT)) {
                tryOfferToQueue(priorityLevel, logChunk);
            }

        } else {
            tryOfferToQueue(priorityLevel, line);
        }
    }

    /**
     * Stops the socket appender. queueFlushTimeout (if greater than 0) sets the maximum timeout in milliseconds for
     * the message queue to be flushed by the socket appender, before it is stopped. If queueFlushTimeout
     * is equal to zero - the method will wait until the queue is empty (which may be dangerous if the
     * queue is constantly populated by another thread mantime.
     *
     * @param queueFlushTimeout - max. wait time in milliseconds for the message queue to be flushed.
     */
    public void close(long queueFlushTimeout) {
        if (queueFlushTimeout < 0) {
            throw new IllegalArgumentException("queueFlushTimeout must be greater or equal to zero");
        }

        long now = System.currentTimeMillis();

        while (!queue.isEmpty()) {
            if (queueFlushTimeout != 0) {
                if (System.currentTimeMillis() - now >= queueFlushTimeout) {
                    // The timeout expired - need to stop the appender.
                    break;
                }
            }
        }
        appender.interrupt();
        started = false;
    }

    public void close() {
        close(0);
    }

    private static boolean checkTokenFormat(String token) {

        return Utils.checkValidUUID(token);
    }

    private void tryOfferToQueue(int priority, String line) throws RuntimeException {
        if (!queue.offer(new AndroidLogger.LogItem(priority, line))) {
            Log.e(TAG, "The queue is full - will try to drop the oldest message in it.");
            queue.poll();
            /*
            FIXME: This code migrated from LE Java Library; currently, there is no a simple
            way to backup the queue in case of overflow due to requirements to max.
            memory consumption and max. possible size of the local logs storage. If use
            the local storage - the we have three problems: 1) Correct joining of logs from
            the queue and from the local storage (and we need some right event to trigger this joining);
            2) Correct order of logs after joining; 3) Data consistence problem, because we're
            accessing the storage from different threads, so sync. logic will increase overall
            complexity of the code. So, for now this logic is left AS IS, due to relatively
            rareness of the case with queue overflow.
             */

            if (!queue.offer(new AndroidLogger.LogItem(priority, line))) {
                throw new RuntimeException(QUEUE_OVERFLOW);
            }
        }
    }

    private class SocketAppender extends Thread {

        // Formatting constants
        private static final String LINE_SEP_REPLACER = "\u2028";

        private LogentriesClient leClient;

        private boolean useHttpPost;
        private boolean useSsl;
        private boolean isUsingDataHub;
        private String dataHubAddr;
        private int dataHubPort;
        private String token;
        private boolean logHostName = true;
        private boolean sendRawLogMessage = false;
        private boolean printTraceId = false;
        private boolean printDeviceId = false;
        private boolean printPriority = false;

        public SocketAppender(boolean useHttpPost, boolean useSsl, boolean isUsingDataHub, String dataHubAddr, int dataHubPort,
                              String token, boolean logHostName, boolean sendRawLogMessage, boolean printTraceId, boolean printDeviceId, boolean printPriority) {
            super("Logentries Socket appender");

            // Don't block shut down
            setDaemon(true);

            this.useHttpPost = useHttpPost;
            this.useSsl = useSsl;
            this.isUsingDataHub = isUsingDataHub;
            this.dataHubAddr = dataHubAddr;
            this.dataHubPort = dataHubPort;
            this.token = token;
            this.logHostName = logHostName;
            this.printTraceId = printTraceId;
            this.printDeviceId = printDeviceId;
            this.printPriority = printPriority;
            this.sendRawLogMessage = sendRawLogMessage;
        }

        private void openConnection() throws IOException, InstantiationException {
            if (leClient == null) {
                leClient = new LogentriesClient(useHttpPost, useSsl, isUsingDataHub, dataHubAddr, dataHubPort, token);
            }

            leClient.connect();
        }

        private boolean reopenConnection(int maxReConnectAttempts) throws InterruptedException, InstantiationException {
            if (maxReConnectAttempts < 0) {
                throw new IllegalArgumentException("maxReConnectAttempts value must be greater or equal to zero");
            }

            // Close the previous connection
            closeConnection();

            for (int attempt = 0; attempt < maxReConnectAttempts; ++attempt) {
                try {

                    openConnection();
                    return true;

                } catch (IOException e) {
                    // Ignore the exception and go for the next
                    // iteration.
                }

                Thread.sleep(RECONNECT_WAIT);
            }

            return false;
        }


        private void closeConnection() {
            if (this.leClient != null) {
                this.leClient.close();
            }
        }

        private boolean tryUploadSavedLogs() {
            Queue<AndroidLogger.LogItem> logs = new ArrayDeque<>();

            try {
                logs = localStorage.getAllLogsFromStorage(false);
                for (AndroidLogger.LogItem msg = logs.peek(); msg != null; msg = logs.peek()) {
                    if (sendRawLogMessage) {
                        leClient.write(Utils.formatMessage(msg.message.replace("\n", LINE_SEP_REPLACER), msg.priority, logHostName, useHttpPost, printTraceId, printDeviceId, deviceId, printPriority));
                    } else {
                        leClient.write(msg.message.replace("\n", LINE_SEP_REPLACER));
                    }
                    logs.poll(); // Remove the message after successful sending.
                }

                // All logs have been uploaded - remove the storage file and create the blank one.
                try {
                    localStorage.reCreateStorageFile();
                } catch (IOException ex) {
                    Log.e(TAG, ex.getMessage());
                }

                return true;

            } catch (IOException ioEx) {
                Log.e(TAG, "Cannot upload logs to the server. Error: " + ioEx.getMessage());

                // Try to save back all messages, that haven't been sent yet.
                try {
                    localStorage.reCreateStorageFile();
                    for (AndroidLogger.LogItem msg : logs) {
                        localStorage.putLogToStorage(msg);
                    }
                } catch (IOException ioEx2) {
                    Log.e(TAG, "Cannot save logs to the local storage - part of messages will be " +
                            "dropped! Error: " + ioEx2.getMessage());
                }
            }

            return false;
        }

        @Override
        public void run() {
            try {

                // Open connection
                reopenConnection(MAX_RECONNECT_ATTEMPTS);

                Queue<AndroidLogger.LogItem> prevSavedLogs = localStorage.getAllLogsFromStorage(true);

                int numFailures = 0;
                boolean connectionIsBroken = false;
                AndroidLogger.LogItem logItem = null;

                // Send data in queue
                while (true) {

                    // First we need to send the logs from the local storage -
                    // they haven't been sent during the last session, so need to
                    // come first.
                    if (prevSavedLogs.isEmpty()) {

                        // Try to take data from the queue if there are no logs from
                        // the local storage left to send.
                        logItem = queue.poll(MAX_QUEUE_POLL_TIME, TimeUnit.MILLISECONDS);

                    } else {

                        // Getting messages from the previous session one by one.
                        logItem = prevSavedLogs.poll();
                    }

                    // Send data, reconnect if needed.
                    while (true) {

                        try {

                            // If we have broken connection, then try to re-connect and send
                            // all logs from the local storage. If succeeded - reset numFailures.
                            if (connectionIsBroken && reopenConnection(MAX_RECONNECT_ATTEMPTS)) {
                                if (tryUploadSavedLogs()) {
                                    connectionIsBroken = false;
                                    numFailures = 0;
                                }
                            }

                            if (logItem != null) {
                                this.leClient.write(Utils.formatMessage(logItem.message.replace("\n", LINE_SEP_REPLACER),
                                        logItem.priority, logHostName, useHttpPost, printTraceId, printDeviceId, deviceId, printPriority));
                                logItem = null;
                            }

                        } catch (IOException e) {

                            if (numFailures >= MAX_NETWORK_FAILURES_ALLOWED) {
                                connectionIsBroken = true; // Have tried to reconnect for MAX_NETWORK_FAILURES_ALLOWED
                                // times and failed, so assume, that we have no link to the
                                // server at all...
                                try {
                                    // ... and put the current message to the local storage.
                                    localStorage.putLogToStorage(logItem);
                                    logItem = null;
                                } catch (IOException ex) {
                                    Log.e(TAG, "Cannot save the log message to the local storage! Error: " +
                                            ex.getMessage());
                                }

                            } else {
                                ++numFailures;

                                // Try to re-open the lost connection.
                                reopenConnection(MAX_RECONNECT_ATTEMPTS);
                            }

                            continue;
                        }

                        break;
                    }
                }
            } catch (InterruptedException e) {
                // We got interrupted, stop.

            } catch (InstantiationException e) {
                Log.e(TAG, "Cannot instantiate LogentriesClient due to improper configuration. Error: " + e.getMessage());

                // Save all existing logs to the local storage.
                // There is nothing we can do else in this case.
                AndroidLogger.LogItem logItem = queue.poll();
                try {
                    while (logItem != null) {
                        localStorage.putLogToStorage(logItem);
                        logItem = queue.poll();
                    }
                } catch (IOException ex) {
                    Log.e(TAG, "Cannot save logs queue to the local storage - all log messages will be dropped! Error: " +
                            e.getMessage());
                }
            }

            closeConnection();
        }
    }

    public String getDeviceId() {
        return deviceId;
    }
}

