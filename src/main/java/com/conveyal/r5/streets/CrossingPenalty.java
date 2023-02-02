package com.conveyal.r5.streets;

import java.util.HashMap;

/**
 * Crossing penalties according to Jaakkonen (2013)’s analysis for the Helsinki metropolitan area
 * See: <a href="http://urn.fi/URN:NBN:fi-fe2017112252365">...</a>, table 28 on page 61,
 *
 * Note that delays are in integer seconds, as com.conveyal.r5.streets.BasicTraversalTimeCalculator.turnTimeSeconds
 * returns full seconds, only (probably a question of performance?)
 */
public class CrossingPenalty {
    public static final HashMap<CongestionLevel, HashMap<JaakkonenStreetClass, Integer>> CROSSING_PENALTIES = new HashMap<>();

    static {
        CROSSING_PENALTIES.put(
                CongestionLevel.AVERAGE, new HashMap<>() {
                    {
                        put(JaakkonenStreetClass.CLASS_1_2, 11 /*11.311*/);
                        put(JaakkonenStreetClass.CLASS_3, 9 /*9.439*/);
                        put(JaakkonenStreetClass.CLASS_4_5_6, 9 /*9.362*/);
                    }
                }
        );
        CROSSING_PENALTIES.put(
                CongestionLevel.OFF_PEAK, new HashMap<>() {
                    {
                        put(JaakkonenStreetClass.CLASS_1_2, 10 /*9.979*/);
                        put(JaakkonenStreetClass.CLASS_3, 7 /*6.650*/);
                        put(JaakkonenStreetClass.CLASS_4_5_6, 8 /*7.752*/);
                    }
                }
        );
        CROSSING_PENALTIES.put(
                CongestionLevel.RUSH_HOUR, new HashMap<>() {
                    {
                        put(JaakkonenStreetClass.CLASS_1_2, 12 /*12.195*/);
                        put(JaakkonenStreetClass.CLASS_3, 11 /*11.199*/);
                        put(JaakkonenStreetClass.CLASS_4_5_6, 11 /*10.633*/);
                    }
                }
        );
    }

    public static int getDelay(CongestionLevel congestionLevel, JaakkonenStreetClass jaakkonenStreetClass){
        return CROSSING_PENALTIES.get(congestionLevel).get(jaakkonenStreetClass);
    }
}
