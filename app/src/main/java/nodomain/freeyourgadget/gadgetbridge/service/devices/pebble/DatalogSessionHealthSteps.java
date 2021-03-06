package nodomain.freeyourgadget.gadgetbridge.service.devices.pebble;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.pebble.HealthSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.impl.GBActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class DatalogSessionHealthSteps extends DatalogSession {

    private static final Logger LOG = LoggerFactory.getLogger(DatalogSessionHealthSteps.class);

    public DatalogSessionHealthSteps(byte id, UUID uuid, int tag, byte item_type, short item_size) {
        super(id, uuid, tag, item_type, item_size);
        taginfo = "(health - steps)";
    }

    @Override
    public boolean handleMessage(ByteBuffer datalogMessage, int length) {
        LOG.info("DATALOG " + taginfo + GB.hexdump(datalogMessage.array(), datalogMessage.position(), length));

        int timestamp;
        byte recordLength, recordNum;
        short recordVersion; //probably
        int beginOfPacketPosition, beginOfRecordPosition;

        int initialPosition = datalogMessage.position();
        if (0 != (length % itemSize))
            return false;//malformed message?

        int packetCount = length / itemSize;

        for (int packetIdx = 0; packetIdx < packetCount; packetIdx++) {
            beginOfPacketPosition = initialPosition + packetIdx * itemSize;
            datalogMessage.position(beginOfPacketPosition);//we may not consume all the records of a packet

            recordVersion = datalogMessage.getShort();

            if (recordVersion != 5)
                return false; //we don't know how to deal with the data TODO: this is not ideal because we will get the same message again and again since we NACK it

            timestamp = datalogMessage.getInt();
            datalogMessage.get(); //unknown, throw away
            recordLength = datalogMessage.get();
            recordNum = datalogMessage.get();

            beginOfRecordPosition = datalogMessage.position();
            StepsRecord[] stepsRecords = new StepsRecord[recordNum];

            for (int recordIdx = 0; recordIdx < recordNum; recordIdx++) {
                datalogMessage.position(beginOfRecordPosition + recordIdx * recordLength); //we may not consume all the bytes of a record
                stepsRecords[recordIdx] = new StepsRecord(timestamp, datalogMessage.get() & 0xff, datalogMessage.get() & 0xff, datalogMessage.getShort() & 0xffff);
                datalogMessage.getShort(); // skip
                timestamp += 60;
            }

            store(stepsRecords);
        }
        return true;//ACK by default
    }

    private void store(StepsRecord[] stepsRecords) {

        DBHandler dbHandler = null;
        SampleProvider sampleProvider = new HealthSampleProvider();

        ActivitySample[] samples = new ActivitySample[stepsRecords.length];
        for (int j = 0; j < stepsRecords.length; j++) {
            StepsRecord stepsRecord = stepsRecords[j];
            samples[j] = new GBActivitySample(
                    sampleProvider,
                    stepsRecord.timestamp,
                    stepsRecord.intensity,
                    stepsRecord.steps,
                    sampleProvider.toRawActivityKind(ActivityKind.TYPE_ACTIVITY));
        }

        try {
            dbHandler = GBApplication.acquireDB();
            dbHandler.addGBActivitySamples(samples);
        } catch (Exception ex) {
            LOG.debug(ex.getMessage());
        } finally {
            if (dbHandler != null) {
                dbHandler.release();
            }
        }
    }

    private class StepsRecord {
        int timestamp;
        int steps;
        int orientation;
        int intensity;

        public StepsRecord(int timestamp, int steps, int orientation, int intensity) {
            this.timestamp = timestamp;
            this.steps = steps;
            this.orientation = orientation;
            this.intensity = intensity;
        }
    }

}