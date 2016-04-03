package me.drton.jmavlib.log.ulog;

import me.drton.jmavlib.log.BinaryLogReader;
import me.drton.jmavlib.log.FormatErrorException;

import java.io.*;
import java.util.*;

/**
 * User: ton Date: 03.06.13 Time: 14:18
 */
public class ULogReader extends BinaryLogReader {
    static final byte MESSAGE_TYPE_FORMAT = (byte) 'F';
    static final byte MESSAGE_TYPE_DATA = (byte) 'D';
    static final byte MESSAGE_TYPE_INFO = (byte) 'I';
    static final byte MESSAGE_TYPE_PARAMETER = (byte) 'P';

    private String systemName = "";
    private long dataStart = 0;
    private Map<Integer, MessageFormat> messageFormats
            = new HashMap<Integer, MessageFormat>();
    private Map<String, String> fieldsList = null;
    private long sizeUpdates = -1;
    private long sizeMicroseconds = -1;
    private long startMicroseconds = -1;
    private long utcTimeReference = -1;
    private Map<Integer, Integer> maxMultiID
            = new HashMap<Integer, Integer>();
    private Map<String, Object> version = new HashMap<String, Object>();
    private Map<String, Object> parameters = new HashMap<String, Object>();
    private List<Exception> errors = new ArrayList<Exception>();

    public ULogReader(String fileName) throws IOException, FormatErrorException {
        super(fileName);
        updateStatistics();
    }

    @Override
    public String getFormat() {
        return "ULog";
    }

    public String getSystemName() {
        return systemName;
    }

    @Override
    public long getSizeUpdates() {
        return sizeUpdates;
    }

    @Override
    public long getStartMicroseconds() {
        return startMicroseconds;
    }

    @Override
    public long getSizeMicroseconds() {
        return sizeMicroseconds;
    }

    @Override
    public long getUTCTimeReferenceMicroseconds() {
        return utcTimeReference;
    }

