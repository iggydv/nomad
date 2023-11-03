package org.nomad.delegation;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.nomad.delegation.models.VoronoiSitePoint;
import org.nomad.grpc.superpeerservice.VirtualPosition;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

class VoronoiWrapperTest {

    VoronoiWrapper subject = new VoronoiWrapper(10.000, 10.000);
    ObjectList<VoronoiSitePoint> points = new ObjectArrayList<>();

    @BeforeEach
    void setup() {
        points = new ObjectArrayList<>();
        points.add(new VoronoiSitePoint("a", 1.00, 1.00));
        points.add(new VoronoiSitePoint("b", 2.00, 3.00));
        points.add(new VoronoiSitePoint("c", 2.00, 2.00));
        points.add(new VoronoiSitePoint("e", 7.24, 2.27));
        points.add(new VoronoiSitePoint("f", 3.38, 7.72));
        points.add(new VoronoiSitePoint("g", 7.86, 4.24));
        points.add(new VoronoiSitePoint("h", 6.14, 2.99));
        points.add(new VoronoiSitePoint("i", 4.55, 3.36));
        points.add(new VoronoiSitePoint("j", 4.74, 3.30));
        points.add(new VoronoiSitePoint("k", 4.61, 3.15));
        points.add(new VoronoiSitePoint("l", 4.66, 3.45));

        subject.updateVoronoiMap(points);
    }

    @Test
    @Disabled
    void test() {
        Random r = new Random();
        long start = System.currentTimeMillis();
        ObjectList<VoronoiSitePoint> points2 = new ObjectArrayList<>();
        for (int i = 0; i <= 100000; i++) {
            points2.add(new VoronoiSitePoint("a" + i, r.nextDouble() * 10.0, r.nextDouble() * 10.0));
        }

        subject.updateVoronoiMap(points2);
        long end = System.currentTimeMillis();
        System.out.println("duration: " + (end - start) + " ms");
        Assertions.assertEquals(100000, subject.getRegionsCount());
    }

    @Test
    void addSuperPeerLocation() {
        subject.updateVoronoiMap(new VoronoiSitePoint("m", 5.00, 5.00));
        Assertions.assertEquals(12, subject.getRegionsCount());
    }

    @Test
    void findVoronoiGroupToJoin() {
        Assertions.assertEquals("l", subject.findVoronoiGroupToJoin(VirtualPosition.newBuilder().setX(5.00).setY(5.00).build()));
        Assertions.assertEquals(11, subject.getRegionsCount());
        subject.updateVoronoiMap(new VoronoiSitePoint("m", 5.00, 5.00));
        Assertions.assertEquals(12, subject.getRegionsCount());
        Assertions.assertEquals("m", subject.findVoronoiGroupToJoin(VirtualPosition.newBuilder().setX(5.00).setY(5.00).build()));
    }

    @Test
    void getNeighbours() {
        List<String> neighbours = Arrays.asList("c", "b");
        Assertions.assertArrayEquals(neighbours.toArray(), subject.getNeighbours(VirtualPosition.newBuilder().setX(1.00).setY(1.00).build()).toArray());
        Assertions.assertTrue(subject.getNeighbours(VirtualPosition.newBuilder().setX(5.00).setY(5.00).build()).isEmpty());
    }
}