package io.vigil.checkpoint.dynamodb;

import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.lock.dynamodb.DynamoFencedLock;
import io.vigil.testkit.ChaosContract;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoChaosTest extends ChaosContract {

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.4"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    static FencedLock        lock;
    static CheckpointManager checkpoints;

    @BeforeAll
    static void setUp() {
        DynamoDbClient ddb = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build();

        ddb.createTable(b -> b.tableName("vigil_job_locks")
                .keySchema(KeySchemaElement.builder().attributeName("job_name").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("job_name").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
        ddb.createTable(b -> b.tableName("vigil_job_checkpoints")
                .keySchema(
                        KeySchemaElement.builder().attributeName("job_name").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("ckpt_key").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("job_name").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("ckpt_key").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
        ddb.waiter().waitUntilTableExists(b -> b.tableName("vigil_job_locks"));
        ddb.waiter().waitUntilTableExists(b -> b.tableName("vigil_job_checkpoints"));

        lock = new DynamoFencedLock(ddb, "vigil_job_locks");
        checkpoints = new DynamoCheckpointManager(ddb, "vigil_job_locks", "vigil_job_checkpoints");
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
