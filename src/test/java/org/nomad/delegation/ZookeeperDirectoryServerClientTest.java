package org.nomad.delegation;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nomad.config.Config;
import org.nomad.delegation.models.GroupData;
import org.nomad.delegation.models.NeighbourData;
import org.nomad.delegation.models.VoronoiSitePoint;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableConfigurationProperties(value = Config.class)
@TestPropertySource("classpath:application.yml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = {ZookeeperDirectoryServerClient.class, Config.class, LeaderSelectorClient.class})
@DirtiesContext
@SpringBootTest
class ZookeeperDirectoryServerClientTest {

    private final String groupName = "group-1";
    private final String host = "localhost:1111";
    @Autowired
    ZookeeperDirectoryServerClient subject;
    private TestingServer zkServer;

    @BeforeAll
    public void setUp() throws Exception {
        zkServer = new TestingServer(2181, true);
    }

    @AfterEach
    public void reset() {
        subject.setVoronoiGroupingEnabled(false);
        try {
            subject.close();
        } catch (Exception e) {
            // Oops, that test probably didn't have a leader-selector client
        }
    }

    @AfterAll
    public void tearDown() throws Exception {
        zkServer.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void JoinGroup(boolean voronoiGroupingEnabled) throws Exception {
        subject.setVoronoiGroupingEnabled(voronoiGroupingEnabled);
        subject.setBaseSleepTimeMs(10);
        subject.setMaxRetries(1);
        subject.init(1000, 1000);

        String actualGroupName = subject.getGroupName();
        GroupData result = subject.joinGroup(VirtualPosition.newBuilder().setX(0).setY(0).setZ(0).build());

        String joinResult_groupName = result.getGroupName();
        String joinResult_Id = result.getPeerId();

        int groupCount = subject.getNumberOfGroups();
        String leaderId = subject.getGroupLeader(groupName);

        assertTrue(subject.isConnected());
        assertEquals(groupName, actualGroupName);
        assertEquals(joinResult_groupName, groupName);
        assertDoesNotThrow(() -> UUID.fromString(joinResult_Id));
        assertEquals(1, groupCount);
        assertEquals(joinResult_Id, leaderId);
    }

    @Test
    void testVoronoiGroupToJoin() throws Exception {
        subject.setVoronoiGroupingEnabled(true);
        subject.init(1000, 1000);

        List<String> joinableGroups = subject.getJoinableVoronoiGroups(VirtualPosition.newBuilder().setX(110).setY(223).build());
        Assertions.assertTrue(joinableGroups.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void test_leader_selection(boolean voronoiGroupingEnabled) throws Exception {
        subject.setVoronoiGroupingEnabled(voronoiGroupingEnabled);
        subject.setBaseSleepTimeMs(10);
        subject.setMaxRetries(1);
        subject.init(1000, 1000);

        GroupData result = subject.joinGroup(VirtualPosition.newBuilder().setX(110).setY(223).build());
        subject.setLeaderVoronoiSitePoint(host, 200, 200);
        String joinResult_Id = result.getPeerId();
        String id = subject.getGroupLeaderId(groupName);
        VoronoiSitePoint data = subject.getLeaderData(groupName, joinResult_Id);
        String actualGroupName = subject.getGroupName();

        assertEquals(joinResult_Id, id);
        assertEquals(groupName, actualGroupName);
        assertTrue(subject.isConnected());
        assertEquals(host, data.getHostname());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void test_setting_and_getting_data(boolean voronoiGroupingEnabled) throws Exception {
        subject.setVoronoiGroupingEnabled(voronoiGroupingEnabled);
        subject.setBaseSleepTimeMs(10);
        subject.setMaxRetries(1);
        subject.init(1000, 1000);

        GroupData result = subject.joinGroup(VirtualPosition.newBuilder().setX(110).setY(223).build());
        String joinResult_Id = result.getPeerId();
        String actualGroupName = subject.getGroupName();

        subject.setGroupStorageHostname(host);
        subject.setPeerHostname(host);
        subject.setDHTHostname(host);
        subject.setLeaderHostname(host);

        Thread.sleep(10);
        List<String> hostnames = subject.getGroupStorageHostnames(actualGroupName);
        String HostnameFromGroupMembers = subject.getDHTHostnamesFromGroupMember(actualGroupName);
        String HostnameFromDHTZNode = subject.getGroupMemberDHTHostname(actualGroupName);
        VoronoiSitePoint leaderHostname = subject.getLeaderData(groupName, joinResult_Id);

        assertEquals(host, hostnames.iterator().next());
        assertEquals(HostnameFromGroupMembers, HostnameFromDHTZNode);
        assertEquals(host, HostnameFromDHTZNode);
        assertEquals(host, leaderHostname.getHostname());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void test_migration_flow(boolean voronoiGroupingEnabled) throws Exception {
        subject.setVoronoiGroupingEnabled(voronoiGroupingEnabled);
        subject.setBaseSleepTimeMs(10);
        subject.setMaxRetries(1);
        subject.init(1000, 1000);

        subject.joinGroup(VirtualPosition.newBuilder().setX(110).setY(223).build());
        subject.migrateMember(groupName);
        subject.setGroupStorageHostname(host);
        subject.setPeerHostname(host);
        subject.setDHTHostname(host);
        subject.setLeaderHostname(host);

        NeighbourData neighbouringLeaderData = subject.getNeighbouringLeaderData(VirtualPosition.newBuilder().setX(110).setY(223).build());
        assertEquals("", neighbouringLeaderData.getGroupName());
        assertEquals(VoronoiSitePoint.builder().build(), neighbouringLeaderData.getLeaderData());
    }
}