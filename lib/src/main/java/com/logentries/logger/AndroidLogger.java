package com.logentries.logger;

import android.content.Context;
import com.logentries.misc.Utils;

import java.io.IOException;

public class AndroidLogger {

    private static AndroidLogger instance;

    private final AsyncLoggingWorker loggingWorker;

    private AndroidLogger(Context context, boolean useHttpPost, boolean useSsl, boolean printTraceId, boolean printDeviceId, String deviceId, boolean isUsingDataHub, String dataHubAddr, int dataHubPort,
                          String token, boolean logHostName) throws IOException {
        loggingWorker = new AsyncLoggingWorker(context, useSsl, useHttpPost, printTraceId, printDeviceId, deviceId, isUsingDataHub, token, dataHubAddr, dataHubPort, logHostName);
    }

    public static synchronized AndroidLogger createInstance(Context context, boolean useHttpPost, boolean useSsl, boolean printTraceId, boolean printDeviceId, String deviceId, boolean isUsingDataHub,
                                                            String dataHubAddr, int dataHubPort, String token, boolean logHostName)
            throws IOException {
        if (instance != null) {
            instance.loggingWorker.close();
        }

        instance = new AndroidLogger(context, useHttpPost, useSsl, printTraceId, printDeviceId, deviceId, isUsingDataHub, dataHubAddr, dataHubPort, token, logHostName);
        return instance;
    }

    public static synchronized AndroidLogger createInstance(Context context, boolean useHttpPost, boolean useSsl, boolean printTraceId, boolean printDeviceId, boolean isUsingDataHub,
                                                            String dataHubAddr, int dataHubPort, String token, boolean logHostName)
            throws IOException {
        return createInstance(context, useHttpPost, useSsl, printTraceId, printDeviceId, Utils.getDeviceId(context), isUsingDataHub, dataHubAddr, dataHubPort, token, logHostName);
    }

    public static synchronized AndroidLogger getInstance() {
        if (instance != null) {
            return instance;
        } else {
            throw new IllegalArgumentException("Logger instance is not initialized. Call createInstance() first!");
        }
    }

    /**
     * Set whether you wish to send your log message without additional meta data to Logentries.
     *
     * @param sendRawLogMessage Set to true if you wish to send raw log messages
     */
    public void setSendRawLogMessage(boolean sendRawLogMessage) {
        loggingWorker.setSendRawLogMessage(sendRawLogMessage);
    }

    /**
     * Returns whether the logger is configured to send raw log messages or not.
     *
     * @return
     */
    public boolean getSendRawLogMessage() {
        return loggingWorker.getSendRawLogMessage();
    }

    public void log(String message) {
        loggingWorker.addLineToQueue(message);
    }

    public String getDeviceId() {
        return loggingWorker.getDeviceId();
    }
}
