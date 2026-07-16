package io.vigil.checkpoint.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.spi.CheckpointManager;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MongoCheckpointManager implements CheckpointManager {

    public static final String COLLECTION = "vigil_job_checkpoints";

    private final MongoCollection<Document> checkpoints;
    private final MongoCollection<Document> locks;

    public MongoCheckpointManager(MongoDatabase database) {
        this.checkpoints = database.getCollection(COLLECTION);
        this.locks       = database.getCollection("vigil_job_locks");
        this.checkpoints.createIndex(
                Indexes.ascending("jobName", "runId", "stageName"),
                new IndexOptions().unique(true));
    }

    @Override
    public void save(CheckpointEntry entry) {
        Document lock = locks.find(Filters.eq("_id", entry.jobName())).first();
        if (lock == null || ((Number) lock.get("token")).longValue() != entry.fencingToken()) {
            throw new LockStolenException(
                    "Fencing token " + entry.fencingToken() + " is stale for job " + entry.jobName());
        }

        Bson key = key(entry.jobName(), entry.runId().toString(), entry.stageName());
        Document existing = checkpoints.find(key).first();
        if (existing == null) {
            checkpoints.insertOne(toDocument(entry));
        } else if (((Number) existing.get("fencingToken")).longValue() <= entry.fencingToken()) {
            checkpoints.replaceOne(key, toDocument(entry));
        }
    }

    @Override
    public void clearRun(String jobName, UUID runId, long fencingToken) {
        Document lock = locks.find(Filters.eq("_id", jobName)).first();
        if (lock == null || ((Number) lock.get("token")).longValue() != fencingToken) {
            return;
        }
        checkpoints.deleteMany(Filters.and(
                Filters.eq("jobName", jobName),
                Filters.eq("runId", runId.toString())));
    }

    @Override
    public Optional<CheckpointEntry> load(String jobName, UUID runId, String stageName) {
        Document doc = checkpoints.find(key(jobName, runId.toString(), stageName)).first();
        return doc == null ? Optional.empty() : Optional.of(toEntry(doc));
    }

    @Override
    public boolean isComplete(String jobName, UUID runId, String stageName) {
        Document doc = checkpoints.find(key(jobName, runId.toString(), stageName)).first();
        return doc != null && CheckpointStatus.COMPLETE.name().equals(doc.getString("status"));
    }

    @Override
    public List<String> listStageNames(String jobName) {
        List<String> stages = new ArrayList<>();
        checkpoints.distinct("stageName", Filters.eq("jobName", jobName), String.class).into(stages);
        return stages;
    }

    @Override
    public boolean hasAnyCheckpoint(String jobName, UUID runId) {
        return checkpoints.countDocuments(
                Filters.and(Filters.eq("jobName", jobName), Filters.eq("runId", runId.toString()))) > 0;
    }

    private static Bson key(String jobName, String runId, String stageName) {
        return Filters.and(
                Filters.eq("jobName", jobName),
                Filters.eq("runId", runId),
                Filters.eq("stageName", stageName));
    }

    private static Document toDocument(CheckpointEntry entry) {
        return new Document("jobName", entry.jobName())
                .append("runId", entry.runId().toString())
                .append("stageName", entry.stageName())
                .append("status", entry.status().name())
                .append("storedValue", entry.storedValue())
                .append("valueType", entry.valueType())
                .append("fencingToken", entry.fencingToken())
                .append("updatedAt", new Date());
    }

    private static CheckpointEntry toEntry(Document doc) {
        return new CheckpointEntry(
                doc.getString("jobName"),
                UUID.fromString(doc.getString("runId")),
                doc.getString("stageName"),
                CheckpointStatus.valueOf(doc.getString("status")),
                doc.getString("storedValue"),
                doc.getString("valueType"),
                ((Number) doc.get("fencingToken")).longValue());
    }
}
