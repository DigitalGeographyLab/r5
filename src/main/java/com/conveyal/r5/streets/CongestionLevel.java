package com.conveyal.r5.streets;

import com.conveyal.r5.profile.ProfileRequest;

import java.time.DayOfWeek;

/**
 * These congestion levels relate to Jaakkonen (2013)’s assessment of crossing penalties in the Helsinki metropolitan area
 * See: <a href="http://urn.fi/URN:NBN:fi-fe2017112252365">...</a>, table 28 on page 61,
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

    public static CongestionLevel fromProfileRequest(ProfileRequest profileRequest) {
        // set the congestion level (rush hour/off peak) depending on week day and time of day
        if (profileRequest.date != null) {
            DayOfWeek dayOfWeek = profileRequest.date.getDayOfWeek();
            if (dayOfWeek.equals(DayOfWeek.SUNDAY) || dayOfWeek.equals(DayOfWeek.SATURDAY))
                return CongestionLevel.OFF_PEAK;
            else
                return CongestionLevel.fromFromTime(profileRequest.fromTime);
        } else
            return CongestionLevel.AVERAGE;
    }

}

