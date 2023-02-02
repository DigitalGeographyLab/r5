package com.conveyal.r5.streets;

import com.conveyal.r5.labeling.StreetClass;

/**
 * Translates the com.conveyal.r5.labeling.StreetClass (OSM tags) into Jaakkonen (2013)’s street classes,
 * which are based on ‘functional classes’ in the DigiRoad’s road classification,
 * see https://ava.vaylapilvi.fi/ava/Tie/Digiroad/Aineistojulkaisut/latest/Julkaisudokumentit
 * and
 */
public enum JaakkonenStreetClass {
    CLASS_1_2,
    CLASS_3,
    CLASS_4_5_6;
}

/*
    public static JaakkonenStreetClass fromR5StreetClass(StreetClass streetClass) {
        if(streetClass.equals(StreetClass.MOTORWAY) || streetClass.equals(StreetClass.PRIMARY)){
            return JaakkonenStreetClass.CLASS_1_2;
        } else if (streetClass.equals(StreetClass.SECONDARY)){
            return JaakkonenStreetClass.CLASS_3;
        } else if (streetClass.equals(StreetClass.TERTIARY) || streetClass.equals(StreetClass.OTHER)){
            return JaakkonenStreetClass.CLASS_4_5_6;
        } else {  // catch-all, not really necessary here?
            return JaakkonenStreetClass.CLASS_4_5_6;
        }
    }

}
*/