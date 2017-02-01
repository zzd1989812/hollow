package org.redlich.hollow.producer;

import java.io.File;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedOutputStream;
import java.util.List;

import org.redlich.hollow.producer.datamodel.Movie;
import org.redlich.hollow.producer.datamodel.SourceDataRetriever;
import org.redlich.hollow.producer.infrastructure.FilesystemAnnouncer;
import org.redlich.hollow.producer.infrastructure.FilesystemPublisher;
import org.redlich.hollow.producer.util.VersionMinter;
import org.redlich.hollow.consumer.infrastructure.FilesystemAnnouncementWatcher;
import org.redlich.hollow.consumer.infrastructure.FilesystemBlobRetriever;

import com.netflix.hollow.api.client.HollowClient;
import com.netflix.hollow.core.read.engine.HollowReadStateEngine;
import com.netflix.hollow.core.write.HollowWriteStateEngine;
import com.netflix.hollow.core.write.HollowBlobWriter;
import com.netflix.hollow.core.write.objectmapper.HollowObjectMapper;

public class Producer {
    public static final String SCRATCH_DIR = System.getProperty("java.io.tmpdir");
    private static final long MIN_TIME_BETWEEN_CYCLES = 10000;

    private final Publisher publisher;
    private final Announcer announcer;
    private final SourceDataRetriever dataRetriever;

    private final HollowWriteStateEngine writeEngine;
    private final HollowObjectMapper objectMapper;

    private long currentCycleId;
    private long previousCycleId;

    public Producer(Publisher publisher,Announcer announcer) {
        this.publisher = publisher;
        this.announcer = announcer;

        dataRetriever = new SourceDataRetriever();

        writeEngine = new HollowWriteStateEngine();
        objectMapper = new HollowObjectMapper(writeEngine);
        objectMapper.initializeTypeState(Movie.class);

        previousCycleId = Long.MIN_VALUE;
        currentCycleId = Long.MIN_VALUE;
        }

    public static void main(String args[]) throws InterruptedException {
        File publishDir = new File(SCRATCH_DIR, "publish-dir");
        publishDir.mkdir();

        System.out.println("***** PRODUCER publishing to " + publishDir.getAbsolutePath());

        Publisher publisher = new FilesystemPublisher(publishDir);
        Announcer announcer = new FilesystemAnnouncer(publishDir);
        Producer producer = new Producer(publisher, announcer);

        restoreIfAvailable(producer, publishDir);
        producer.cycleForever();
        }

    public static void restoreIfAvailable(Producer producer, File publishDir) {
        System.out.println("ATTEMPTING TO RESTORE PRIOR STATE...");
        try {
            long latestVersion = new FilesystemAnnouncementWatcher(publishDir).readLatestVersion();

            HollowClient client = new HollowClient(new FilesystemBlobRetriever(publishDir));
            client.triggerRefreshTo(latestVersion);

            producer.restoreFrom(client.getStateEngine(),client.getCurrentVersionId());
            }
        catch(Exception exception) {
            System.out.println("RESTORE NOT AVAILABLE");
            }
        }

    public void restoreFrom(HollowReadStateEngine previousAnnouncedState, long previousAnnouncedVersion) {
        writeEngine.restoreFrom(previousAnnouncedState);
        currentCycleId = previousAnnouncedVersion;
        }

    public void cycleForever() {
        long lastCycleTime = Long.MIN_VALUE;
        while(true) {
            waitForMinCycleTime(lastCycleTime);
            lastCycleTime = System.currentTimeMillis();
            runACycle();
            }
        }

    private void waitForMinCycleTime(long lastCycleTime) {
        long targetNextCycleTime = lastCycleTime + MIN_TIME_BETWEEN_CYCLES;

        while(System.currentTimeMillis() < targetNextCycleTime) {
            try {
                Thread.sleep(targetNextCycleTime - System.currentTimeMillis());
                }
            catch(InterruptedException ignore) { }
            }
        }

    /**
     * Each cycle produces a single state.
     */
    public void runACycle() {
        previousCycleId = currentCycleId;
        currentCycleId = VersionMinter.mintANewVersion();
        System.out.println("Beginning cycle " + currentCycleId);

        try {
            writeEngine.prepareForNextCycle();

            List<Movie> movies = dataRetriever.retrieveAllMovies();

            for(Movie movie : movies) {
                /// this is thread-safe, and could be done in parallel
                objectMapper.addObject(movie);
                }

            publishAndAnnounce();
            }
        catch(Throwable throwable) {
            throwable.printStackTrace();
            currentCycleId = previousCycleId;
            writeEngine.resetToLastPrepareForNextCycle();
            }

        }

    private void publishAndAnnounce() throws IOException {
        HollowBlobWriter writer = new HollowBlobWriter(writeEngine);

        File snapshotFile = new File(SCRATCH_DIR, "snapshot-" + currentCycleId);
        File deltaFile = null;
        File reverseDeltaFile = null;

        try(OutputStream os = new BufferedOutputStream(new FileOutputStream(snapshotFile))) {
            writer.writeSnapshot(os);
            }

        if(previousCycleId != Long.MIN_VALUE) {
            deltaFile = new File(SCRATCH_DIR, "delta-" + previousCycleId + "-" + currentCycleId);
            reverseDeltaFile = new File(SCRATCH_DIR, "reversedelta-" + currentCycleId + "-" + previousCycleId);

            try(OutputStream os = new BufferedOutputStream(new FileOutputStream(deltaFile))) {
                writer.writeDelta(os);
                }

            try(OutputStream os = new BufferedOutputStream(new FileOutputStream(reverseDeltaFile))) {
                writer.writeReverseDelta(os);
                }

            publisher.publishDelta(deltaFile, previousCycleId, currentCycleId);
            publisher.publishReverseDelta(reverseDeltaFile, previousCycleId, currentCycleId);

            deltaFile.delete();
            reverseDeltaFile.delete();
            }

        try {
            /// it's ok to fail to publish a snapshot, as long as you don't miss too many in a row.
            /// you can add a timeout or even do this in a separate thread.
            publisher.publishSnapshot(snapshotFile, currentCycleId);
            snapshotFile.delete();
            }
        catch(Throwable ignore) { }

        announcer.announce(currentCycleId);
        }
    }
