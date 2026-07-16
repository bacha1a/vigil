package io.vigil.checkpoint.mongo;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.lock.mongo.MongoFencedLock;
import io.vigil.testkit.ChaosContract;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MongoChaosTest extends ChaosContract {

    @Container
    static final MongoDBContainer DB = new MongoDBContainer("mongo:7");

    static FencedLock        lock;
    static CheckpointManager checkpoints;

    @BeforeAll
    static void setUp() {
        MongoDatabase database = MongoClients.create(DB.getConnectionString()).getDatabase("vigil_test");
        lock        = new MongoFencedLock(database);
        checkpoints = new MongoCheckpointManager(database);
    }

    @Override
    protected CheckpointManager checkpoints() {
        return checkpoints;
    }

    @Override
    protected FencedLock lock() {
        return lock;
    }
}
