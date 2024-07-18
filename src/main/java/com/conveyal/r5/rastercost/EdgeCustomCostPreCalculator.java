package com.conveyal.r5.rastercost;


import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.BasicTraversalTimeCalculator;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.TraversalTimeCalculator;

/**
 * Custom (experimental) Modification that loads costs from a raster.
 * This class, will precalculate custom cost traversal edge cost time seconds before routing
 * and will then be set as the StreetRoutes this.TimeCalculator
 * The traversal costs will then be just fetched from the preCalculated Map, making the custom cost 
 * routing faster
 */
public class EdgeCustomCostPreCalculator implements TraversalTimeCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private StreetLayer streetLayer;
    private Map<Integer, Integer> PreCalculatedEdgeTravelTimesWithCustomCosts;
    private TraversalTimeCalculator base;
    public ProfileRequest req = new ProfileRequest();

    public EdgeCustomCostPreCalculator(StreetLayer streetLayer) {
        this.streetLayer = streetLayer;
        this.PreCalculatedEdgeTravelTimesWithCustomCosts = new HashMap<>();
        this.base = new BasicTraversalTimeCalculator(streetLayer, true);
        this.req = new ProfileRequest();
    }

    // Currently GP2 is supporting only 1 mode and therefore only 1 Speed
    // thus we can set the mode to walking and the speed to a single speed no matter if
    // the travel mode is walking or bicycle, for it is used only here to get the speed
    public void setStaticTravelSpeeds(float speedKmh) {;
        // Convert speed from km/h to m/s as R5 is using m/s internally
        float travelSpeedMetersPerSecond = speedKmh * (5.0f / 18.0f);
        this.req.walkSpeed = travelSpeedMetersPerSecond;
        this.req.bikeSpeed = travelSpeedMetersPerSecond;
    }

    public void calculateCustomCostTraversalTimes() {
        setStaticTravelSpeeds(this.streetLayer.staticSpeedKmh);
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        List<CostField> costFields = streetLayer.edgeStore.costFields;
        // the streetMode is only used to get the speed, which is static
        // so we can just use walking here even if the actual steetMode would be cycling
        // so this mode should not make any difference
        StreetMode streetMode = StreetMode.BICYCLE;
        // Loop through all edges
        while (edge.advance()) {
            // Calculate the base travel time
            int baseTraversalTimeSeconds = base.traversalTimeSeconds(edge, streetMode, req);
            // Calculate the total travel time by adding the additional costs to the base travel time
            int totalTimeSeconds = baseTraversalTimeSeconds;
            // Loop through all costFields and add the additional costs to the total travel time per edge
            for (CostField costField : costFields) {
                totalTimeSeconds += costField.additionalTraversalTimeSeconds(edge, baseTraversalTimeSeconds);
            }
            if (totalTimeSeconds < 1) {
                // Cost was negative or zero. Clamping to 1 second.
                totalTimeSeconds = 1;
            }
            // Save the recalculated travel time to the HashMap
            PreCalculatedEdgeTravelTimesWithCustomCosts.put(edge.getEdgeIndex(), totalTimeSeconds);
        }
    }

    // when using precalculated costs, we can just fetch the cost from the map
    // which is faster than using MultistageTraversalTimeCalculator to calculate the cost during routing
    @Override
    public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
        return PreCalculatedEdgeTravelTimesWithCustomCosts.get(currentEdge.getEdgeIndex());
    }

    public Map<Integer, Integer> getPreCalculatedEdgeTravelTimesWithCustomCosts() {
        return PreCalculatedEdgeTravelTimesWithCustomCosts;
    }

    @Override
    public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
        return base.turnTimeSeconds(fromEdge, toEdge, streetMode);
    }
}