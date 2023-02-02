package com.conveyal.r5.streets;

/**
 * These congestion levels relate to Jaakkonen (2013)’s assessment of crossing penalties in the Helsinki metropolitan area
 * See: http://urn.fi/URN:NBN:fi-fe2017112252365, table 28 on page 61,
 */
public enum CongestionLevel {
    RUSH_HOUR,
    OFF_PEAK,
    AVERAGE;

    public static CongestionLevel fromFromTime(int secondsSinceMidnight) {
        /*
         * based on https://www.tomtom.com/traffic-index/helsinki-traffic/
         */
        if (secondsSinceMidnight < 25_200)  // 7:00
            return CongestionLevel.OFF_PEAK;
        else if (secondsSinceMidnight < 36_000)  // 10:00
            return CongestionLevel.RUSH_HOUR;
        else if (secondsSinceMidnight < 50_400)  // 14:00
            return CongestionLevel.AVERAGE;
        else if (secondsSinceMidnight < 64800)  // 18:00
            return CongestionLevel.RUSH_HOUR;
        else
            return CongestionLevel.OFF_PEAK;
    }

}

