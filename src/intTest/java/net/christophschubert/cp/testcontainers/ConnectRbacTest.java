package net.christophschubert.cp.testcontainers;

import io.restassured.RestAssured;
import net.christophschubert.cp.testcontainers.util.ConnectClient;
import net.christophschubert.cp.testcontainers.util.DataGenConfig;
import net.christophschubert.cp.testcontainers.util.MdsRestWrapper;
import net.christophschubert.cp.testcontainers.util.TestClients;
import org.junit.Test;
import org.testcontainers.lifecycle.Startables;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static io.restassured.RestAssured.given;
import static net.christophschubert.cp.testcontainers.util.MdsRestWrapper.ClusterRole.SecurityAdmin;
import static net.christophschubert.cp.testcontainers.util.MdsRestWrapper.KafkaResourceType.*;
import static net.christophschubert.cp.testcontainers.util.MdsRestWrapper.ResourceRole.*;
import static net.christophschubert.cp.testcontainers.util.TestContainerUtils.startAll;

public class ConnectRbacTest {

    // alice is our trusted admin
    final String alice = "alice";
    final String aliceSecret = "alice-secret";

    void setUpAccess(ConfluentServerContainer cpServer) {

        RestAssured.port = cpServer.getMdsPort();


        // get Kafka cluster id
        final var id = given().
                when().
                get("/v1/metadata/id").
                then().
                statusCode(200).
                log().all().extract().body().path("id").toString();

        final var clusters = Map.of("clusters",
                Map.of("kafka-cluster", id,"connect-cluster", "connect"));

        // grant connect principal the security admin role on the connect cluster
        given().auth().preemptive().basic(alice, aliceSecret)
                .body(clusters)
                .contentType("application/json")
                .log().all()
                .when()
                .post("/security/1.0/principals/User:connect-principal/roles/SecurityAdmin")
                .then().log().all().statusCode(204);

        // add ClusterAdmin role so that we can view connector status
        given().auth().preemptive().basic(alice, aliceSecret)
                .body(clusters)
                .contentType("application/json")
                .when()
                .post("/security/1.0/principals/User:connect-principal/roles/SystemAdmin")
                .then().log().all().statusCode(204);

        // grant ResourceOwner on connect group and on the topics
        var connectGroup = Map.of("scope", Map.of("clusters", Map.of("kafka-cluster", id)),
                "resourcePatterns", List.of(
                        Map.of("resourceType", "Group","name", "connect","patternType", "LITERAL"),
                        Map.of("resourceType", "Topic","name", "connect-configs","patternType", "LITERAL"),
                        Map.of("resourceType", "Topic","name", "connect-offsets","patternType", "LITERAL"),
                        Map.of("resourceType", "Topic","name", "connect-status","patternType", "LITERAL"),
                        Map.of("resourceType", "Topic","name", "datagen","patternType", "LITERAL")
                ));

        given().auth().preemptive().basic(alice, aliceSecret)
                 .body(connectGroup)
                 .contentType("application/json")
                 .when()
                 .post("/security/1.0/principals/User:connect-principal/roles/ResourceOwner/bindings")
                 .then().log().all().statusCode(204);
    }

    @Test
    public void setupRbacConnectFailsWithoutAuthZ() throws ExecutionException, InterruptedException, IOException {
        final String connectPrincipal = "connect-principal";
        final String connectSecret = connectPrincipal + "-secret";

        final var factory = new CPTestContainerFactory();
        final var ldap = factory.createLdap(Set.of("alice", "bob", connectPrincipal));

        final var cServer = factory.createConfluentServer().enableRbac();
        final var connect = factory.createConfluentServerConnect(List.of("confluentinc/kafka-connect-datagen:0.4.0"), cServer)
                .enableRbac("http://kafka:8090", connectPrincipal, connectSecret)
                .withLogConsumer(o -> System.out.print(o.getUtf8String()));

        startAll(ldap, cServer);

        setUpAccess(cServer);

        connect.start();

        RestAssured.port = connect.getMappedHttpPort();
        given().auth().preemptive().basic(connectPrincipal, connectSecret)
                .when()
                .get("/connectors")
                .then().log().all().statusCode(200);

        ConnectClient client = new ConnectClient(connect.getBaseUrl(), connectPrincipal, connectSecret);

        final var dataGenConfig = new DataGenConfig("datagen-connector")
                .withKafkaTopic("datagen")
                .withQuickstart("inventory")
                .withIterations(1000)
                .with("value.converter.schemas.enable", "false");
        client.startConnector(dataGenConfig);

        RestAssured.port = connect.getMappedHttpPort();
        given().auth().preemptive().basic(connectPrincipal, connectSecret)
                .when()
                .get("/connectors")
                .then().log().all().statusCode(200);

    }

