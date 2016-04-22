package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripSchedule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static com.conveyal.r5.analyst.scenario.FakeGraph.checksum;
import static com.conveyal.r5.analyst.scenario.FakeGraph.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Adjust speed on routes.
 */
public class AdjustSpeedTest {
    public TransportNetwork network;
    public long checksum;

    @Before
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = checksum(network);
    }

    /** Adjust speed on the entire route */
    @Test
    public void adjustSpeedWholeRoute () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        TripSchedule exemplarSchedule = network.transitLayer.tripPatterns.get(0).tripSchedules.get(0);

        int baseTripLength = exemplarSchedule.departures[exemplarSchedule.departures.length - 1] - exemplarSchedule.arrivals[0];

        AdjustSpeed as = new AdjustSpeed();
        as.routes = set("SINGLE_LINE:route");
        as.scale = 2;
        as.scaleDwells = true;

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(1, mod.transitLayer.tripPatterns.size());

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int newTripLength = schedule.departures[schedule.departures.length - 1] - schedule.arrivals[0];
            assertEquals(baseTripLength / 2, newTripLength, 2); // epsilon of 2 seconds to account for rounding errors
        }

        assertEquals(checksum, checksum(network));
    }

    /** Adjust speed on the entire route */
    @Test
    public void adjustSpeedWholeRouteButDontAdjustDwells () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        AdjustSpeed as = new AdjustSpeed();
        as.routes = set("SINGLE_LINE:route");
        as.scale = 2;
        as.scaleDwells = false;

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(1, mod.transitLayer.tripPatterns.size());

        // thrice travel time for three hops, divide by two, and twice dwell time but don't divide by 2.
        int expectedTripLength = FakeGraph.TRAVEL_TIME * 3 / 2 + FakeGraph.DWELL_TIME * 2;

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int newTripLength = schedule.departures[schedule.departures.length - 1] - schedule.arrivals[0];
            assertEquals(expectedTripLength, newTripLength, 2); // epsilon of 2 seconds to account for rounding errors
        }

        assertEquals(checksum, checksum(network));
    }

    /** Adjust speed on a segment of the route */
    @Test
    public void adjustSpeedSegment () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        AdjustSpeed as = new AdjustSpeed();
        as.routes = set("SINGLE_LINE:route");
        as.scale = 2;
        as.scaleDwells = false;
        as.hops = Arrays.asList(
                new String[] {"SINGLE_LINE:s2", "SINGLE_LINE:s3"},
                new String[] {"SINGLE_LINE:s3", "SINGLE_LINE:s4"}
        );

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(1, mod.transitLayer.tripPatterns.size());

        // two hops at half of travel time, one hop at full travel time, and dwell time not scaled
        int expectedTripLength = FakeGraph.TRAVEL_TIME  + FakeGraph.TRAVEL_TIME / 2 * 2 + FakeGraph.DWELL_TIME * 2;

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int newTripLength = schedule.departures[schedule.departures.length - 1] - schedule.arrivals[0];
            assertEquals(expectedTripLength, newTripLength, 2); // epsilon of 2 seconds to account for rounding errors
        }

        assertEquals(checksum, checksum(network));
    }

    /** Adjust speed on just one trip */
    @Test
    public void adjustSpeedOneTrip () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        assertTrue(network.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> "SINGLE_LINE:trip25200".equals(schedule.tripId)));

        assertTrue(network.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> !"SINGLE_LINE:trip25200".equals(schedule.tripId)));

        TripSchedule exemplarSchedule = network.transitLayer.tripPatterns.get(0).tripSchedules.get(0);

        int baseTripLength = exemplarSchedule.departures[exemplarSchedule.departures.length - 1] - exemplarSchedule.arrivals[0];

        AdjustSpeed as = new AdjustSpeed();
        as.trips = set("SINGLE_LINE:trip25200");
        as.scale = 2;
        as.scaleDwells = true;

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(1, mod.transitLayer.tripPatterns.size());

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int newTripLength = schedule.departures[schedule.departures.length - 1] - schedule.arrivals[0];

            if ("SINGLE_LINE:trip25200".equals(schedule.tripId))
                assertEquals(baseTripLength / 2, newTripLength, 2); // epsilon of 2 seconds to account for rounding errors
            else
                assertEquals(baseTripLength, newTripLength);
        }

        // make sure that there are both affected and unaffected trips
        assertTrue(mod.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> "SINGLE_LINE:trip25200".equals(schedule.tripId)));

        assertTrue(mod.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> !"SINGLE_LINE:trip25200".equals(schedule.tripId)));

        assertEquals(checksum, checksum(network));
    }

    @After
    public void tearDown () {
        this.network = null;
    }
}