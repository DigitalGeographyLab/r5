package com.conveyal.r5.streets;

import com.conveyal.r5.labeling.StreetClass;

/**
 * Translates the com.conveyal.r5.labeling.StreetClass (OSM tags) into Jaakkonen (2013)’s street classes,
 * which are based on ‘functional classes’ in the DigiRoad’s road classification,
 * see <a href="https://ava.vaylapilvi.fi/ava/Tie/Digiroad/Aineistojulkaisut/latest/Julkaisudokumentit">...</a>
 * and
 */
public enum JaakkonenStreetClass {
    CLASS_1_2,
    CLASS_3,
    CLASS_4_5_6;

    public static JaakkonenStreetClass fromR5StreetClassCode(Byte streetClassCode) {
        if(streetClassCode.equals(StreetClass.MOTORWAY.code) || streetClassCode.equals(StreetClass.PRIMARY.code)){
            return JaakkonenStreetClass.CLASS_1_2;
        } else if (streetClassCode.equals(StreetClass.SECONDARY.code)){
            return JaakkonenStreetClass.CLASS_3;
        } else if (streetClassCode.equals(StreetClass.TERTIARY.code) || streetClassCode.equals(StreetClass.OTHER.code)){
            return JaakkonenStreetClass.CLASS_4_5_6;
        } else {  // catch-all, not really necessary here?
            return JaakkonenStreetClass.CLASS_4_5_6;
        }
    }

}