    @Test
    public void connectWithRbacEnabledSchemaRegistry() throws InterruptedException {
        // enable user 'bob' to start a connect to write to a topic with an Avro schema
        final String connectPrincipal = "connect-principal";
        final String connectSecret = connectPrincipal + "-secret";
        final String srPrincipal = "sr-user";
        final String srSecret = srPrincipal + "-secret";
        final var factory = new CPTestContainerFactory();
        final var ldap = factory.createLdap(Set.of("alice", "bob", srPrincipal, connectPrincipal));

        final var cpServer = factory.createConfluentServer().enableRbac();
        startAll(ldap, cpServer);


        final var connect = factory.createConfluentServerConnect(List.of("confluentinc/kafka-connect-datagen:0.4.0"), cpServer)
                .enableRbac("http://kafka:8090", connectPrincipal, connectSecret)
                .withLogConsumer(o -> System.out.print(o.getUtf8String()));
        final var schemaRegistry = factory.createSchemaRegistry(cpServer).enableRbac();

        final var mdsWrapper = new MdsRestWrapper(cpServer.getMdsPort(), alice, aliceSecret);

        // configure general access for schema registry
        mdsWrapper.grantRoleOnCluster(srPrincipal, SecurityAdmin, MdsRestWrapper.ClusterType.SchemaRegistryCluster, schemaRegistry.getClusterId());

        mdsWrapper.grantRoleOnKafkaResource(srPrincipal, ResourceOwner, Group, schemaRegistry.getClusterId());
        mdsWrapper.grantRoleOnKafkaResource(srPrincipal, ResourceOwner, Topic, "_schemas");

        for (var role : List.of(DeveloperRead, DeveloperWrite)) {
            mdsWrapper.grantRoleOnKafkaResource(srPrincipal, role, Topic, cpServer.licenseTopic());
        }

        // configure general access for connect cluster
        // TODO: add method to get clusterId from connectCluster
        mdsWrapper.grantRoleOnCluster(connectPrincipal, SecurityAdmin, MdsRestWrapper.ClusterType.ConnectCluster, "connect");

        // add ClusterAdmin role so that we can view connector status
        // TODO: double check whether we need systemAdmin or ClusterAdmin or whether this is needed at all
        mdsWrapper.grantRoleOnCluster(connectPrincipal, MdsRestWrapper.ClusterRole.SystemAdmin, MdsRestWrapper.ClusterType.ConnectCluster, "connect");

        mdsWrapper.grantRoleOnKafkaResource(connectPrincipal, ResourceOwner, Group, "connect");

        for (var topicName: List.of("connect-configs", "connect-offsets", "connect-status")) {
            mdsWrapper.grantRoleOnKafkaResource(connectPrincipal, ResourceOwner, Topic, topicName);
        }


        connect.prettyPrintEnvs();

        startAll(connect, schemaRegistry);

        //bob should be able to start the connector, so let's give him the DeveloperManage role
        //(see https://docs.confluent.io/platform/current/connect/rbac/connect-rbac-getting-started.html)
        final var connectorName = "datagen";
        final var topicName = "datagen";
        mdsWrapper.grantRoleOnResource("bob", DeveloperManage, MdsRestWrapper.ClusterType.ConnectCluster, "connect", MdsRestWrapper.ResourceType.Connector, connectorName);

        mdsWrapper.grantRoleOnResource("bob", ResourceOwner, MdsRestWrapper.ClusterType.SchemaRegistryCluster, schemaRegistry.getClusterId(), MdsRestWrapper.ResourceType.Subject, topicName + "-value");

        mdsWrapper.grantRoleOnKafkaResource("bob", ResourceOwner, Topic, topicName);

        mdsWrapper.grantRoleOnKafkaResource("bob", DeveloperWrite, Cluster, "kafka-cluster");

        final var dataGenConfig = new DataGenConfig(connectorName)
                .withKafkaTopic(topicName)
                .withQuickstart("inventory")
                .withIterations(10000)
                .withValueConverter("io.confluent.connect.avro.AvroConverter")
                .with("value.converter.schema.registry.url", schemaRegistry.getInternalBaseUrl())
                .with("value.converter.basic.auth.credentials.source", "USER_INFO")
                .with("value.converter.basic.auth.user.info", "bob:bob-secret")
                // option 1: use overrides here
//                .with("producer.override.security.protocol", "SASL_PLAINTEXT")
//                .with("producer.override.sasl.mechanism", "OAUTHBEARER")
//                .with("producer.override.sasl.login.callback.handler.class", "io.confluent.kafka.clients.plugins.auth.token.TokenUserLoginCallbackHandler")
                .with("producer.override.sasl.jaas.config", SecurityConfigs.oauthJaas("bob", "bob-secret", "http://kafka:8090"));

        RestAssured.port = connect.getMappedHttpPort();
        given().auth().preemptive().basic("bob", "bob-secret")
                .when()
                .contentType("application/json")
                .body(dataGenConfig)
                .log().all()
                .post("/connectors")
                .then().log().all().statusCode(201);


        Thread.sleep(2000);

        given().auth().preemptive().basic("bob", "bob-secret")
                .when()
                .contentType("application/json")
                .log().all()
                .get("/connectors/" + connectorName + "/status")
                .then().log().all().statusCode(200);



        mdsWrapper.grantRoleOnKafkaResource("bob", DeveloperRead, Group, "test-group");

        final Map<String, Object> addProps = new HashMap<>(SecurityConfigs.plainJaasProperties("bob", "bob-secret"));

        addProps.put("basic.auth.credentials.source", "USER_INFO");
        addProps.put("schema.registry.basic.auth.user.info", "bob:bob-secret");

        final var consumer = TestClients.createAvroConsumer(cpServer.getBootstrapServers(),
                schemaRegistry.getBaseUrl(),
                addProps);
        consumer.subscribe(List.of(topicName));
        System.out.println(consumer.consumeUntil(100));
    }
}
