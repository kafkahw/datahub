package com.linkedin.metadata.entity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.Status;
import com.linkedin.common.urn.CorpuserUrn;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.ByteString;
import com.linkedin.data.template.DataTemplateUtil;
import com.linkedin.data.template.JacksonDataTemplateCodec;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.dataset.DatasetProfile;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.EnvelopedAspect;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.identity.CorpUserInfo;
import com.linkedin.metadata.aspect.Aspect;
import com.linkedin.metadata.aspect.CorpUserAspect;
import com.linkedin.metadata.aspect.CorpUserAspectArray;
import com.linkedin.metadata.aspect.VersionedAspect;
import com.linkedin.metadata.entity.ebean.EbeanAspectDao;
import com.linkedin.metadata.entity.ebean.EbeanAspectV2;
import com.linkedin.metadata.entity.ebean.EbeanEntityService;
import com.linkedin.metadata.entity.ebean.EbeanRetentionService;
import com.linkedin.metadata.entity.ebean.EbeanUtils;
import com.linkedin.metadata.event.EntityEventProducer;
import com.linkedin.metadata.key.CorpUserKey;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.registry.ConfigEntityRegistry;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.models.registry.EntityRegistryException;
import com.linkedin.metadata.models.registry.MergedEntityRegistry;
import com.linkedin.metadata.query.ListUrnsResult;
import com.linkedin.metadata.run.AspectRowSummary;
import com.linkedin.metadata.snapshot.CorpUserSnapshot;
import com.linkedin.metadata.snapshot.Snapshot;
import com.linkedin.metadata.utils.EntityKeyUtils;
import com.linkedin.metadata.utils.PegasusUtils;
import com.linkedin.mxe.GenericAspect;
import com.linkedin.mxe.MetadataAuditOperation;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.mxe.SystemMetadata;
import com.linkedin.retention.DataHubRetentionConfig;
import com.linkedin.retention.Retention;
import com.linkedin.retention.VersionBasedRetention;
import io.ebean.EbeanServer;
import io.ebean.EbeanServerFactory;
import io.ebean.config.ServerConfig;
import io.ebean.datasource.DataSourceConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;


public class EbeanEntityServiceTest {

  private static final AuditStamp TEST_AUDIT_STAMP = createTestAuditStamp();
  private final EntityRegistry _snapshotEntityRegistry = new TestEntityRegistry();
  private final EntityRegistry _configEntityRegistry =
      new ConfigEntityRegistry(Snapshot.class.getClassLoader().getResourceAsStream("entity-registry.yml"));
  private final EntityRegistry _testEntityRegistry =
      new MergedEntityRegistry(_snapshotEntityRegistry).apply(_configEntityRegistry);
  private EbeanEntityService _entityService;
  private EbeanAspectDao _aspectDao;
  private EbeanServer _server;
  private EntityEventProducer _mockProducer;
  private EbeanRetentionService _retentionService;

  public EbeanEntityServiceTest() throws EntityRegistryException {
  }

  @Nonnull
  private static ServerConfig createTestingH2ServerConfig() {
    DataSourceConfig dataSourceConfig = new DataSourceConfig();
    dataSourceConfig.setUsername("tester");
    dataSourceConfig.setPassword("");
    dataSourceConfig.setUrl("jdbc:h2:mem:;IGNORECASE=TRUE;");
    dataSourceConfig.setDriver("org.h2.Driver");

    ServerConfig serverConfig = new ServerConfig();
    serverConfig.setName("gma");
    serverConfig.setDataSourceConfig(dataSourceConfig);
    serverConfig.setDdlGenerate(true);
    serverConfig.setDdlRun(true);

    return serverConfig;
  }

