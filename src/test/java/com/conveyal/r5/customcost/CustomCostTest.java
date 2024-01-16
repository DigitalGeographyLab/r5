package com.conveyal.r5.customcost;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.network.GridLayout;
import com.conveyal.r5.rastercost.CustomCostField;
import com.conveyal.r5.rastercost.CustomCostFieldException;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.ReverseRoutingTest;
import com.conveyal.r5.transit.TransportNetwork;

import static com.conveyal.r5.analyst.network.SimpsonDesertTests.SIMPSON_DESERT_CORNER;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/* GP2 edit: added tests for custom cost logic */
public class CustomCostTest {
    private static final Logger LOG = LoggerFactory.getLogger(ReverseRoutingTest.class);

    HashMap<Long, Double> staticCustomCostHashMap;

    @BeforeEach
    public void setUp () throws Exception {
        staticCustomCostHashMap = generateCustomCostHashMap();
    }

    // this is used in places where no customised cost map is needed
    public static HashMap<Long, Double> generateCustomCostHashMap() {
        HashMap<Long, Double> osmIdMap = new HashMap<>();
        // put a known value as the first in the hashmap
        osmIdMap.put(123123L, 7.0);
        Random rand = new Random();
        for (int i = 0; i < 10; i++) {
            long osmId = i;
            double randomValue = rand.nextDouble();

            osmIdMap.put(osmId, randomValue);
        }

        return osmIdMap;
    }

    @Test
    public void testCreateValidCustomCostField () {
        CustomCostField customCostInstance = new CustomCostField("testKey", 3, staticCustomCostHashMap, false);
        double targetValue = staticCustomCostHashMap.get(123123L);
        assertEquals(customCostInstance.getDisplayKey(), "testKey");
        assertEquals(targetValue, 7.0);
        assertEquals(staticCustomCostHashMap.size() > 0, true);
    }

    @Test
    public void testCreateInvalidCustomCostField () {
        HashMap<Long, Double> emptyHashmap = new HashMap<>();
        // assertThrows(IllegalArgumentException.class, );
        assertThrows(IllegalArgumentException.class, () -> new CustomCostField("testKey", 3, emptyHashmap, false));
        assertThrows(IllegalArgumentException.class, () -> new CustomCostField("testKey", 3, null, false));
    }

    /**
     * Got the grid layout and the test from SimpsonDesertTests.java
     */
    @ParameterizedTest
    @MethodSource("customCostTestParameterProvider")
    public void testRoutingWithCustomCosts(boolean allowNullCustomCostEdges) { 
        // this test could be made more robust by:
        // 1. use different methods of creating the network and the task
        // which would let us see that the times have increased the amount we set
        // the custom cost per osmId to it

        // currently this test is sufficient, it shows that adding custom costs to the network
        // are changing the correct amount of travel times and that the custom cost components are working

        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 6);
        TransportNetwork Network = gridLayout.generateNetwork();
  
        // take osmIds that the network has
        // if allowNullCustomCostEdges is true, take only 1/2 of osids for testing missing osmids
        // add them to a list and make sure that they exist
        List<Long> uniqueOsmIds = getOsmIds(Network, allowNullCustomCostEdges);
        HashMap<Long, Double> customCostHashMap = new HashMap<>();
        // add the acquired osmIds to a hashmap with random values
        for (Long osmId : uniqueOsmIds) {
            // add just a small increate in traveltime for not 
            // making the vertices unreachable
            customCostHashMap.put(osmId, 0.25);
        }

        // add the hashmap as the customCostHashMap to the customCostInstance
        CustomCostField customCostInstance = new CustomCostField("testKey", 2, customCostHashMap, allowNullCustomCostEdges);
        Network.streetLayer.edgeStore.costFields = CustomCostField.wrapToEdgeStoreCostFieldsList(customCostInstance);


        // build the task from the grid, example taken from SimpsonDesertTests.java
        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .setOrigin(2, 2)
                .singleFreeformDestination(5, 3)
                .uniformOpportunityDensity(2)
                .monteCarloDraws(1)
                .build();

