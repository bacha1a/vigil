package io.vigil.lock.mongo;

import com.mongodb.client.MongoClients;
import io.vigil.core.spi.FencedLock;
import io.vigil.testkit.FencedLockContract;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MongoFencedLockContractTest extends FencedLockContract {

    @Container
    static final MongoDBContainer DB = new MongoDBContainer("mongo:7");

    static FencedLock lock;

    @BeforeAll
    static void setUp() {
        lock = new MongoFencedLock(
                MongoClients.create(DB.getConnectionString()).getDatabase("vigil_test"));
    }

    @Override
    protected FencedLock lock() {
        return lock;
    }
}