  private static AuditStamp createTestAuditStamp() {
    try {
      return new AuditStamp().setTime(123L).setActor(Urn.createFromString("urn:li:principal:tester"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to create urn");
    }
  }

  @BeforeMethod
  public void setupTest() {
    _server = EbeanServerFactory.create(createTestingH2ServerConfig());
    _mockProducer = mock(EntityEventProducer.class);
    _aspectDao = new EbeanAspectDao(_server);
    _aspectDao.setConnectionValidated(true);
    _entityService = new EbeanEntityService(_aspectDao, _mockProducer, _testEntityRegistry);
    _retentionService = new EbeanRetentionService(_entityService, _server, 1000);
    _entityService.setRetentionService(_retentionService);
  }

  @Test
  public void testIngestGetEntity() throws Exception {
    // Test Writing a CorpUser Entity
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test");
    com.linkedin.entity.Entity writeEntity = createCorpUserEntity(entityUrn, "tester@test.com");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    // 1. Ingest Entity
    _entityService.ingestEntity(writeEntity, TEST_AUDIT_STAMP, metadata1);

    // 2. Retrieve Entity
    com.linkedin.entity.Entity readEntity = _entityService.getEntity(entityUrn, Collections.emptySet());

    // 3. Compare Entity Objects
    assertEquals(2, readEntity.getValue().getCorpUserSnapshot().getAspects().size()); // Key + Info aspect.
    assertTrue(DataTemplateUtil.areEqual(writeEntity.getValue().getCorpUserSnapshot().getAspects().get(0),
        readEntity.getValue().getCorpUserSnapshot().getAspects().get(1)));
    CorpUserKey expectedKey = new CorpUserKey();
    expectedKey.setUsername("test");
    assertTrue(DataTemplateUtil.areEqual(expectedKey,
        readEntity.getValue().getCorpUserSnapshot().getAspects().get(0).getCorpUserKey())); // Key + Info aspect.

    verify(_mockProducer, times(2)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));
    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testAddKey() throws Exception {
    // Test Writing a CorpUser Key
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test");
    com.linkedin.entity.Entity writeEntity = createCorpUserEntity(entityUrn, "tester@test.com");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    // 1. Ingest Entity
    _entityService.ingestEntity(writeEntity, TEST_AUDIT_STAMP, metadata1);

    // 2. Retrieve Entity
    com.linkedin.entity.Entity readEntity = _entityService.getEntity(entityUrn, Collections.emptySet());

    // 3. Compare Entity Objects
    assertEquals(2, readEntity.getValue().getCorpUserSnapshot().getAspects().size()); // Key + Info aspect.
    assertTrue(DataTemplateUtil.areEqual(writeEntity.getValue().getCorpUserSnapshot().getAspects().get(0),
        readEntity.getValue().getCorpUserSnapshot().getAspects().get(1)));
    CorpUserKey expectedKey = new CorpUserKey();
    expectedKey.setUsername("test");
    assertTrue(DataTemplateUtil.areEqual(expectedKey,
        readEntity.getValue().getCorpUserSnapshot().getAspects().get(0).getCorpUserKey())); // Key + Info aspect.

    verify(_mockProducer, times(2)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));
    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testIngestGetEntities() throws Exception {
    // Test Writing a CorpUser Entity
    Urn entityUrn1 = Urn.createFromString("urn:li:corpuser:tester1");
    com.linkedin.entity.Entity writeEntity1 = createCorpUserEntity(entityUrn1, "tester@test.com");

    Urn entityUrn2 = Urn.createFromString("urn:li:corpuser:tester2");
    com.linkedin.entity.Entity writeEntity2 = createCorpUserEntity(entityUrn2, "tester2@test.com");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1625792690);
    metadata2.setRunId("run-123");

    // 1. Ingest Entities
    _entityService.ingestEntities(ImmutableList.of(writeEntity1, writeEntity2), TEST_AUDIT_STAMP,
        ImmutableList.of(metadata1, metadata2));

    // 2. Retrieve Entities
    Map<Urn, com.linkedin.entity.Entity> readEntities =
        _entityService.getEntities(ImmutableSet.of(entityUrn1, entityUrn2), Collections.emptySet());

    // 3. Compare Entity Objects

    // Entity 1
    com.linkedin.entity.Entity readEntity1 = readEntities.get(entityUrn1);
    assertEquals(2, readEntity1.getValue().getCorpUserSnapshot().getAspects().size()); // Key + Info aspect.
    assertTrue(DataTemplateUtil.areEqual(writeEntity1.getValue().getCorpUserSnapshot().getAspects().get(0),
        readEntity1.getValue().getCorpUserSnapshot().getAspects().get(1)));
    CorpUserKey expectedKey1 = new CorpUserKey();
    expectedKey1.setUsername("tester1");
    assertTrue(DataTemplateUtil.areEqual(expectedKey1,
        readEntity1.getValue().getCorpUserSnapshot().getAspects().get(0).getCorpUserKey())); // Key + Info aspect.

    // Entity 2
    com.linkedin.entity.Entity readEntity2 = readEntities.get(entityUrn2);
    assertEquals(2, readEntity2.getValue().getCorpUserSnapshot().getAspects().size()); // Key + Info aspect.
    assertTrue(DataTemplateUtil.areEqual(writeEntity2.getValue().getCorpUserSnapshot().getAspects().get(0),
        readEntity2.getValue().getCorpUserSnapshot().getAspects().get(1)));
    CorpUserKey expectedKey2 = new CorpUserKey();
    expectedKey2.setUsername("tester2");
    assertTrue(DataTemplateUtil.areEqual(expectedKey2,
        readEntity2.getValue().getCorpUserSnapshot().getAspects().get(0).getCorpUserKey())); // Key + Info aspect.

    verify(_mockProducer, times(2)).produceMetadataAuditEvent(Mockito.eq(entityUrn1), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verify(_mockProducer, times(2)).produceMetadataAuditEvent(Mockito.eq(entityUrn2), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testIngestGetEntitiesV2() throws Exception {
    // Test Writing a CorpUser Entity
    Urn entityUrn1 = Urn.createFromString("urn:li:corpuser:tester1");
    com.linkedin.entity.Entity writeEntity1 = createCorpUserEntity(entityUrn1, "tester@test.com");

    Urn entityUrn2 = Urn.createFromString("urn:li:corpuser:tester2");
    com.linkedin.entity.Entity writeEntity2 = createCorpUserEntity(entityUrn2, "tester2@test.com");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1625792690);
    metadata2.setRunId("run-123");

    String aspectName = "corpUserInfo";
    String keyName = "corpUserKey";

    // 1. Ingest Entities
    _entityService.ingestEntities(ImmutableList.of(writeEntity1, writeEntity2), TEST_AUDIT_STAMP,
        ImmutableList.of(metadata1, metadata2));

    // 2. Retrieve Entities
    Map<Urn, EntityResponse> readEntities =
        _entityService.getEntitiesV2("corpuser", ImmutableSet.of(entityUrn1, entityUrn2), ImmutableSet.of(aspectName));

    // 3. Compare Entity Objects

    // Entity 1
    EntityResponse readEntityResponse1 = readEntities.get(entityUrn1);
    assertEquals(2, readEntityResponse1.getAspects().size()); // Key + Info aspect.
    EnvelopedAspect envelopedAspect1 = readEntityResponse1.getAspects().get(aspectName);
    assertEquals(envelopedAspect1.getName(), aspectName);
    assertTrue(
        DataTemplateUtil.areEqual(writeEntity1.getValue().getCorpUserSnapshot().getAspects().get(0).getCorpUserInfo(),
            new CorpUserInfo(envelopedAspect1.getValue().data())));
    CorpUserKey expectedKey1 = new CorpUserKey();
    expectedKey1.setUsername("tester1");
    EnvelopedAspect envelopedKey1 = readEntityResponse1.getAspects().get(keyName);
    assertTrue(DataTemplateUtil.areEqual(expectedKey1, new CorpUserKey(envelopedKey1.getValue().data())));

    // Entity 2
    EntityResponse readEntityResponse2 = readEntities.get(entityUrn2);
    assertEquals(2, readEntityResponse2.getAspects().size()); // Key + Info aspect.
    EnvelopedAspect envelopedAspect2 = readEntityResponse2.getAspects().get(aspectName);
    assertEquals(envelopedAspect2.getName(), aspectName);
    assertTrue(
        DataTemplateUtil.areEqual(writeEntity2.getValue().getCorpUserSnapshot().getAspects().get(0).getCorpUserInfo(),
            new CorpUserInfo(envelopedAspect2.getValue().data())));
    CorpUserKey expectedKey2 = new CorpUserKey();
    expectedKey2.setUsername("tester2");
    EnvelopedAspect envelopedKey2 = readEntityResponse2.getAspects().get(keyName);
    assertTrue(DataTemplateUtil.areEqual(expectedKey2, new CorpUserKey(envelopedKey2.getValue().data())));

    verify(_mockProducer, times(2)).produceMetadataAuditEvent(Mockito.eq(entityUrn1), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verify(_mockProducer, times(2)).produceMetadataAuditEvent(Mockito.eq(entityUrn2), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testIngestGetLatestAspect() throws Exception {
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test");

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    String aspectName = PegasusUtils.getAspectNameFromSchema(writeAspect1.schema());

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1635792689);
    metadata2.setRunId("run-456");

    // Validate retrieval of CorpUserInfo Aspect #1
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);
    RecordTemplate readAspect1 = _entityService.getLatestAspect(entityUrn, aspectName);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1, readAspect1));

    // Ingest CorpUserInfo Aspect #2
    CorpUserInfo writeAspect2 = createCorpUserInfo("email2@test.com");

    // Validate retrieval of CorpUserInfo Aspect #2
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect2, TEST_AUDIT_STAMP, metadata2);
    RecordTemplate readAspect2 = _entityService.getLatestAspect(entityUrn, aspectName);
    EbeanAspectV2 readEbean1 = _aspectDao.getAspect(entityUrn.toString(), aspectName, 1);
    EbeanAspectV2 readEbean2 = _aspectDao.getAspect(entityUrn.toString(), aspectName, 0);

    assertTrue(DataTemplateUtil.areEqual(writeAspect2, readAspect2));
    assertTrue(DataTemplateUtil.areEqual(EbeanUtils.parseSystemMetadata(readEbean2.getSystemMetadata()), metadata2));
    assertTrue(DataTemplateUtil.areEqual(EbeanUtils.parseSystemMetadata(readEbean1.getSystemMetadata()), metadata1));

    verify(_mockProducer, times(1)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verify(_mockProducer, times(1)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.notNull(), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testIngestGetLatestEnvelopedAspect() throws Exception {
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test");

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    String aspectName = PegasusUtils.getAspectNameFromSchema(writeAspect1.schema());

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1635792689);
    metadata2.setRunId("run-456");

    // Validate retrieval of CorpUserInfo Aspect #1
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);
    EnvelopedAspect readAspect1 = _entityService.getLatestEnvelopedAspect("corpuser", entityUrn, aspectName);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1, new CorpUserInfo(readAspect1.getValue().data())));

    // Ingest CorpUserInfo Aspect #2
    CorpUserInfo writeAspect2 = createCorpUserInfo("email2@test.com");

    // Validate retrieval of CorpUserInfo Aspect #2
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect2, TEST_AUDIT_STAMP, metadata2);
    EnvelopedAspect readAspect2 = _entityService.getLatestEnvelopedAspect("corpuser", entityUrn, aspectName);
    EbeanAspectV2 readEbean1 = _aspectDao.getAspect(entityUrn.toString(), aspectName, 1);
    EbeanAspectV2 readEbean2 = _aspectDao.getAspect(entityUrn.toString(), aspectName, 0);

