package com.logentries.misc;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.*;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.Buffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Utils {

    private static final String TAG = "LogentriesAndroidLogger";

    /**
     * Reg.ex. that is used to check correctness of HostName if it is defined by user
     */
    private static final Pattern HOSTNAME_REGEX = Pattern.compile("[$/\\\"&+,:;=?#|<>_* \\[\\]]");

    private static String traceID = "";
    private static String hostName = "";

    private static String deviceId = null;

    private static final String le_device_filename = "LogentriesLogStorage.dat";

    public static synchronized String getDeviceId(Context c) {
        if (deviceId == null)
            readOrGenerateDeviceId(c);
        return deviceId;
    }

    private static synchronized void readOrGenerateDeviceId(Context c) {
        // Attempt to read deviceId
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(c.openFileInput(le_device_filename)));
            deviceId = br.readLine();
            Log.d(TAG, "Device id retrieved from file: " + deviceId);

            // Device id read, terminate
            return;
        } catch (IOException ex) {
            Log.d(TAG, "Exception while reading the device id from file", ex);
        } finally {
            try {
                if (br != null)
                    br.close();
            } catch (IOException ex) {
                Log.e(TAG, "Exception while closing the device id file open in read mode", ex);
            }
        }

        // Generate and store device ID
        BufferedWriter bw = null;
        try {
            deviceId = UUID.randomUUID().toString();
            bw = new BufferedWriter(new OutputStreamWriter(c.openFileOutput(le_device_filename, Context.MODE_PRIVATE)));
            bw.write(deviceId);

            Log.d(TAG, "Generated new deviceID: " + deviceId);
        } catch (IOException ex) {
            Log.e(TAG, "Exception while writing the device id to file", ex);
        } finally {
            try {
                if (bw != null)
                    bw.close();
            } catch (IOException ex) {
                Log.e(TAG, "Exception while closing the device id file open in write mode", ex);
            }
        }
    }

    // Requires at least API level 9 (v. >= 2.3).
    static {
        try {
            traceID = computeTraceID();
        } catch (NoSuchAlgorithmException ex) {
            Log.e(TAG, "Cannot get traceID from device's properties!");
            traceID = "unknown";
        }

        try {
            hostName = getProp("net.hostname");
            if (hostName.equals("")) { // We have failed to get the real host name
                // so, use the default one.
                hostName = InetAddress.getLocalHost().getHostName();
            }
        } catch (UnknownHostException e) {
            // We cannot resolve local host name - so won't use it at all.
        }
    }

    private static String getProp(String propertyName) {

        if (propertyName == null || propertyName.isEmpty()) {
            return "";
        }

        try {
            Method getString = Build.class.getDeclaredMethod("getString", String.class);
            getString.setAccessible(true);
            return getString.invoke(null, propertyName).toString();
        } catch (Exception ex) {
            // Ignore the exception - we simply couldn't access the property;
            Log.e(TAG, ex.getMessage());
        }

        return "";
    }

    private static String computeTraceID() throws NoSuchAlgorithmException {
        String fingerprint = getProp("ro.build.fingerprint");
        String displayId = getProp("ro.build.display.id");
        String hardware = getProp("ro.hardware");
        String device = getProp("ro.product.device");
        String rilImei = getProp("ril.IMEI");

        MessageDigest hashGen = MessageDigest.getInstance("MD5");
        byte[] digest = null;
        if (fingerprint.isEmpty() & displayId.isEmpty() & hardware.isEmpty() & device.isEmpty() & rilImei.isEmpty()) {
            Log.e(TAG, "Cannot obtain any of device's properties - will use default Trace ID source.");

            Double randomTrace = Math.random() + Math.PI;
            String defaultValue = randomTrace.toString();
            randomTrace = Math.random() + Math.PI;
            defaultValue += randomTrace.toString().replace(".", "");
            // The code below fixes one strange bug, when call to a freshly installed app crashes at this
            // point, because random() produces too short sequence. Note, that this behavior does not
            // occur for the second and all further launches.
            defaultValue = defaultValue.length() >= 36 ? defaultValue.substring(2, 34) :
                    defaultValue.substring(2);

            hashGen.update(defaultValue.getBytes());
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(fingerprint).append(displayId).append(hardware).append(device).append(rilImei);
            hashGen.update(sb.toString().getBytes());
        }

        digest = hashGen.digest();
        StringBuilder conv = new StringBuilder();
        for (byte b : digest) {
            conv.append(String.format("%02x", b & 0xff).toUpperCase());
        }

        return conv.toString();
    }

    public static String getTraceID() {
        return traceID;
    }


    private static String getFormattedDeviceId(boolean toJSON, String deviceId) {
        if (toJSON) {
            return "\"DeviceId\": \"" + deviceId + "\"";
        }
        return "DeviceId=" + deviceId;
    }

    public static String getFormattedTraceID(boolean toJSON) {
        if (toJSON) {
            return "\"TraceID\": \"" + traceID + "\"";
        }
        return "TraceID=" + traceID;
    }

    public static String getHostName() {
        return hostName;
    }

    public static String getFormattedHostName(boolean toJSON) {
        if (toJSON) {
            return "\"Host\": \"" + hostName + "\"";
        }
        return "Host=" + hostName;
    }

    /**
     * Via http://stackoverflow.com/a/10174938
     */
    public static boolean isJSONValid(String message) {
        try {
            new JSONObject(message);
        } catch (JSONException ex) {
            try {
                new JSONArray(message);
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Formats given message to make it suitable for ingestion by Logentris endpoint.
     * If isUsingHttp == true, the method produces such structure:
     * {"event": {"Host": "SOMEHOST", "Timestamp": 12345, "DeviceID": "DEV_ID", "Message": "MESSAGE"}}
     * <p>
     * If isUsingHttp == false the output will be like this:
     * Host=SOMEHOST Timestamp=12345 DeviceID=DEV_ID MESSAGE
     *
     * @param message       Message to be sent to Logentries
     * @param logHostName   - if set to true - "Host"=HOSTNAME parameter is appended to the message.
     * @param isUsingHttp   will be using http
     * @param printTraceId  - if set to true will print the "TraceID"
     * @param printDeviceId if set to true will print the "DeviceID"
     * @param deviceId      The device ID
     * @return
     */
    public static String formatMessage(String message, boolean logHostName, boolean isUsingHttp, boolean printTraceId,
                                       boolean printDeviceId, String deviceId) {
        StringBuilder sb = new StringBuilder();

        if (isUsingHttp) {
            // Add 'event' structure.
            sb.append("{\"event\": {");
        }

        if (logHostName) {
            sb.append(Utils.getFormattedHostName(isUsingHttp));
            sb.append(isUsingHttp ? ", " : " ");
        }

        if (printTraceId) {
            sb.append(Utils.getFormattedTraceID(isUsingHttp)).append(" ");
            sb.append(isUsingHttp ? ", " : " ");
        }

        if (printDeviceId) {
            sb.append(getFormattedDeviceId(isUsingHttp, deviceId)).append(" ");
            sb.append(isUsingHttp ? ", " : " ");
        }

        long timestamp = System.currentTimeMillis(); // Current time in UTC in milliseconds.
        if (isUsingHttp) {
            sb.append("\"Timestamp\": ").append(Long.toString(timestamp)).append(", ");
        } else {
            sb.append("Timestamp=").append(Long.toString(timestamp)).append(" ");
        }

        // Append the event data
        if (isUsingHttp) {
            if (Utils.isJSONValid(message)) {
                sb.append("\"Message\":").append(message);
                sb.append("}}");
            } else {
                sb.append("\"Message\": \"").append(message);
                sb.append("\"}}");
            }

        } else {
            sb.append(message);
        }

        return sb.toString();
    }

    public static boolean checkValidUUID(String uuid) {
        if (uuid != null && !uuid.isEmpty()) {
            try {

                UUID u = UUID.fromString(uuid);
                return true;

            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    public static String[] splitStringToChunks(String source, int chunkLength) {
        if (chunkLength < 0) {
            throw new IllegalArgumentException("Chunk length must be greater or equal to zero!");
        }

        int srcLength = source.length();
        if (chunkLength == 0 || srcLength <= chunkLength) {
            return new String[]{source};
        }

        ArrayList<String> chunkBuffer = new ArrayList<String>();
        int splitSteps = srcLength / chunkLength + (srcLength % chunkLength > 0 ? 1 : 0);

        int lastCutPosition = 0;
        for (int i = 0; i < splitSteps; ++i) {

            if (i < splitSteps - 1) {
                // Cut out the chunk of the requested size.
                chunkBuffer.add(source.substring(lastCutPosition, lastCutPosition + chunkLength));
            } else {
                // Cut out all that left to the end of the string.
                chunkBuffer.add(source.substring(lastCutPosition));
            }

            lastCutPosition += chunkLength;
        }

        return chunkBuffer.toArray(new String[chunkBuffer.size()]);
    }
}
