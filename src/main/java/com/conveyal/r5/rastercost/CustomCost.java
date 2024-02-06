package com.conveyal.r5.rastercost;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;

/**
 * CustomCost helper for Greenpaths2 custom cost multi-objective routing
 * create a separate class for modularity and transparency
 * and distinguishing the custom cost logic from the rest of the base code
 * 
 * Also this separate class can be helpful if we want to defreeze the R5 version
 * 
 * note: for further development geometries could also be fethed from the router state, but it is not implemented yet
 * would need refactoring e.g. getting edgeIndex list separately, renamings, new method for getting geometries
 * flag for if should get geometries or not in the calling method. Dont forget tests.
 *
 * Created by roope on 11.10.2023.
 */

/* GP2 edit: create this class for handling custom cost logic */
public class CustomCost {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeComputer.class);
    
    /*
    * Get the osmIds from the router state
    * this is used in many-to-many or one-to-may matrices routing e.g. in TravelTimeComputer
    * these osmIds are needed for exposure based routing
    * they are combined by "osmid" with separately calculated exposure values
    * e.g. we calculate a dict of {osmid: exposure values}, run the matrix routing and then 
    * see what osmid's we traversed and get the result by accessing the dict with the osmid as the key
    * 
    */
    public static List<List<Long>> getOsmIdsFromRouterState(PointSetTimes nonTransitTravelTimesToDestinations, StreetRouter sr, TransportNetwork network) {
        if(nonTransitTravelTimesToDestinations == null) return null;

        // create a list of lists for osmIds
        // populate with empty lists
        // the first list will indicate the point index, the nested list has list of osmids created from StreetRouter.State and StreetPath
        List<List<Long>> osmIdResults = Stream.generate(ArrayList<Long>::new)
                                        .limit(nonTransitTravelTimesToDestinations.size())
                                        .collect(Collectors.toList());

        // loop for each destination point in the matrix grid (i.e. the pointset)
        for(var i = 0; i < nonTransitTravelTimesToDestinations.size(); i++) {
            // get the lat and lon of the destination point
            double destPointLat = nonTransitTravelTimesToDestinations.pointSet.getLat(i);
            double destPointLon = nonTransitTravelTimesToDestinations.pointSet.getLon(i);

            // get street router state using the current destination lat and lon
            StreetRouter.State lastState = sr.getState(destPointLat, destPointLon);
            if (lastState == null) {
                // skip the point if lat or lon is 0 or no state is found
                continue;
            }
            // create a street path using the state, used for looping all edges from the path
            StreetPath streetPath = new StreetPath(lastState, network, false);
            // get the all the edge indexes from the path
            LinkedList<Integer> pathEdges = streetPath.getEdges();
            // initialize empty list for osmIds
            LinkedList<Long> edgeOsmIdsForPath = new LinkedList<>();
            // loop through all the edges in the path traversed and get osmids from the edge store
            for (Integer edgeIdx : pathEdges) {
                EdgeStore.Edge edge = network.streetLayer.edgeStore.getCursor(edgeIdx);
                edgeOsmIdsForPath.add(edge.getOSMID());
            }
            // remove dublicate osmIds
            // the router splits the osm ways into node-node segments, so the same osmId can be found multiple times
            // because all child segments have the original parent osmId
            // this all happens because osm ways natively can and most likely will extend over multiple nodes
            List<Long> uniqueEdgeOsmIdsForPath = edgeOsmIdsForPath.stream().distinct().collect(Collectors.toList());
            // replace the empty populated list with the unique list if any osmids are found
            if (!uniqueEdgeOsmIdsForPath.isEmpty()) {
                osmIdResults.set(i, uniqueEdgeOsmIdsForPath);
            }
        }
        // TODO: is returning empty lists sufficient? or should we return null?
        // note that returning null and yielding error will crash the routing
        return osmIdResults;
    }
}
