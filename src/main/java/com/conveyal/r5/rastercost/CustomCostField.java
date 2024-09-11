package com.conveyal.r5.rastercost;
import com.conveyal.r5.streets.EdgeStore;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * CustomCost for enabling Greenpaths2 flexible custom costs
 * This class should be utilized in r5py in the following way:
 * 
 * 1) initialize/calculate the custom cost map (e.g. from raster data)
 * which is a dict of {osmid: value} in seconds added to the base traversal time.
 * Notice that the python dict type of custom cost map needs to be converted to Java HashMap
 * 
 * 2) initialize the CustomCostField with the custom cost map and sensitivity coefficient (default 1.0)
 * ! currently the sensitivity coefficient can also be a negative number
 * but the sum of basetraversaltime + all cost fields cant be less than 1 
 * 
 * 3) add list customCost instances to the TransportNetwork.
 * List because EdgeStore's costFields is a list of CostField instances
 * also this way we can have multiple custom cost fields if wanted
 * 
 * 4) run the routing process. In the routing logic each edge will be checked for the custom cost as:
*      - go throught each edge to see how long it takes to traverse
 *     - use MultistageTraversalTimeCalculator if EdgeStore has costFields
 *     - when MultistageTraversalTimeCalculator is used, routing will add a custom cost on top of the 
 *       base traversal time for each edge. This cost is calculated using this classes additionalTraversalTimeSeconds.
 *       This method will get the osmId of the current edge and use it to get the custom cost from the custom cost hashmap
 *       using the osmid as the key. If no custom cost is found, 0 is used as default
 *       for custom cost addition, which is equivalent to no custom cost.
 * 
 *      !Note: this process is adding the custom costs as seconds on top of the base traversal time. So e.g. in matrix
 *      traveltimes the times include the additional "cost" which in this context is seconds. So actually the 
 *      custom costs are seconds. The amount of additional seconds has to be separately calculated
 *      in a "preprocessing" phase which produces the custom cost map.
 * 
 * Created by roope on 11.10.2023.
 */

/* GP2 edit: create this class to be used in Greenpaths custom cost exposure routing */
public class CustomCostField implements CostField, Serializable {

    /** 
    * displayKey currently not really used, implemented due to CostField interface
    *
    * this could be used for displaying the custom cost field name e.g.
    * if we use multiple custom cost fields, add the names to the return values
    * which could in python side be added to the result df to see which custom cost fields were used
    */
    private final String displayKey;

    // define the sensitivity coefficient for the custom cost field
    // currently also supports negative number
    // this is needed for deriving multiple routes with different weights to custom cost
    private double sensitivityCoefficient;

    // CustomCostFactors are used to calculate the custom cost addition "cost seconds" 
    // consists of osmId as the key and custom cost factor as value
    private HashMap<Integer, Double> customCostFactors = null;

    // Flag for allowing null's for custom cost edges.
    // If this is false the routing will force custom cost values for each edge so
    // the rouiting will crash if no value found for each edge traversed
    // if set to true, the processed edge will get a custom cost value of 0
    private boolean allowNullCustomCostEdges;

    /** 
    * base edge travel times by osmid
    * these costs are without the custom cost addition
    *
    * Key is osmId, value is base traversal time in seconds
    */
    private HashMap<Integer, Integer> baseTraveltimes = new HashMap<Integer, Integer>();

    /**
     * custom cost travel times by osmid
     * these costs are the custom cost addition costs which are added to the base traversal time
     * they are treated as seconds but are actually costs or "cost seconds"
     * these values are calculaeted using formula: basetraveltime * customcostfactor * sensitivitycoefficient
     * and then added to the base traversal time for each edge
     * 
     * Key is osmId, value is addition custom cost time as costs (seconds)
     */

    // currently not used in GP2, but could be used for debugging or analysis
    private HashMap<Integer, Integer> customCostAdditionalTraveltimes = new HashMap<Integer, Integer>();


/**
 * 
 * @param displayKey
 * usefull when using multiple custom cost instances for naming
 * also implemented due to CostField interface
 * @param sensitivityCoefficient
 * sensitivityCoefficient can currently also be a negative number
 * the only restricting factor is that the routing logic in MultistageTraversalTimeCalculator
 * will fallback to a positive number 1 if the sum of basetraversaltime + all custom cost found for that edge
 * will be less than 1. This is because the routing logic will not allow negative traversal times.
 * @param customCostFactors
 * customCostFactors has osmId as the key and the custom cost seconds as the value
 * @param allowNullCustomCostEdges
 * (optional) flag for allowing or disallowing null costs for osmids in the customCostFactors
 * if this is false, every edge/osmid key needs to have a value in the HashMap, otheriwse will throw error
 * if true, will just fallback to 0 additionalSeconds for each edge where customCost value not found
 */
    public CustomCostField (String displayKey, double sensitivityCoefficient, HashMap<Integer, Double> customCostFactors, boolean allowNullCustomCostEdges) {
        validateCustomCostFactors(customCostFactors);
        this.sensitivityCoefficient = sensitivityCoefficient;
        this.displayKey = displayKey;
        this.allowNullCustomCostEdges = allowNullCustomCostEdges;
    }

