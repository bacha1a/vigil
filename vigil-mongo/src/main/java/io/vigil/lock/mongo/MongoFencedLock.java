package io.vigil.lock.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.FencedLock;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class MongoFencedLock implements FencedLock {

    public static final String COLLECTION = "vigil_job_locks";

    private final MongoCollection<Document> locks;

    public MongoFencedLock(MongoDatabase database) {
        this.locks = database.getCollection(COLLECTION);
    }

    @Override
    public void ensureSeedRow(String jobName) {
        Date now = new Date();
        locks.updateOne(
                new Document("_id", jobName),
                Updates.combine(
                        Updates.setOnInsert("holder", "init"),
                        Updates.setOnInsert("token", 0L),
                        Updates.setOnInsert("status", "FREE"),
                        Updates.setOnInsert("runId", UUID.randomUUID().toString()),
                        Updates.setOnInsert("acquiredAt", now),
                        Updates.setOnInsert("expiresAt", now)),
                new UpdateOptions().upsert(true));
    }

    @Override
    public Optional<LockAcquisition> tryAcquire(String jobName, String podId, Duration ttl) {
        String candidateRunId = UUID.randomUUID().toString();

        Bson filter = new Document("_id", jobName)
                .append("status", new Document("$ne", "PAUSED"))
                .append("$expr", new Document("$or", List.of(
                        new Document("$eq", List.of("$status", "FREE")),
                        new Document("$eq", List.of("$status", "ORPHANED")),
                        new Document("$lt", List.of("$expiresAt", "$$NOW")))));

        List<Bson> update = List.of(new Document("$set", new Document()
                .append("holder", podId)
                .append("token", new Document("$add", List.of("$token", 1L)))
                .append("acquiredAt", "$$NOW")
                .append("expiresAt", new Document("$add", List.of("$$NOW", ttl.toMillis())))
                .append("status", "HELD")
                .append("runId", new Document("$cond", List.of(
                        new Document("$and", List.of(
                                new Document("$eq", List.of("$status", "FREE")),
                                new Document("$gte", List.of("$expiresAt", "$$NOW")))),
                        candidateRunId,
                        "$runId")))));

        Document result = locks.findOneAndUpdate(filter, update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

        if (result == null) {
            return Optional.empty();
        }
        long token = ((Number) result.get("token")).longValue();
        UUID runId = UUID.fromString(result.getString("runId"));
        return Optional.of(new LockAcquisition(token, runId));
    }

    @Override
    public boolean tryRenew(String jobName, long fencingToken, Duration ttl) {
        Bson filter = new Document("_id", jobName)
                .append("token", fencingToken)
                .append("status", "HELD");
        List<Bson> update = List.of(new Document("$set",
                new Document("expiresAt", new Document("$add", List.of("$$NOW", ttl.toMillis())))));

        UpdateResult result = locks.updateOne(filter, update);
        return result.getMatchedCount() > 0;
    }

    @Override
    public void release(String jobName, long fencingToken) {
        locks.updateOne(
                new Document("_id", jobName).append("token", fencingToken).append("status", "HELD"),
                Updates.set("status", "FREE"));
    }

    @Override
    public void checkConnectivity() {
        locks.estimatedDocumentCount();
    }
}