        List<CustomCostField> customCostFieldsList = Network.streetLayer.edgeStore.costFields.stream()
            .map(CustomCostField.class::cast)
            .collect(Collectors.toList());

        assertTrue(customCostFieldsList.size() > 0);

        // assert that all the customCostFields have empty baseTraveltimesMap
        for (CustomCostField customCostField : customCostFieldsList) {
            assertTrue(customCostField.getBaseTraveltimes().size() == 0);
        }

        TravelTimeComputer computer = new TravelTimeComputer(task, Network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();


        assert(oneOriginResult != null);
        assertTrue(oneOriginResult.osmIdResults.size() > 0);
        assertTrue(oneOriginResult.travelTimes.nPoints > 0);

        // test that the routing result osmId has valid osmIds which are found from the network
        // test for some valid if using partial osmids, test for allMatch if using all osmids
        if(allowNullCustomCostEdges) {
            // Check if any OSM ID from the results is in the subset
            boolean hasSomeValidOsmIdsFoundFromNetwork = oneOriginResult.osmIdResults.stream()
                .flatMap(List::stream)
                .anyMatch(uniqueOsmIds::contains);
            assert(hasSomeValidOsmIdsFoundFromNetwork);
        }
        else {
            boolean hasOnlyValidOsmIdsFoundFromNetwork = oneOriginResult.osmIdResults.stream()
                .allMatch(innerList -> innerList.stream().allMatch(uniqueOsmIds::contains));
            assertTrue(hasOnlyValidOsmIdsFoundFromNetwork);
        }

        int [][] travelTimeValues = oneOriginResult.travelTimes.getValues();

        // flatten the two dimensional array to a list
        List<Integer> CustomCostTravelTimes = Arrays.stream(travelTimeValues)
                           .flatMapToInt(Arrays::stream)
                           .boxed()
                           .collect(Collectors.toList());


        // REMOVE COSTFIELDS FROM NETWORK AND RUN ROUTING FOR COMPARISON
        // copy network to new variable
        TransportNetwork NetworkNoCustomCosts = Network;
        NetworkNoCustomCosts.streetLayer.edgeStore.costFields = null;
        TravelTimeComputer computerNoCustomCosts = new TravelTimeComputer(task, NetworkNoCustomCosts);
        OneOriginResult oneOriginResultNoCustomCosts = computerNoCustomCosts.computeTravelTimes();

        assertTrue(oneOriginResultNoCustomCosts != null);
        // check that no osmIdResults found for they are not created when there are no customCosts
        assertTrue(oneOriginResultNoCustomCosts.osmIdResults == null);
        assertTrue(oneOriginResultNoCustomCosts.travelTimes.nPoints > 0);

        int [][] travelTimeValuesNoCustomCost = oneOriginResultNoCustomCosts.travelTimes.getValues();

        List<Integer> DefaultRoutingNoCustomCosts = Arrays.stream(travelTimeValuesNoCustomCost)
                           .flatMapToInt(Arrays::stream)
                           .boxed()
                           .collect(Collectors.toList());

        // assert before and after removing unreachable vertices values
        assertTrue(CustomCostTravelTimes.size() == DefaultRoutingNoCustomCosts.size());
        assertTrue(CustomCostTravelTimes.size() > 0);
        assertTrue(CustomCostTravelTimes.size() == DefaultRoutingNoCustomCosts.size());
        assertTrue(!CustomCostTravelTimes.equals(DefaultRoutingNoCustomCosts));

        boolean allValuesIncreasedOrSame = IntStream.range(0, CustomCostTravelTimes.size())
        .allMatch(i -> CustomCostTravelTimes.get(i) >= DefaultRoutingNoCustomCosts.get(i));

        // check that customCost value is always same or bigger, never smaller
        assertTrue(allValuesIncreasedOrSame);

        // test that the custom cost values are correctly applied
        // and that the base time and custom cost "time" maps have correct values

        assertTrue(customCostFieldsList.size() > 0);

        // check that all the customCostFields have non-empty baseTraveltimesMap
        // and uniqueOsmIds size is the same and all the osmIds are found from the map
        // for they are were traversed so they should be present
        for (CustomCostField customCostField : customCostFieldsList) {
            HashMap<Long, Integer> baseTraveltimesMap = customCostField.getBaseTraveltimes();
            assertTrue(baseTraveltimesMap.size() > 0);
            if(!allowNullCustomCostEdges) {
                assertTrue(baseTraveltimesMap.size() == uniqueOsmIds.size());
                assertTrue(baseTraveltimesMap.keySet().stream().allMatch(uniqueOsmIds::contains));
            }
            else {
                assertTrue(baseTraveltimesMap.size() > uniqueOsmIds.size());
                assertTrue(baseTraveltimesMap.keySet().stream().anyMatch(uniqueOsmIds::contains));
            }
        }

        // check that the maps have correct values and that we get same values from the map and the manual calculation
        for (CustomCostField customCostField : customCostFieldsList) {
            HashMap<Long, Integer> baseTraveltimesMap = customCostField.getBaseTraveltimes();
            HashMap<Long, Integer> customCostAdditionalTravelTimesMap = customCostField.getcustomCostAdditionalTraveltimes();
            for (Long osmId : uniqueOsmIds) {
                // calculate cost manually
                int baseTraveltime = baseTraveltimesMap.get(osmId);
                double customCostFactor = customCostHashMap.get(osmId);
                double sensitivity = customCostField.getSensitivityCoefficient();
                // custom cost additional seconds added to base travel time
                int additionalCostSeconds = (int) Math.round(baseTraveltime * customCostFactor * sensitivity);
                // final travel time with custom cost
                int finalManuallyCalculatedCost = baseTraveltime + additionalCostSeconds;
                // use maps to get the final travel time with custom cost using osmId as key
                int customCostAdditionalTraveltimeCost = customCostAdditionalTravelTimesMap.get(osmId);
                // final travel time with custom cost from maps
                int customCostFinalTraveltimeCostFromMaps = baseTraveltime + customCostAdditionalTraveltimeCost;
                // see that manually calculated value match with maps values
                assertEquals(finalManuallyCalculatedCost, customCostFinalTraveltimeCostFromMaps);
            }
        }
    }