    // Overloaded constructor with allowNullCustomCostEdges set to false
    public CustomCostField(String displayKey, double sensitivityCoefficient, HashMap<Integer, Double> customCostFactors) {
        this(displayKey, sensitivityCoefficient, customCostFactors, false);
    }

    /**
     * Check that the custom cost map is not empty, do we need more validation?
     */
    private void validateCustomCostFactors(HashMap<Integer, Double> customCostFactors) {
        if (customCostFactors == null || customCostFactors.isEmpty()) {
            throw new IllegalArgumentException("Custom cost map can't be empty when initializing CustomCostField");
        }
        this.customCostFactors = customCostFactors;
    }

    /**
     * Override method for adding custom cost seconds to the base traversal time
     * 
     * This approach of adding custom cost seconds on top of the base traversal time
     * makes the travel times not actual second times but rather "cost" times
     */
    @Override
    public int additionalTraversalTimeSeconds (EdgeStore.Edge currentEdge, int baseTraversalTimeSeconds) {
        // get the <long> osmId of the current edge
        int keyOsmId = currentEdge.getOSMID();
        // save the base traversal seconds
        if (!baseTraveltimes.containsKey(keyOsmId)) {
            baseTraveltimes.put(keyOsmId, baseTraversalTimeSeconds);
        }
        // get the custom cost factor from the custom cost map using the edgeas osmId as key
        Object customCostValue = this.customCostFactors.get(keyOsmId);
        // if customCostValue from HashMap was null and flag allowNullcustomCostEdges is false
        if (customCostValue == null && !this.allowNullCustomCostEdges) {
            throw new CustomCostFieldException("Custom cost not found for edge with osmId: " + currentEdge.getOSMID() + ". If some null values are expected, set allowNullCustomCostEdges to true");
        }
        // set additionalCostSeconds to 0.0 as default
        Double additionalCostSeconds = 0.0;
        // calculate the additionalCostSeconds for edge if customCostValue is found
        if (customCostValue != null) {
            // cast to Double if needed
            Double customCostFactor;
            if (customCostValue instanceof Integer) {
                customCostFactor = ((Integer) customCostValue).doubleValue();
            } else if (customCostValue instanceof Double) {
                customCostFactor = (Double) customCostValue;
            } else {
                throw new CustomCostFieldException("Unexpected type (should be number) for custom cost with osmId: " + currentEdge.getOSMID());
            }   
            // calculate seconds to be added to the base traversal time 
            // multiply the base travel time with custom cost factor and sensitivity coefficient
            // this value is then added to the base traversal time
            additionalCostSeconds = baseTraversalTimeSeconds * customCostFactor * this.sensitivityCoefficient;
        }
        // round the additionalCostSeconds to int
        int roundedAdditionalCostSeconds = (int) Math.round(additionalCostSeconds);
        // save the custom cost addition costs
        customCostAdditionalTraveltimes.put(keyOsmId, roundedAdditionalCostSeconds);

        return roundedAdditionalCostSeconds;
    }

    // currently not really used, implemented due to CostField interface
    @Override
    public String getDisplayKey () {
        return this.displayKey;
    }

    // currently not really used, implemented due to CostField interface
    @Override
    public double getDisplayValue (int osmIdKey) {
        // will need this conversation because implementation needs int but key is long
        return this.customCostFactors.get(osmIdKey);
    }

    public double getSensitivityCoefficient() {
        return sensitivityCoefficient;
    }

    public HashMap<Integer, Double> getCustomCostFactors() {
        return customCostFactors;
    }

    public HashMap<Integer, Integer> getBaseTraveltimes() {
        return baseTraveltimes;
    }

    public HashMap<Integer, Integer> getcustomCostAdditionalTraveltimes() {
        return customCostAdditionalTraveltimes;
    }

    public void resetBaseTraveltimes() {
        this.baseTraveltimes = new HashMap<Integer, Integer>();
    }

    // convert 1-n custom cost fields to a list of custom cost fields
    public static List<CostField> wrapToEdgeStoreCostFieldsList(CostField... customCostFields) {
        return new ArrayList<>(Arrays.asList(customCostFields));
    }

}
