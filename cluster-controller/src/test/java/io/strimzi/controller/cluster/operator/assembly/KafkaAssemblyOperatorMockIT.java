/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.controller.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.controller.cluster.Reconciliation;
import io.strimzi.controller.cluster.model.AssemblyType;
import io.strimzi.controller.cluster.model.KafkaCluster;
import io.strimzi.controller.cluster.model.Labels;
import io.strimzi.controller.cluster.model.Storage;
import io.strimzi.controller.cluster.model.TopicController;
import io.strimzi.controller.cluster.model.ZookeeperCluster;
import io.strimzi.controller.cluster.operator.resource.ConfigMapOperator;
import io.strimzi.controller.cluster.operator.resource.DeploymentOperator;
import io.strimzi.controller.cluster.operator.resource.KafkaSetOperator;
import io.strimzi.controller.cluster.operator.resource.PvcOperator;
import io.strimzi.controller.cluster.operator.resource.ServiceOperator;
import io.strimzi.controller.cluster.operator.resource.ZookeeperSetOperator;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunnerWithParametersFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(VertxUnitRunnerWithParametersFactory.class)
public class KafkaAssemblyOperatorMockIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAssemblyOperatorMockIT.class);

    private static final String NAMESPACE = "my-namespace";
    private static final String CLUSTER_NAME = "my-cluster";

    private final int zkReplicas;
    private final JsonObject zkStorage;

    private final int kafkaReplicas;
    private final JsonObject kafkaStorage;
    private KubernetesClient mockClient;

    public static class Params {
        private final int zkReplicas;
        private final JsonObject zkStorage;

        private final int kafkaReplicas;
        private final JsonObject kafkaStorage;

        public Params(int zkReplicas, JsonObject zkStorage, int kafkaReplicas, JsonObject kafkaStorage) {
            this.kafkaReplicas = kafkaReplicas;
            this.kafkaStorage = kafkaStorage;
            this.zkReplicas = zkReplicas;
            this.zkStorage = zkStorage;
        }

        public String toString() {
            return "zkReplicas=" + zkReplicas +
                    ",zkStorage=" + kafkaStorage +
                    ",kafkaReplicas=" + kafkaReplicas +
                    ",kafkaStorage=" + kafkaStorage;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<KafkaAssemblyOperatorMockIT.Params> data() {
        int[] replicas = {1, 3};
        JsonObject[] storageConfigs = {
            new JsonObject("{\"type\": \"ephemeral\"}"),

            new JsonObject("{\"type\": \"persistent-claim\", " +
                    "\"size\": \"123\", " +
                    "\"class\": \"foo\"," +
                    "\"delete-claim\": true}"),

            new JsonObject("{\"type\": \"persistent-claim\", " +
                    "\"size\": \"123\", " +
                    "\"class\": \"foo\"," +
                    "\"delete-claim\": false}"),

            new JsonObject("{\"type\": \"local\", " +
                    "\"size\": \"123\", " +
                    "\"class\": \"foo\"}")
        };
        List<KafkaAssemblyOperatorMockIT.Params> result = new ArrayList();

        for (int zkReplica : replicas) {
            for (JsonObject zkStorage : storageConfigs) {
                for (int kafkaReplica : replicas) {
                    for (JsonObject kafkaStorage : storageConfigs) {
                        result.add(new KafkaAssemblyOperatorMockIT.Params(
                                zkReplica, zkStorage,
                                kafkaReplica, kafkaStorage));
                    }
                }
            }
        }

        return result;
    }

    public KafkaAssemblyOperatorMockIT(KafkaAssemblyOperatorMockIT.Params params) {
        this.zkReplicas = params.zkReplicas;
        this.zkStorage = params.zkStorage;

        this.kafkaReplicas = params.kafkaReplicas;
        this.kafkaStorage = params.kafkaStorage;
    }

    /** Return the storage type the test cluster initially uses */
    public Storage.StorageType storageType(JsonObject json) {
        return Storage.fromJson(json).type();
    }

    /** Return the storage class the test cluster initially uses */
    public String storageClass(JsonObject json) {
        return json.getString(Storage.STORAGE_CLASS_FIELD);
    }

    /** Return the storage delete-claim the test cluster initially uses */
    public boolean deleteClaim(JsonObject json) {
        return json.getBoolean(Storage.DELETE_CLAIM_FIELD, false);
    }


    private Vertx vertx;
    private ConfigMap cluster;

    @Before
    public void before() {
        this.vertx = Vertx.vertx();
        this.cluster = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Labels.forKind("cluster").withType(AssemblyType.KAFKA).toMap())
                .endMetadata()
                .withData(map(KafkaCluster.KEY_REPLICAS, String.valueOf(kafkaReplicas),
                        KafkaCluster.KEY_STORAGE, kafkaStorage.toString(),
                        KafkaCluster.KEY_METRICS_CONFIG, "{}",
                        ZookeeperCluster.KEY_REPLICAS, String.valueOf(zkReplicas),
                        ZookeeperCluster.KEY_STORAGE, zkStorage.toString(),
                        ZookeeperCluster.KEY_METRICS_CONFIG, "{}",
                        TopicController.KEY_CONFIG, "{}"))
                .build();
        mockClient = new MockKube().withInitialCms(Collections.singleton(cluster)).build();
    }

    @After
    public void after() {
        this.vertx.close();
    }

    private static <T> Map<T, T> map(T... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException();
        }
        Map<T, T> result = new HashMap<>(pairs.length / 2);
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private KafkaAssemblyOperator createCluster(TestContext context) {
        ConfigMapOperator cmops = new ConfigMapOperator(vertx, mockClient);
        ServiceOperator svcops = new ServiceOperator(vertx, mockClient);
        KafkaSetOperator ksops = new KafkaSetOperator(vertx, mockClient);
        ZookeeperSetOperator zksops = new ZookeeperSetOperator(vertx, mockClient);
        DeploymentOperator depops = new DeploymentOperator(vertx, mockClient);
        PvcOperator pvcops = new PvcOperator(vertx, mockClient);
        KafkaAssemblyOperator kco = new KafkaAssemblyOperator(vertx, true, 2_000,
                cmops, svcops, zksops, ksops, pvcops, depops);

        LOGGER.info("Reconciling initially -> create");
        Async createAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            context.assertNotNull(mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(KafkaCluster.kafkaClusterName(CLUSTER_NAME)).get());
            context.assertNotNull(mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(ZookeeperCluster.zookeeperClusterName(CLUSTER_NAME)).get());
            context.assertNotNull(mockClient.extensions().deployments().inNamespace(NAMESPACE).withName(TopicController.topicControllerName(CLUSTER_NAME)).get());
            context.assertNotNull(mockClient.configMaps().inNamespace(NAMESPACE).withName(KafkaCluster.metricConfigsName(CLUSTER_NAME)).get());
            context.assertNotNull(mockClient.configMaps().inNamespace(NAMESPACE).withName(ZookeeperCluster.zookeeperMetricsName(CLUSTER_NAME)).get());
            createAsync.complete();
        });
        createAsync.await();
        return kco;
    }

    /** Create a cluster from a Kafka Cluster CM */
    @Test
    public void testCreateUpdateDelete(TestContext context) {
        Set<String> expectedClaims = resilientPvcs();

        KafkaAssemblyOperator kco = createCluster(context);
        LOGGER.info("Reconciling again -> update");
        Async updateAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            updateAsync.complete();
        });
        updateAsync.await();
        LOGGER.info("Reconciling again -> delete");
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).delete();
        Async deleteAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            assertPvcs(context, expectedClaims);
            deleteAsync.complete();
        });
    }

    private void assertPvcs(TestContext context, Set<String> expectedClaims) {
        context.assertEquals(expectedClaims,
                mockClient.persistentVolumeClaims().inNamespace(NAMESPACE).list().getItems().stream()
                        .map(pvc -> pvc.getMetadata().getName()).collect(Collectors.toSet()));
    }

    /**
     * Create PVCs for appropriate for the kafka and ZK storage test parameters,
     * return the names of the PVCs which shouldn't be deleted according to the deleteClaim config
     */
    private Set<String> resilientPvcs() {
        Set<String> expectedClaims = new HashSet<>();
        Set<String> kafkaPvcNames = createPvcs(kafkaStorage,
                kafkaReplicas, podId -> KafkaCluster.getPersistentVolumeClaimName(KafkaCluster.kafkaClusterName(CLUSTER_NAME), podId)
        );
        if (!deleteClaim(kafkaStorage)) {
            expectedClaims.addAll(kafkaPvcNames);
        }
        Set<String> zkPvcNames = createPvcs(zkStorage,
                zkReplicas, podId -> ZookeeperCluster.getPersistentVolumeClaimName(ZookeeperCluster.zookeeperClusterName(CLUSTER_NAME), podId)
        );
        if (!deleteClaim(zkStorage)) {
            expectedClaims.addAll(zkPvcNames);
        }
        return expectedClaims;
    }

    /**
     * Create PVCs in the mockClient
     * according to the given storage, number of replicas and naming scheme,
     * return the names of the PVCs created
     */
    private Set<String> createPvcs(JsonObject storage, int replicas, Function<Integer, String> pvcNameFn) {
        Set<String> expectedClaims = new HashSet<>();
        if (storageType(storage).equals(Storage.StorageType.PERSISTENT_CLAIM)) {
            for (int i = 0; i < replicas; i++) {
                String pvcName = pvcNameFn.apply(i);
                mockClient.persistentVolumeClaims().inNamespace(NAMESPACE).withName(pvcName).create(
                        new PersistentVolumeClaimBuilder().withNewMetadata()
                                .withNamespace(NAMESPACE)
                                .withName(pvcName)
                                .endMetadata()
                                .build());
                expectedClaims.add(pvcName);

            }
        }
        return expectedClaims;
    }

    /**
     * Test the controller re-creates services if they get deleted
     */
    private void updateClusterWithoutServices(TestContext context, String... services) {

        KafkaAssemblyOperator kco = createCluster(context);
        for (String service: services) {
            mockClient.services().inNamespace(NAMESPACE).withName(service).delete();
            assertNull("Expected service " + service + " to be not exist",
                    mockClient.services().inNamespace(NAMESPACE).withName(service).get());
        }
        LOGGER.info("Reconciling again -> update");
        Async updateAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            for (String service: services) {
                assertNotNull(
                        "Expected service " + service + " to have been recreated",
                        mockClient.services().inNamespace(NAMESPACE).withName(service).get());
            }
            updateAsync.complete();
        });
    }

    @Test
    public void testUpdateClusterWithoutZkServices(TestContext context) {
        updateClusterWithoutServices(context,
                ZookeeperCluster.zookeeperClusterName(CLUSTER_NAME),
                ZookeeperCluster.zookeeperHeadlessName(CLUSTER_NAME));
    }

    @Test
    public void testUpdateClusterWithoutKafkaServices(TestContext context) {
        updateClusterWithoutServices(context,
                KafkaCluster.kafkaClusterName(CLUSTER_NAME),
                KafkaCluster.headlessName(CLUSTER_NAME));
    }

    @Test
    public void testUpdateClusterWithoutZkStatefulSet(TestContext context) {
        String statefulSet = ZookeeperCluster.zookeeperClusterName(CLUSTER_NAME);
        updateClusterWithoutStatefulSet(context, statefulSet);
    }

    @Test
    public void testUpdateClusterWithoutKafkaStatefulSet(TestContext context) {
        String statefulSet = KafkaCluster.kafkaClusterName(CLUSTER_NAME);
        updateClusterWithoutStatefulSet(context, statefulSet);
    }

    private void updateClusterWithoutStatefulSet(TestContext context, String statefulSet) {
        KafkaAssemblyOperator kco = createCluster(context);

        mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(statefulSet).delete();
        assertNull("Expected ss " + statefulSet + " to be not exist",
                mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(statefulSet).get());

        LOGGER.info("Reconciling again -> update");
        Async updateAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());

            assertNotNull(
                    "Expected ss " + statefulSet + " to have been recreated",
                    mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(statefulSet).get());

            updateAsync.complete();
        });
    }

    private void deleteClusterWithoutServices(TestContext context, String... services) {

        KafkaAssemblyOperator kco = createCluster(context);
        Set<String> ssNames = mockClient.apps().statefulSets().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet());
        Set<String> deploymentNames = mockClient.extensions().deployments().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet());
        Set<String> cmNames = new HashSet<>(mockClient.configMaps().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet()));
        cmNames.remove(CLUSTER_NAME);
        Set<String> pvcNames = mockClient.persistentVolumeClaims().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet());
        for (String service: services) {
            mockClient.services().inNamespace(NAMESPACE).withName(service).delete();
            assertNull("Expected service " + service + " to be not exist",
                    mockClient.services().inNamespace(NAMESPACE).withName(service).get());
        }
        LOGGER.info("Deleting");
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).delete();
        Async updateAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            for (String service: services) {
                assertNull(
                        "Expected service " + service + " to still not exist",
                        mockClient.services().inNamespace(NAMESPACE).withName(service).get());
            }
            for (String ss: ssNames) {
                assertNull(
                        "Expected ss " + ss + " to still not exist",
                        mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(ss).get());
            }
            // assert other resources do not exist either
            for (String r: ssNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(r).get());
            }
            for (String r: deploymentNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.extensions().deployments().inNamespace(NAMESPACE).withName(r).get());
            }
            for (String r: cmNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.configMaps().inNamespace(NAMESPACE).withName(r).get());
            }
            for (String r: pvcNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.persistentVolumeClaims().inNamespace(NAMESPACE).withName(r).get());
            }
            updateAsync.complete();
        });
    }

    @Test
    public void testDeleteClusterWithoutZkServices(TestContext context) {
        deleteClusterWithoutServices(context,
                ZookeeperCluster.zookeeperClusterName(CLUSTER_NAME),
                ZookeeperCluster.zookeeperHeadlessName(CLUSTER_NAME));
    }

    @Test
    public void testDeleteClusterWithoutKafkaServices(TestContext context) {
        deleteClusterWithoutServices(context,
                KafkaCluster.kafkaClusterName(CLUSTER_NAME),
                KafkaCluster.headlessName(CLUSTER_NAME));
    }

    private void deleteClusterWithoutStatefulSet(TestContext context, String... statefulSets) {

        KafkaAssemblyOperator kco = createCluster(context);
        Set<String> ssNames = mockClient.apps().statefulSets().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet());
        Set<String> serviceNames = mockClient.services().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet());
        Set<String> deploymentNames = mockClient.extensions().deployments().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet());
        Set<String> cmNames = new HashSet<>(mockClient.configMaps().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet()));
        cmNames.remove(CLUSTER_NAME);
        Set<String> pvcNames = mockClient.persistentVolumeClaims().inNamespace(NAMESPACE).list().getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toSet());

        for (String ss: statefulSets) {
            mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(ss).delete();
            assertNull("Expected ss " + ss + " to be not exist",
                    mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(ss).get());
        }
        LOGGER.info("Deleting");
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).delete();
        Async updateAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            for (String ss: statefulSets) {
                assertNull(
                        "Expected ss " + ss + " to still not exist",
                        mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(ss).get());
            }
            // assert other resources do not exist either
            for (String r: ssNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(r).get());
            }
            for (String r: serviceNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.services().inNamespace(NAMESPACE).withName(r).get());
            }
            for (String r: deploymentNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.extensions().deployments().inNamespace(NAMESPACE).withName(r).get());
            }
            for (String r: cmNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.configMaps().inNamespace(NAMESPACE).withName(r).get());
            }
            for (String r: pvcNames) {
                assertNull(
                        "Expected r " + r + " to still not exist",
                        mockClient.persistentVolumeClaims().inNamespace(NAMESPACE).withName(r).get());
            }
            updateAsync.complete();
        });
    }

    @Test
    public void testDeleteClusterWithoutZkStatefulSet(TestContext context) {
        deleteClusterWithoutStatefulSet(context,
                ZookeeperCluster.zookeeperClusterName(CLUSTER_NAME));
    }

    @Test
    public void testDeleteClusterWithoutKafkaStatefulSet(TestContext context) {
        deleteClusterWithoutStatefulSet(context,
                KafkaCluster.kafkaClusterName(CLUSTER_NAME));
    }

    @Test
    public void testUpdateKafkaWithChangedPersistentVolume(TestContext context) {
        if (!Storage.StorageType.PERSISTENT_CLAIM.equals(storageType(kafkaStorage))) {
            LOGGER.info("Skipping claim-based test because using storage type {}", kafkaStorage);
            return;
        }

        KafkaAssemblyOperator kco = createCluster(context);
        String originalStorageClass = storageClass(kafkaStorage);
        assertStorageClass(context, KafkaCluster.kafkaClusterName(CLUSTER_NAME), originalStorageClass);

        Async updateAsync = context.async();

        // Try to update the storage class
        String changedClass = originalStorageClass + "2";

        HashMap<String, String> data = new HashMap<>(cluster.getData());
        data.put(KafkaCluster.KEY_STORAGE,
                new JsonObject(kafkaStorage.toString()).put(Storage.STORAGE_CLASS_FIELD, changedClass).toString());
        ConfigMap changedClusterCm = new ConfigMapBuilder(cluster).withData(data).build();
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).patch(changedClusterCm);

        LOGGER.info("Updating with changed storage class");
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            // Check the storage class was not changed
            assertStorageClass(context, KafkaCluster.kafkaClusterName(CLUSTER_NAME), originalStorageClass);
            updateAsync.complete();
        });
    }

    private void assertStorageClass(TestContext context, String statefulSetName, String expectedClass) {
        StatefulSet statefulSet = mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(statefulSetName).get();
        context.assertNotNull(statefulSet);
        // Check the storage class is initially "foo"
        List<PersistentVolumeClaim> volumeClaimTemplates = statefulSet.getSpec().getVolumeClaimTemplates();
        context.assertFalse(volumeClaimTemplates.isEmpty());
        context.assertEquals(expectedClass, volumeClaimTemplates.get(0).getSpec().getStorageClassName());
    }

    /** Test that we can change the deleteClaim flag, and that it's honoured */
    @Test
    public void testUpdateKafkaWithChangedDeleteClaim(TestContext context) {
        if (!Storage.StorageType.PERSISTENT_CLAIM.equals(storageType(kafkaStorage))) {
            LOGGER.info("Skipping claim-based test because using storage type {}", kafkaStorage);
            return;
        }

        Set<String> allPvcs = new HashSet<>();
        Set<String> kafkaPvcs = createPvcs(kafkaStorage,
                kafkaReplicas, podId -> KafkaCluster.getPersistentVolumeClaimName(KafkaCluster.kafkaClusterName(CLUSTER_NAME), podId)
        );
        Set<String> zkPvcs = createPvcs(zkStorage,
                zkReplicas, podId -> ZookeeperCluster.getPersistentVolumeClaimName(ZookeeperCluster.zookeeperClusterName(CLUSTER_NAME), podId)
        );
        allPvcs.addAll(kafkaPvcs);
        allPvcs.addAll(zkPvcs);

        KafkaAssemblyOperator kco = createCluster(context);

        boolean originalKafkaDeleteClaim = deleteClaim(kafkaStorage);
        //assertDeleteClaim(context, KafkaCluster.kafkaClusterName(CLUSTER_NAME), originalKafkaDeleteClaim);

        // Try to update the storage class
        boolean changedKafkaDeleteClaim = !originalKafkaDeleteClaim;
        HashMap<String, String> data = new HashMap<>(cluster.getData());
        data.put(KafkaCluster.KEY_STORAGE,
                new JsonObject(kafkaStorage.toString()).put(Storage.DELETE_CLAIM_FIELD, changedKafkaDeleteClaim).toString());
        ConfigMap changedClusterCm = new ConfigMapBuilder(cluster).withData(data).build();
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).patch(changedClusterCm);

        LOGGER.info("Updating with changed delete claim");
        Async updateAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            updateAsync.complete();
        });
        updateAsync.await();

        LOGGER.info("Reconciling again -> delete");
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).delete();
        Async deleteAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            assertPvcs(context, changedKafkaDeleteClaim ? deleteClaim(zkStorage) ? emptySet() : zkPvcs :
                    deleteClaim(zkStorage) ? kafkaPvcs : allPvcs);
            deleteAsync.complete();
        });
    }

    /** Create a cluster from a Kafka Cluster CM */
    @Test
    public void testKafkaScaleDown(TestContext context) {
        if (kafkaReplicas <= 1) {
            LOGGER.info("Skipping scale down test because there's only 1 broker");
            return;
        }
        KafkaAssemblyOperator kco = createCluster(context);
        Async updateAsync = context.async();

        int newScale = kafkaReplicas - 1;
        String deletedPod = KafkaCluster.kafkaPodName(CLUSTER_NAME, newScale);
        context.assertNotNull(mockClient.pods().inNamespace(NAMESPACE).withName(deletedPod).get());

        HashMap<String, String> data = new HashMap<>(cluster.getData());
        data.put(KafkaCluster.KEY_REPLICAS,
                String.valueOf(newScale));
        ConfigMap changedClusterCm = new ConfigMapBuilder(cluster).withData(data).build();
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).patch(changedClusterCm);

        LOGGER.info("Scaling down to {} Kafka pods", newScale);
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            context.assertEquals(newScale,
                    mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(KafkaCluster.kafkaClusterName(CLUSTER_NAME)).get().getSpec().getReplicas());
            context.assertNull(mockClient.pods().inNamespace(NAMESPACE).withName(deletedPod).get(),
                    "Expected pod " + deletedPod + " to have been deleted");
            // TODO assert no rolling update
            updateAsync.complete();
        });
        updateAsync.await();
    }

    /** Create a cluster from a Kafka Cluster CM */
    @Test
    public void testKafkaScaleUp(TestContext context) {

        KafkaAssemblyOperator kco = createCluster(context);
        Async updateAsync = context.async();

        int newScale = kafkaReplicas + 1;
        String newPod = KafkaCluster.kafkaPodName(CLUSTER_NAME, kafkaReplicas);
        context.assertNull(mockClient.pods().inNamespace(NAMESPACE).withName(newPod).get());

        HashMap<String, String> data = new HashMap<>(cluster.getData());
        data.put(KafkaCluster.KEY_REPLICAS,
                String.valueOf(newScale));
        ConfigMap changedClusterCm = new ConfigMapBuilder(cluster).withData(data).build();
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).patch(changedClusterCm);

        LOGGER.info("Scaling up to {} Kafka pods", newScale);
        kco.reconcileAssembly(new Reconciliation("test-trigger", AssemblyType.KAFKA, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            context.assertEquals(newScale,
                    mockClient.apps().statefulSets().inNamespace(NAMESPACE).withName(KafkaCluster.kafkaClusterName(CLUSTER_NAME)).get().getSpec().getReplicas());
            context.assertNotNull(mockClient.pods().inNamespace(NAMESPACE).withName(newPod).get(),
                    "Expected pod " + newPod + " to have been created");
            // TODO assert no rolling update
            updateAsync.complete();
        });
        updateAsync.await();
    }

    @Test
    public void testReconcileAllDeleteCase(TestContext context) throws InterruptedException {
        KafkaAssemblyOperator kco = createCluster(context);
        mockClient.configMaps().inNamespace(NAMESPACE).withName(CLUSTER_NAME).delete();

        LOGGER.info("reconcileAll after CM deletion -> All resources should be deleted");
        kco.reconcileAll("test-trigger", NAMESPACE, Labels.forKind("cluster")).await();

        // Assert no CMs, Services, StatefulSets, Deployments are left
        context.assertTrue(mockClient.configMaps().inNamespace(NAMESPACE).list().getItems().isEmpty());
        context.assertTrue(mockClient.services().inNamespace(NAMESPACE).list().getItems().isEmpty());
        context.assertTrue(mockClient.apps().statefulSets().inNamespace(NAMESPACE).list().getItems().isEmpty());
        context.assertTrue(mockClient.extensions().deployments().inNamespace(NAMESPACE).list().getItems().isEmpty());
    }

}