    assertTrue(DataTemplateUtil.areEqual(writeAspect2, new CorpUserInfo(readAspect2.getValue().data())));
    assertTrue(DataTemplateUtil.areEqual(EbeanUtils.parseSystemMetadata(readEbean2.getSystemMetadata()), metadata2));
    assertTrue(DataTemplateUtil.areEqual(EbeanUtils.parseSystemMetadata(readEbean1.getSystemMetadata()), metadata1));

    verify(_mockProducer, times(1)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verify(_mockProducer, times(1)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.notNull(), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testIngestSameAspect() throws Exception {
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test");

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    String aspectName = PegasusUtils.getAspectNameFromSchema(writeAspect1.schema());

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1635792689);
    metadata2.setRunId("run-456");

    // Validate retrieval of CorpUserInfo Aspect #1
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);
    RecordTemplate readAspect1 = _entityService.getLatestAspect(entityUrn, aspectName);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1, readAspect1));

    // Ingest CorpUserInfo Aspect #2
    CorpUserInfo writeAspect2 = createCorpUserInfo("email@test.com");

    // Validate retrieval of CorpUserInfo Aspect #2
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect2, TEST_AUDIT_STAMP, metadata2);
    RecordTemplate readAspect2 = _entityService.getLatestAspect(entityUrn, aspectName);
    EbeanAspectV2 readEbean2 = _aspectDao.getAspect(entityUrn.toString(), aspectName, 0);

    assertTrue(DataTemplateUtil.areEqual(writeAspect2, readAspect2));
    assertFalse(DataTemplateUtil.areEqual(EbeanUtils.parseSystemMetadata(readEbean2.getSystemMetadata()), metadata2));
    assertFalse(DataTemplateUtil.areEqual(EbeanUtils.parseSystemMetadata(readEbean2.getSystemMetadata()), metadata1));

    SystemMetadata metadata3 = new SystemMetadata();
    metadata3.setLastObserved(1635792689);
    metadata3.setRunId("run-123");

    assertTrue(DataTemplateUtil.areEqual(EbeanUtils.parseSystemMetadata(readEbean2.getSystemMetadata()), metadata3));

    verify(_mockProducer, times(1)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.eq(null), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verify(_mockProducer, times(0)).produceMetadataAuditEvent(Mockito.eq(entityUrn), Mockito.notNull(), Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.eq(MetadataAuditOperation.UPDATE));

    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testIngestListLatestAspects() throws Exception {
    Urn entityUrn1 = Urn.createFromString("urn:li:corpuser:test1");
    Urn entityUrn2 = Urn.createFromString("urn:li:corpuser:test2");
    Urn entityUrn3 = Urn.createFromString("urn:li:corpuser:test3");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserInfo().schema());

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #2
    CorpUserInfo writeAspect2 = createCorpUserInfo("email2@test.com");
    _entityService.ingestAspect(entityUrn2, aspectName, writeAspect2, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #3
    CorpUserInfo writeAspect3 = createCorpUserInfo("email3@test.com");
    _entityService.ingestAspect(entityUrn3, aspectName, writeAspect3, TEST_AUDIT_STAMP, metadata1);

    // List aspects
    ListResult<RecordTemplate> batch1 = _entityService.listLatestAspects(entityUrn1.getEntityType(), aspectName, 0, 2);

    assertEquals(2, batch1.getNextStart());
    assertEquals(2, batch1.getPageSize());
    assertEquals(3, batch1.getTotalCount());
    assertEquals(2, batch1.getTotalPageCount());
    assertEquals(2, batch1.getValues().size());
    assertTrue(DataTemplateUtil.areEqual(writeAspect1, batch1.getValues().get(0)));
    assertTrue(DataTemplateUtil.areEqual(writeAspect2, batch1.getValues().get(1)));

    ListResult<RecordTemplate> batch2 = _entityService.listLatestAspects(entityUrn1.getEntityType(), aspectName, 2, 2);
    assertEquals(1, batch2.getValues().size());
    assertTrue(DataTemplateUtil.areEqual(writeAspect3, batch2.getValues().get(0)));
  }

  @Test
  public void testIngestTimeseriesAspect() throws Exception {
    Urn entityUrn = Urn.createFromString("urn:li:dataset:(urn:li:dataPlatform:foo,bar,PROD)");
    DatasetProfile datasetProfile = new DatasetProfile();
    datasetProfile.setRowCount(1000);
    datasetProfile.setColumnCount(15);
    datasetProfile.setTimestampMillis(0L);
    MetadataChangeProposal gmce = new MetadataChangeProposal();
    gmce.setEntityUrn(entityUrn);
    gmce.setChangeType(ChangeType.UPSERT);
    gmce.setEntityType("dataset");
    gmce.setAspectName("datasetProfile");
    JacksonDataTemplateCodec dataTemplateCodec = new JacksonDataTemplateCodec();
    byte[] datasetProfileSerialized = dataTemplateCodec.dataTemplateToBytes(datasetProfile);
    GenericAspect genericAspect = new GenericAspect();
    genericAspect.setValue(ByteString.unsafeWrap(datasetProfileSerialized));
    genericAspect.setContentType("application/json");
    gmce.setAspect(genericAspect);
    _entityService.ingestProposal(gmce, TEST_AUDIT_STAMP);
  }

  @Test
  public void testUpdateGetAspect() throws Exception {
    // Test Writing a CorpUser Entity
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserInfo().schema());
    AspectSpec corpUserInfoSpec = _testEntityRegistry.getEntitySpec("corpuser").getAspectSpec("corpUserInfo");

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect = createCorpUserInfo("email@test.com");

    // Validate retrieval of CorpUserInfo Aspect #1
    _entityService.updateAspect(entityUrn, "corpuser", aspectName, corpUserInfoSpec, writeAspect, TEST_AUDIT_STAMP, 1,
        true);
    RecordTemplate readAspect1 = _entityService.getAspect(entityUrn, aspectName, 1);
    assertTrue(DataTemplateUtil.areEqual(writeAspect, readAspect1));
    verify(_mockProducer, times(1)).produceMetadataChangeLog(Mockito.eq(entityUrn), Mockito.eq(corpUserInfoSpec),
        Mockito.any());

    // Ingest CorpUserInfo Aspect #2
    writeAspect.setEmail("newemail@test.com");

    // Validate retrieval of CorpUserInfo Aspect #2
    _entityService.updateAspect(entityUrn, "corpuser", aspectName, corpUserInfoSpec, writeAspect, TEST_AUDIT_STAMP, 1,
        false);
    RecordTemplate readAspect2 = _entityService.getAspect(entityUrn, aspectName, 1);
    assertTrue(DataTemplateUtil.areEqual(writeAspect, readAspect2));
    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testGetAspectAtVersion() throws Exception {
    // Test Writing a CorpUser Entity
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserInfo().schema());
    AspectSpec corpUserInfoSpec = _testEntityRegistry.getEntitySpec("corpuser").getAspectSpec("corpUserInfo");

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect = createCorpUserInfo("email@test.com");

    // Validate retrieval of CorpUserInfo Aspect #1
    _entityService.updateAspect(entityUrn, "corpuser", aspectName, corpUserInfoSpec, writeAspect, TEST_AUDIT_STAMP, 1,
        true);

    VersionedAspect writtenVersionedAspect = new VersionedAspect();
    writtenVersionedAspect.setAspect(Aspect.create(writeAspect));
    writtenVersionedAspect.setVersion(1);

    VersionedAspect readAspect1 = _entityService.getVersionedAspect(entityUrn, aspectName, 1);
    assertTrue(DataTemplateUtil.areEqual(writtenVersionedAspect, readAspect1));
    verify(_mockProducer, times(1)).produceMetadataChangeLog(Mockito.eq(entityUrn), Mockito.eq(corpUserInfoSpec),
        Mockito.any());

    VersionedAspect readAspect2 = _entityService.getVersionedAspect(entityUrn, aspectName, -1);
    assertTrue(DataTemplateUtil.areEqual(writtenVersionedAspect, readAspect2));

    VersionedAspect readAspectVersion0 = _entityService.getVersionedAspect(entityUrn, aspectName, 0);
    assertFalse(DataTemplateUtil.areEqual(writtenVersionedAspect, readAspectVersion0));

    verifyNoMoreInteractions(_mockProducer);
  }

  @Test
  public void testRollbackAspect() throws Exception {
    Urn entityUrn1 = Urn.createFromString("urn:li:corpuser:test1");
    Urn entityUrn2 = Urn.createFromString("urn:li:corpuser:test2");
    Urn entityUrn3 = Urn.createFromString("urn:li:corpuser:test3");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1635792689);
    metadata2.setRunId("run-456");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserInfo().schema());

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #2
    CorpUserInfo writeAspect2 = createCorpUserInfo("email2@test.com");
    _entityService.ingestAspect(entityUrn2, aspectName, writeAspect2, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #3
    CorpUserInfo writeAspect3 = createCorpUserInfo("email3@test.com");
    _entityService.ingestAspect(entityUrn3, aspectName, writeAspect3, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #1 Overwrite
    CorpUserInfo writeAspect1Overwrite = createCorpUserInfo("email1.overwrite@test.com");
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1Overwrite, TEST_AUDIT_STAMP, metadata2);

    // this should no-op since this run has been overwritten
    AspectRowSummary rollbackOverwrittenAspect = new AspectRowSummary();
    rollbackOverwrittenAspect.setRunId("run-123");
    rollbackOverwrittenAspect.setAspectName(aspectName);
    rollbackOverwrittenAspect.setUrn(entityUrn1.toString());

    _entityService.rollbackRun(ImmutableList.of(rollbackOverwrittenAspect), "run-123");

    // assert nothing was deleted
    RecordTemplate readAspectOriginal = _entityService.getAspect(entityUrn1, aspectName, 1);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1, readAspectOriginal));

    RecordTemplate readAspectOverwrite = _entityService.getAspect(entityUrn1, aspectName, 0);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1Overwrite, readAspectOverwrite));

    // this should delete the most recent aspect
    AspectRowSummary rollbackRecentAspect = new AspectRowSummary();
    rollbackRecentAspect.setRunId("run-456");
    rollbackRecentAspect.setAspectName(aspectName);
    rollbackRecentAspect.setUrn(entityUrn1.toString());

    _entityService.rollbackRun(ImmutableList.of(rollbackOverwrittenAspect), "run-456");

    // assert the new most recent aspect is the original one
    RecordTemplate readNewRecentAspect = _entityService.getAspect(entityUrn1, aspectName, 0);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1, readNewRecentAspect));
  }

  @Test
  public void testRollbackKey() throws Exception {
    Urn entityUrn1 = Urn.createFromString("urn:li:corpuser:test1");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1635792689);
    metadata2.setRunId("run-456");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserInfo().schema());
    String keyAspectName = _entityService.getKeyAspectName(entityUrn1);

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);

    RecordTemplate writeKey1 = _entityService.buildKeyAspect(entityUrn1);
    _entityService.ingestAspect(entityUrn1, keyAspectName, writeKey1, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #1 Overwrite
    CorpUserInfo writeAspect1Overwrite = createCorpUserInfo("email1.overwrite@test.com");
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1Overwrite, TEST_AUDIT_STAMP, metadata2);

    // this should no-op since the key should have been written in the furst run
    AspectRowSummary rollbackKeyWithWrongRunId = new AspectRowSummary();
    rollbackKeyWithWrongRunId.setRunId("run-456");
    rollbackKeyWithWrongRunId.setAspectName("corpUserKey");
    rollbackKeyWithWrongRunId.setUrn(entityUrn1.toString());

    _entityService.rollbackRun(ImmutableList.of(rollbackKeyWithWrongRunId), "run-456");

    // assert nothing was deleted
    RecordTemplate readAspectOriginal = _entityService.getAspect(entityUrn1, aspectName, 1);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1, readAspectOriginal));

    RecordTemplate readAspectOverwrite = _entityService.getAspect(entityUrn1, aspectName, 0);
    assertTrue(DataTemplateUtil.areEqual(writeAspect1Overwrite, readAspectOverwrite));

    // this should delete the most recent aspect
    AspectRowSummary rollbackKeyWithCorrectRunId = new AspectRowSummary();
    rollbackKeyWithCorrectRunId.setRunId("run-123");
    rollbackKeyWithCorrectRunId.setAspectName("corpUserKey");
    rollbackKeyWithCorrectRunId.setUrn(entityUrn1.toString());

    _entityService.rollbackRun(ImmutableList.of(rollbackKeyWithCorrectRunId), "run-123");

    // assert the new most recent aspect is null
    RecordTemplate readNewRecentAspect = _entityService.getAspect(entityUrn1, aspectName, 0);
    assertTrue(DataTemplateUtil.areEqual(null, readNewRecentAspect));
  }

  @Test
  public void testRollbackUrn() throws Exception {
    Urn entityUrn1 = Urn.createFromString("urn:li:corpuser:test1");
    Urn entityUrn2 = Urn.createFromString("urn:li:corpuser:test2");
    Urn entityUrn3 = Urn.createFromString("urn:li:corpuser:test3");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    SystemMetadata metadata2 = new SystemMetadata();
    metadata2.setLastObserved(1635792689);
    metadata2.setRunId("run-456");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserInfo().schema());
    String keyAspectName = _entityService.getKeyAspectName(entityUrn1);

    // Ingest CorpUserInfo Aspect #1
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);

    RecordTemplate writeKey1 = _entityService.buildKeyAspect(entityUrn1);
    _entityService.ingestAspect(entityUrn1, keyAspectName, writeKey1, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #2
    CorpUserInfo writeAspect2 = createCorpUserInfo("email2@test.com");
    _entityService.ingestAspect(entityUrn2, aspectName, writeAspect2, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #3
    CorpUserInfo writeAspect3 = createCorpUserInfo("email3@test.com");
    _entityService.ingestAspect(entityUrn3, aspectName, writeAspect3, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #1 Overwrite
    CorpUserInfo writeAspect1Overwrite = createCorpUserInfo("email1.overwrite@test.com");
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1Overwrite, TEST_AUDIT_STAMP, metadata2);

    // this should no-op since the key should have been written in the furst run
    AspectRowSummary rollbackKeyWithWrongRunId = new AspectRowSummary();
    rollbackKeyWithWrongRunId.setRunId("run-456");
    rollbackKeyWithWrongRunId.setAspectName("CorpUserKey");
    rollbackKeyWithWrongRunId.setUrn(entityUrn1.toString());

    // this should delete all related aspects
    _entityService.deleteUrn(Urn.createFromString("urn:li:corpuser:test1"));

    // assert the new most recent aspect is null
    RecordTemplate readNewRecentAspect = _entityService.getAspect(entityUrn1, aspectName, 0);
    assertTrue(DataTemplateUtil.areEqual(null, readNewRecentAspect));

    RecordTemplate deletedKeyAspect = _entityService.getAspect(entityUrn1, "corpUserKey", 0);
    assertTrue(DataTemplateUtil.areEqual(null, deletedKeyAspect));
  }

  @Test
  public void testIngestListUrns() throws Exception {
    Urn entityUrn1 = Urn.createFromString("urn:li:corpuser:test1");
    Urn entityUrn2 = Urn.createFromString("urn:li:corpuser:test2");
    Urn entityUrn3 = Urn.createFromString("urn:li:corpuser:test3");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserKey().schema());

    // Ingest CorpUserInfo Aspect #1
    RecordTemplate writeAspect1 = createCorpUserKey(entityUrn1);
    _entityService.ingestAspect(entityUrn1, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #2
    RecordTemplate writeAspect2 = createCorpUserKey(entityUrn2);
    _entityService.ingestAspect(entityUrn2, aspectName, writeAspect2, TEST_AUDIT_STAMP, metadata1);

    // Ingest CorpUserInfo Aspect #3
    RecordTemplate writeAspect3 = createCorpUserKey(entityUrn3);
    _entityService.ingestAspect(entityUrn3, aspectName, writeAspect3, TEST_AUDIT_STAMP, metadata1);

    // List aspects urns
    ListUrnsResult batch1 = _entityService.listUrns(entityUrn1.getEntityType(), 0, 2);

    assertEquals(0, (int) batch1.getStart());
    assertEquals(2, (int) batch1.getCount());
    assertEquals(3, (int) batch1.getTotal());
    assertEquals(2, batch1.getEntities().size());
    assertEquals(entityUrn1.toString(), batch1.getEntities().get(0).toString());
    assertEquals(entityUrn2.toString(), batch1.getEntities().get(1).toString());

    ListUrnsResult batch2 = _entityService.listUrns(entityUrn1.getEntityType(), 2, 2);

    assertEquals(2, (int) batch2.getStart());
    assertEquals(1, (int) batch2.getCount());
    assertEquals(3, (int) batch2.getTotal());
    assertEquals(1, batch2.getEntities().size());
    assertEquals(entityUrn3.toString(), batch2.getEntities().get(0).toString());
  }

  @Test
  public void testRetention() throws Exception {
    Urn entityUrn = Urn.createFromString("urn:li:corpuser:test1");

    SystemMetadata metadata1 = new SystemMetadata();
    metadata1.setLastObserved(1625792689);
    metadata1.setRunId("run-123");

    String aspectName = PegasusUtils.getAspectNameFromSchema(new CorpUserInfo().schema());

    // Ingest CorpUserInfo Aspect
    CorpUserInfo writeAspect1 = createCorpUserInfo("email@test.com");
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect1, TEST_AUDIT_STAMP, metadata1);
    CorpUserInfo writeAspect1a = createCorpUserInfo("email_a@test.com");
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect1a, TEST_AUDIT_STAMP, metadata1);
    CorpUserInfo writeAspect1b = createCorpUserInfo("email_b@test.com");
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect1b, TEST_AUDIT_STAMP, metadata1);

    String aspectName2 = PegasusUtils.getAspectNameFromSchema(new Status().schema());
    // Ingest Status Aspect
    Status writeAspect2 = new Status().setRemoved(true);
    _entityService.ingestAspect(entityUrn, aspectName2, writeAspect2, TEST_AUDIT_STAMP, metadata1);
    Status writeAspect2a = new Status().setRemoved(false);
    _entityService.ingestAspect(entityUrn, aspectName2, writeAspect2a, TEST_AUDIT_STAMP, metadata1);
    Status writeAspect2b = new Status().setRemoved(true);
    _entityService.ingestAspect(entityUrn, aspectName2, writeAspect2b, TEST_AUDIT_STAMP, metadata1);

    assertEquals(_entityService.getAspect(entityUrn, aspectName, 1), writeAspect1);
    assertEquals(_entityService.getAspect(entityUrn, aspectName2, 1), writeAspect2);

    _retentionService.setRetention(null, null, new DataHubRetentionConfig().setRetention(
        new Retention().setVersion(new VersionBasedRetention().setMaxVersions(2))));
    _retentionService.setRetention("corpuser", "status", new DataHubRetentionConfig().setRetention(
        new Retention().setVersion(new VersionBasedRetention().setMaxVersions(4))));

    // Ingest CorpUserInfo Aspect again
    CorpUserInfo writeAspect1c = createCorpUserInfo("email_c@test.com");
    _entityService.ingestAspect(entityUrn, aspectName, writeAspect1c, TEST_AUDIT_STAMP, metadata1);
    // Ingest Status Aspect again
    Status writeAspect2c = new Status().setRemoved(false);
    _entityService.ingestAspect(entityUrn, aspectName2, writeAspect2c, TEST_AUDIT_STAMP, metadata1);

    assertNull(_entityService.getAspect(entityUrn, aspectName, 1));
    assertEquals(_entityService.getAspect(entityUrn, aspectName2, 1), writeAspect2);

    // Reset retention policies
    _retentionService.setRetention(null, null, new DataHubRetentionConfig().setRetention(
        new Retention().setVersion(new VersionBasedRetention().setMaxVersions(1))));
    _retentionService.deleteRetention("corpuser", "status");
    // Invoke batch apply
    _retentionService.batchApplyRetention(null, null);
    assertEquals(_entityService.listLatestAspects(entityUrn.getEntityType(), aspectName, 0, 10).getTotalCount(), 1);
    assertEquals(_entityService.listLatestAspects(entityUrn.getEntityType(), aspectName2, 0, 10).getTotalCount(), 1);
  }

  @Nonnull
  private com.linkedin.entity.Entity createCorpUserEntity(Urn entityUrn, String email) throws Exception {
    CorpuserUrn corpuserUrn = CorpuserUrn.createFromUrn(entityUrn);
    com.linkedin.entity.Entity entity = new com.linkedin.entity.Entity();
    Snapshot snapshot = new Snapshot();
    CorpUserSnapshot corpUserSnapshot = new CorpUserSnapshot();
    List<CorpUserAspect> userAspects = new ArrayList<>();
    userAspects.add(CorpUserAspect.create(createCorpUserInfo(email)));
    corpUserSnapshot.setAspects(new CorpUserAspectArray(userAspects));
    corpUserSnapshot.setUrn(corpuserUrn);
    snapshot.setCorpUserSnapshot(corpUserSnapshot);
    entity.setValue(snapshot);
    return entity;
  }

  @Nonnull
  private RecordTemplate createCorpUserKey(Urn urn) throws Exception {
    return EntityKeyUtils.convertUrnToEntityKey(urn, new CorpUserKey().schema());
  }

  @Nonnull
  private CorpUserInfo createCorpUserInfo(String email) throws Exception {
    CorpUserInfo corpUserInfo = new CorpUserInfo();
    corpUserInfo.setEmail(email);
    corpUserInfo.setActive(true);
    return corpUserInfo;
  }
}