    // use parameterization for testing allowNullCustomCostEdges flag
    // for all osmids and just some osmids
    static Stream<Arguments> customCostTestParameterProvider() {
        return Stream.of(
            // run with for all osmIds
            Arguments.of(false),
            // run with some osmIds
            Arguments.of(true)
        );
    }

    public List<Long> getOsmIds(TransportNetwork network, boolean getOnlyPartialOsmidList) {
        List<Long> osmIds = new ArrayList<>();
        EdgeStore.Edge e = network.streetLayer.edgeStore.getCursor(0);
        do {
            osmIds.add(e.getOSMID());
        } while (e.advance());

        List<Long> uniqueOsmIds = osmIds.stream().distinct().collect(Collectors.toList());

        assertTrue(uniqueOsmIds.size() > 0);

        // get only 1/3 of all osmids
        // used to test flag allowNullCustomCostEdges
        if (getOnlyPartialOsmidList) {
            uniqueOsmIds = uniqueOsmIds.subList(0, uniqueOsmIds.size() / 2);
        }
      
        return uniqueOsmIds;
    }

    @Test
    public void testInvalidCustomCostFactorsOsmIdNotFound() { 
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 6);
        TransportNetwork Network = gridLayout.generateNetwork();
        CustomCostField customCostInstance = new CustomCostField("testKey", 1.5, staticCustomCostHashMap, false);
        Network.streetLayer.edgeStore.costFields = CustomCostField.wrapToEdgeStoreCostFieldsList(customCostInstance);

        // build the task from the grid, example taken from SimpsonDesertTests.java
        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .setOrigin(2, 2)
                .singleFreeformDestination(5, 3)
                .uniformOpportunityDensity(2)
                .monteCarloDraws(1)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, Network);

        // make sure that invalid customCostFactors throws CustomCostFieldException
        Exception exception = assertThrows(CustomCostFieldException.class, () -> {
            computer.computeTravelTimes();
        });
        
        assertTrue(exception.getMessage().contains("Custom cost not found for edge with osmId:"));
    }
}
