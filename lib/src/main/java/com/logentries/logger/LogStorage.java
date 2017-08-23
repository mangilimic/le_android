package com.logentries.logger;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogStorage {

    private static final String TAG = "LogentriesAndroidLogger";
    private static final String STORAGE_FILE_NAME = "LogentriesLogStorage.log";
    private static final long MAX_QUEUE_FILE_SIZE = 10 * 1024 * 1024; // 10 MBytes.

    private Context context;

    private File storageFilePtr = null; // We keep the ptr permanently, because frequently accessing
    // the file for retrieving it's size.

    private final Pattern pattern;

    public LogStorage(Context context) throws IOException {
        this.context = context;
        this.pattern = Pattern.compile("([0-9]+);([^;]*);(.*)");
        storageFilePtr = create();
    }

    public synchronized void putLogToStorage(AndroidLogger.LogItem logItem) throws IOException, RuntimeException {
        String tag = logItem.mTag == null ? "" : logItem.mTag;
        String message = logItem.mPriority + ";" + tag + ";" + logItem.mMessage;

        // Fix line endings for ingesting the log to the local storage.
        if (!message.endsWith("\n")) {
            message += "\n";
        }

        FileOutputStream writer = null;
        try {
            byte[] rawMessage = message.getBytes();
            long currSize = getCurrentStorageFileSize() + rawMessage.length;
            String sizeStr = Long.toString(currSize);
            Log.d(TAG, "Current size: " + sizeStr);
            if (currSize >= MAX_QUEUE_FILE_SIZE) {
                Log.d(TAG, "Log storage will be cleared because threshold of " + MAX_QUEUE_FILE_SIZE + " bytes has been reached");
                reCreateStorageFile();
            }

            writer = context.openFileOutput(STORAGE_FILE_NAME, Context.MODE_APPEND);
            writer.write(rawMessage);

        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    public synchronized Queue<AndroidLogger.LogItem> getAllLogsFromStorage(boolean needToRemoveStorageFile) {
        Queue<AndroidLogger.LogItem> logs = new ArrayDeque<>();
        FileInputStream input = null;

        try {
            input = context.openFileInput(STORAGE_FILE_NAME);
            DataInputStream inputStream = new DataInputStream(input);
            BufferedReader bufReader = new BufferedReader(new InputStreamReader(inputStream));

            String logLine = bufReader.readLine();
            while (logLine != null) {
                try {
                    Matcher m = pattern.matcher(logLine);
                    m.matches();
                    String priorityStr = m.group(1);
                    String tag = m.group(2);
                    String message = m.group(3);

                    logs.offer(new AndroidLogger.LogItem(priorityStr, tag, message));
                } catch (Exception ex) {
                    Log.e(TAG, "Unexpected exception", ex);

                    try {
                        logs.offer(new AndroidLogger.LogItem(Log.ERROR, "LogStorageError", logLine));
                    } catch (Exception ex2) {
                        Log.e(TAG, "Unexpected exception while recovering logs", ex);
                    }
                }

                logLine = bufReader.readLine();
            }

            if (needToRemoveStorageFile) {
                removeStorageFile();
            }

        } catch (IOException ex) {
            Log.e(TAG, "Cannot load logs from the local storage: " + ex.getMessage());
            // Basically, ignore the exception - if something has gone wrong - just return empty
            // logs list.
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException ex2) {
                Log.e(TAG, "Cannot close the local storage file: " + ex2.getMessage());
            }
        }

        return logs;
    }

    public synchronized void removeStorageFile() throws IOException {
        if (!storageFilePtr.delete()) {
            throw new IOException("Cannot delete " + STORAGE_FILE_NAME);
        }
    }

    public synchronized void reCreateStorageFile() throws IOException {
        Log.d(TAG, "Log storage has been re-created.");
        if (storageFilePtr == null) {
            storageFilePtr = create();
        } else {
            removeStorageFile();
        }
        storageFilePtr = create();
    }

    private File create() throws IOException {
        return new File(context.getFilesDir(), STORAGE_FILE_NAME);
    }

    private long getCurrentStorageFileSize() throws IOException {
        if (storageFilePtr == null) {
            storageFilePtr = create();
        }

        return storageFilePtr.length();
    }
}