    @Override
    public Map<String, Object> getVersion() {
        return version;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    private void updateStatistics() throws IOException, FormatErrorException {
        position(0);
        long packetsNum = 0;
        long timeStart = -1;
        long timeEnd = -1;
        fieldsList = new HashMap<String, String>();
        while (true) {
            Object msg;
            try {
                msg = readMessage();
            } catch (EOFException e) {
                break;
            }
            packetsNum++;

            if (msg instanceof MessageFormat) {
                MessageFormat msgFormat = (MessageFormat) msg;
                messageFormats.put(msgFormat.msgID, msgFormat);

            } else if (msg instanceof MessageParameter) {
                MessageParameter msgParam = (MessageParameter) msg;
                parameters.put(msgParam.getKey(), msgParam.value);

            } else if (msg instanceof MessageInfo) {
                MessageInfo msgInfo = (MessageInfo) msg;
                if ("sys_name".equals(msgInfo.getKey())) {
                    systemName = (String) msgInfo.value;
                } else if ("ver_hw".equals(msgInfo.getKey())) {
                    version.put("HW", msgInfo.value);
                } else if ("ver_sw".equals(msgInfo.getKey())) {
                    version.put("FW", msgInfo.value);
                } else if ("time_ref_utc".equals(msgInfo.getKey())) {
                    utcTimeReference = ((Number) msgInfo.value).longValue();
                }

            } else if (msg instanceof MessageData) {
                if (dataStart == 0) {
                    dataStart = position();
                }
                MessageData msgData = (MessageData) msg;
                if (timeStart < 0) {
                    timeStart = msgData.timestamp;
                }
                timeEnd = msgData.timestamp;
                int msgID = msgData.format.msgID;
                if (maxMultiID.containsKey(msgID)) {
                    if (maxMultiID.get(msgID) < msgData.multiID) maxMultiID.put(msgID, msgData.multiID);
                } else {
                    maxMultiID.put(msgID, msgData.multiID);
                }
            }
        }
        // make a second pass filling the fieldsList now that we know how many multi-instances are in the log
        position(0);
        while (true) {
            Object msg;
            try {
                msg = readMessage();
            } catch (EOFException e) {
                break;
            }
            if (msg instanceof MessageFormat) {
                MessageFormat msgFormat = (MessageFormat) msg;
                if (msgFormat.name.charAt(0) != '_') {
                    for (int i = 0; i < msgFormat.fields.length; i++) {
                        FieldFormat fieldDescr = msgFormat.fields[i];
                        for (int mid=0; mid<=maxMultiID.get(msgFormat.msgID); mid++) {
                            if (fieldDescr.isArray()) {
                                for (int j = 0; j < fieldDescr.size; j++) {
                                    fieldsList.put(msgFormat.name + "_" + mid + "." + fieldDescr.name + "[" + j + "]", fieldDescr.type);
                                }
                            } else {
                                fieldsList.put(msgFormat.name + "_" + mid + "." + fieldDescr.name, fieldDescr.type);
                            }
                        }
                    }
                }
            }
            if (msg instanceof MessageData) {
                break;
            }
        }
        startMicroseconds = timeStart;
        sizeUpdates = packetsNum;
        sizeMicroseconds = timeEnd - timeStart;
        seek(0);
    }

    @Override
    public boolean seek(long seekTime) throws IOException, FormatErrorException {
        position(dataStart);
        if (seekTime == 0) {      // Seek to start of log
            return true;
        }
        // Seek to specified timestamp without parsing all messages
        try {
            while (true) {
                fillBuffer(3);
                long pos = position();
                int msgType = buffer.get() & 0xFF;
                int s1 = buffer.get() & 0xFF;
                int s2 = buffer.get() & 0xFF;
                int msgSize = s1 + (256 * s2);
                fillBuffer(msgSize);
                if (msgType == MESSAGE_TYPE_DATA) {
                    int msgID = buffer.get() & 0xFF;
                    buffer.get();   // MultiID
                    long timestamp = buffer.getLong();
                    if (timestamp >= seekTime) {
                        // Time found
                        position(pos);
                        return true;
                    }
                    MessageFormat msgFormat = messageFormats.get(msgID);
                    if (msgFormat == null) {
                        position(pos);
                        throw new FormatErrorException(pos, "Unknown DATA message ID: " + msgID);
                    }
                    buffer.position(buffer.position() + msgSize - 11);
                } else {
                    fillBuffer(msgSize);
                    buffer.position(buffer.position() + msgSize);
                }
            }
        } catch (EOFException e) {
            return false;
        }
    }

    private void applyMsg(Map<String, Object> update, MessageData msg) {
        applyMsgAsName(update, msg, msg.format.name + "_" + msg.multiID);
//        if (msg.isActive) {
//            applyMsgAsName(update, msg, msg.format.name);
//        }
    }

    void applyMsgAsName(Map<String, Object> update, MessageData msg, String msg_name) {
        FieldFormat[] fields = msg.format.fields;
        for (int i = 0; i < fields.length; i++) {
            FieldFormat field = fields[i];
            if (field.isArray()) {
                for (int j = 0; j < field.size; j++) {
                    update.put(msg_name + "." + field.name + "[" + j + "]", ((Object[]) msg.get(i))[j]);
                }
            } else {
                update.put(msg_name + "." + field.name, msg.get(i));
            }
        }
    }

    @Override
    public long readUpdate(Map<String, Object> update) throws IOException, FormatErrorException {
        while (true) {
            Object msg = readMessage();
            if (msg instanceof MessageData) {
                applyMsg(update, (MessageData) msg);
                return ((MessageData) msg).timestamp;
            }
        }
    }

    @Override
    public Map<String, String> getFields() {
        return fieldsList;
    }

    /**
     * Read next message from log
     *
     * @return log message
     * @throws IOException  on IO error
     * @throws EOFException on end of stream
     */
    public Object readMessage() throws IOException, FormatErrorException {
        while (true) {
            fillBuffer(3);
            long pos = position();
            int msgType = buffer.get() & 0xFF;
            int s1 = buffer.get() & 0xFF;
            int s2 = buffer.get() & 0xFF;
            int msgSize = s1 + (256 * s2);
            try {
                fillBuffer(msgSize);
            } catch (EOFException e) {
                errors.add(new FormatErrorException(pos, "Unexpected end of file"));
                throw e;
            }
            Object msg;
            if (msgType == MESSAGE_TYPE_DATA) {
                int msgID = buffer.get() & 0xFF;
                MessageFormat msgFormat = messageFormats.get(msgID);
                if (msgFormat == null) {
                    position(pos);
                    errors.add(new FormatErrorException(pos, "Unknown DATA message ID: " + msgID));
                    buffer.position(buffer.position() + msgSize - 1);
                    continue;
                } else {
                    msg = new MessageData(msgFormat, buffer);
                }
            } else if (msgType == MESSAGE_TYPE_INFO) {
                msg = new MessageInfo(buffer);
            } else if (msgType == MESSAGE_TYPE_PARAMETER) {
                msg = new MessageParameter(buffer);
            } else if (msgType == MESSAGE_TYPE_FORMAT) {
                msg = new MessageFormat(buffer);
            } else {
                buffer.position(buffer.position() + msgSize);
                errors.add(new FormatErrorException(pos, "Unknown message type: " + msgType));
                continue;
            }
            int sizeParsed = (int) (position() - pos - 2);
            if (sizeParsed != msgSize) {
                errors.add(new FormatErrorException(pos, "Message size mismatch, parsed: " + sizeParsed + ", msg size: " + msgSize));
                buffer.position(buffer.position() + msgSize - sizeParsed);
            }
            return msg;
        }
    }

    public static void main(String[] args) throws Exception {
        ULogReader reader = new ULogReader("log001.ulg");
        long tStart = System.currentTimeMillis();
        long last_t = 0;
        Map<String, PrintStream> ostream = new HashMap<String, PrintStream>();
        while (true) {
//            try {
//                Object msg = reader.readMessage();
//                System.out.println(msg);
//            } catch (EOFException e) {
//                break;
//            }
            Map<String, Object> update = new HashMap<String, Object>();
            try {
                long t = reader.readUpdate(update);
                double tsec = (double)t / 1e6;
                System.out.printf("timestamp: %8.3f", tsec);
                double dt = (double)t / 1e6 - (double)last_t / 1e6;
                last_t = t;
                System.out.printf(" dt: %8.3f\n", dt);
                String stream = update.keySet().iterator().next().split("\\.")[0];
//                Set<String> keyset = update.keySet();
//                String fieldName = keyset.iterator().next();
//                String[] parts = fieldName.split("\\.");
//                String stream = parts[0];
                if (!ostream.containsKey(stream)) {
                    PrintStream newStream = new PrintStream(stream + ".csv");
                    ostream.put(stream, newStream);
                    Iterator<String> keys = update.keySet().iterator();
                    newStream.print("timestamp");
                    while (keys.hasNext()) {
                        newStream.print(',');
                        newStream.print(keys.next());
                    }
                    newStream.println();
                }
                // append this record to output stream
                PrintStream curStream = ostream.get(stream);
                curStream.print(t);
                for (Object field: update.values()) {
                    curStream.print(',');
                    curStream.print(field.toString());
                }
                curStream.println();
//                ostream.get(stream).write(update.toString().getBytes());
            } catch (EOFException e) {
                break;
            }
        }
        long tEnd = System.currentTimeMillis();
        System.out.println(tEnd - tStart);
        reader.close();
    }

    @Override
    public List<Exception> getErrors() {
        return Collections.emptyList();
    }

    @Override
    public void clearErrors() {
    }
}