package com.conveyal.r5.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.kryo.InstanceCountingClassResolver;
import com.conveyal.r5.kryo.TIntArrayListSerializer;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ExternalizableSerializer;
import com.esotericsoftware.kryo.serializers.JavaSerializer;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.conveyal.r5.analyst.fare.GreedyFareCalculator;
import com.conveyal.r5.profile.StreetMode;
import com.google.common.io.Files;
import com.vividsolutions.jts.geom.Envelope;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import gnu.trove.impl.hash.TIntHash;
import gnu.trove.list.array.TIntArrayList;
import org.mapdb.Fun;
import org.objenesis.strategy.SerializingInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.*;

/**
 * This is a completely new replacement for Graph, Router etc.
 * It uses a lot less object pointers and can be built, read, and written orders of magnitude faster.
 * @author abyrd
 */
public class TransportNetwork implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(TransportNetwork.class);

    public StreetLayer streetLayer;

    public TransitLayer transitLayer;

    /**
     * This stores any number of lightweight scenario networks built upon the current base network.
     * FIXME that sounds like a memory leak, should be a WeighingCache or at least size-limited.
     */
    public transient Map<String, TransportNetwork> scenarios = new HashMap<>();

    /**
     * A grid point set that covers the full extent of this transport network. The PointSet itself then caches linkages
     * to street networks (the baseline street network, or ones with various scenarios applied). If they have been
     * created, this point set and its linkage to the street network are serialized along with the network, which makes
     * startup much faster. Note that there's a linkage cache with references to streetlayers in this GridPointSet,
     * so you should usually only serialize a TransportNetwork right after it's built, when that cache contains only
     * the baseline linkage.
     */
    public WebMercatorGridPointSet gridPointSet;

    /**
     * Linkages are cached within GridPointSets. Guava caches serialize their configuration but not
     * their contents, which is actually pretty sane behavior for a cache. So if we want a particular linkage to be
     * available on reload, we have to store it in its own field.
     * TODO it would be more "normalized" to keep only this field, and access the unlinked gridPointSet via linkedGridPointSet.pointset.
     */
    public LinkedPointSet linkedGridPointSet;

    /**
     * A string uniquely identifying the contents of this TransportNetwork in the space of TransportNetworks.
     * When no scenario has been applied, this field will contain the original base networkId.
     * When a scenario has modified a base network to produce this network, this field will be changed to the
     * scenario ID. This allows proper caching of downstream data and results: we need a way to know what information
     * is in the network independent of object identity, and after a round trip through serialization.
     */
    public String scenarioId = null;

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    public GreedyFareCalculator fareCalculator;

    /** Non-fatal warnings encountered when applying the scenario, null on a base network */
    public List<TaskError> scenarioApplicationWarnings;


    /**
     * Factory method ensuring that we configure Kryo exactly the same way when saving and loading networks, without
     * duplicating code. We could explicitly register all classes in this method, which would avoid writing out the
     * class names the first time they are encountered and guarantee that the desired serialization approach was used.
     * Because these networks are so big though, pre-registration should provide very little savings.
     * Registration would be more important for small network messages.
     */
    private static Kryo makeKryo () {
        // Use custom ClassResolver to count instances and see what serializers they are using
        // Kryo kryo = new Kryo(new InstanceCountingClassResolver(), new MapReferenceResolver(), new DefaultStreamFactory());
        Kryo kryo = new Kryo();
        // Allow classes to be auto-associated with default serializers the first time they are seen.
        kryo.setRegistrationRequired(false);
        // Handle references and loops in the object graph, do not repeatedly serialize the same instance.
        kryo.setReferences(true);
        // Certain Trove class hierarchies are all Externalizable, defining their own efficient methods.
        // addDefaultSerializer will create a serializer instance for any subclass of the specified class.
        // Kryo's default serializers and instantiation strategies also don't seem to deal well with Trove Maps.
        kryo.addDefaultSerializer(TIntHash.class, ExternalizableSerializer.class);
        // We've got a custom serializer for primitive int array lists, because there are a lot of them and it's
        // much faster than deferring to their Externalizable implementation.
        kryo.register(TIntArrayList.class, new TIntArrayListSerializer());
        // Kryo default instantiation and deserialization of BitSets leaves them empty.
        // The Kryo BitSet serializer in magro/kryo-serializers naively writes out a dense stream of booleans.
        // BitSet's built-in Java serializer saves the internal bitfields, which is efficient.
        kryo.register(BitSet.class, new JavaSerializer());
        // Instantiation strategy: how should Kryo make new instances of objects when they are deserialized?
        // The default strategy requires every class you serialize, even in your dependencies, to have a zero-arg
        // constructor (which can be private).
        // Setting the instantiator strategy as follows completely replaces that default strategy:
        // kryo.setInstantiatorStrategy(new SerializingInstantiatorStrategy());
        // We instead want to specify a fallback strategy for the default strategy:
        kryo.setInstantiatorStrategy(new Kryo.DefaultInstantiatorStrategy(new SerializingInstantiatorStrategy()));
        return kryo;
    }

    public void write (File file) throws IOException {
        LOG.info("Writing transport network...");
        Output output = new Output(new FileOutputStream(file));
        makeKryo().writeClassAndObject(output, this);
        output.close();
        LOG.info("Done writing.");
    }

    public static TransportNetwork read (File file) throws Exception {
        LOG.info("Reading transport network...");
        Input input = new Input(new FileInputStream(file));
        TransportNetwork result = (TransportNetwork) makeKryo().readClassAndObject(input);
        input.close();
        LOG.info("Done reading.");
        if (result.fareCalculator != null) {
            result.fareCalculator.transitLayer = result.transitLayer;
        }

        // we need to put the linked grid pointset back in the linkage cache, as the contents of the linkage cache
        // are not saved by Guava. This accelerates the time to first result after a prebuilt network has been loaded
        // to just a few seconds even in the largest regions. However, currently only the linkage for walking is saved,
        // so there will still be a long pause at the first request for driving or cycling.
        // TODO just use a map for linkages? There should never be more than a handful per pointset, one for each mode.
        // and the pointsets themselves are in a cache, although it does not currently have an eviction method.
        if (result.gridPointSet != null && result.linkedGridPointSet != null) {
            result.gridPointSet.linkageCache
                    .put(new Fun.Tuple2<>(result.streetLayer, result.linkedGridPointSet.streetMode), result.linkedGridPointSet);
        }

        result.rebuildTransientIndexes();
        return result;
    }

    /**
     * Build some simple derived index tables that are not serialized with the network.
     * Distance tables and street spatial indexes are now serialized with the network.
     */
    public void rebuildTransientIndexes() {
        streetLayer.buildEdgeLists();
        streetLayer.indexStreets();
        transitLayer.rebuildTransientIndexes();
    }


    /** Create a TransportNetwork from gtfs-lib feeds */
    public static TransportNetwork fromFeeds (String osmSourceFile, List<GTFSFeed> feeds, TNBuilderConfig config) {
        return fromFiles(osmSourceFile, null, feeds, config);
    }

    /** Legacy method to load from a single GTFS file */
    public static TransportNetwork fromFiles (String osmSourceFile, String gtfsSourceFile, TNBuilderConfig tnBuilderConfig) throws DuplicateFeedException {
        return fromFiles(osmSourceFile, Arrays.asList(gtfsSourceFile), tnBuilderConfig);
    }

    /**
     * It would seem cleaner to just have two versions of this function, one which takes a list of strings and converts
     * it to a list of feeds, and one that just takes a list of feeds directly. However, this would require loading all
     * the feeds into memory simulataneously, which shouldn't be so bad with mapdb-based feeds, but it's still not great
     * (due to caching etc.)
     */
    private static TransportNetwork fromFiles (String osmSourceFile, List<String> gtfsSourceFiles, List<GTFSFeed> feeds,
                                               TNBuilderConfig tnBuilderConfig) throws DuplicateFeedException {

        System.out.println("Summarizing builder config: " + BUILDER_CONFIG_FILENAME);
        System.out.println(tnBuilderConfig);
        File dir = new File(osmSourceFile).getParentFile();

        // Create a transport network to hold the street and transit layers
        TransportNetwork transportNetwork = new TransportNetwork();

        // Load OSM data into MapDB
        OSM osm = new OSM(new File(dir,"osm.mapdb").getPath());
        osm.intersectionDetection = true;
        osm.readFromFile(osmSourceFile);

        // Make street layer from OSM data in MapDB
        StreetLayer streetLayer = new StreetLayer(tnBuilderConfig);
        transportNetwork.streetLayer = streetLayer;
        streetLayer.parentNetwork = transportNetwork;
        streetLayer.loadFromOsm(osm);
        osm.close();

        // The street index is needed for associating transit stops with the street network
        // and for associating bike shares with the street network
        streetLayer.indexStreets();

        if (tnBuilderConfig.bikeRentalFile != null) {
            streetLayer.associateBikeSharing(tnBuilderConfig);
        }

        // Load transit data TODO remove need to supply street layer at this stage
        TransitLayer transitLayer = new TransitLayer();

        if (feeds != null) {
            for (GTFSFeed feed : feeds) {
                transitLayer.loadFromGtfs(feed);
            }
        } else {
            for (String feedFile: gtfsSourceFiles) {
                GTFSFeed feed = GTFSFeed.fromFile(feedFile);
                transitLayer.loadFromGtfs(feed);
                feed.close();
            }
        }
        transportNetwork.transitLayer = transitLayer;
        transitLayer.parentNetwork = transportNetwork;
        // transitLayer.summarizeRoutesAndPatterns();

        // The street index is needed for associating transit stops with the street network.
        // FIXME indexStreets is called three times: in StreetLayer::loadFromOsm, just after loading the OSM, and here
        streetLayer.indexStreets();
        streetLayer.associateStops(transitLayer);
        // Edge lists must be built after all inter-layer linking has occurred.
        streetLayer.buildEdgeLists();
        transitLayer.rebuildTransientIndexes();

        // Create transfers
        new TransferFinder(transportNetwork).findTransfers();
        new TransferFinder(transportNetwork).findParkRideTransfer();

        transportNetwork.fareCalculator = tnBuilderConfig.analysisFareCalculator;

        if (transportNetwork.fareCalculator != null) transportNetwork.fareCalculator.transitLayer = transitLayer;

        return transportNetwork;
    }

    /**
     * OSM PBF files are fragments of a single global database with a single namespace. Therefore it is valid to load
     * more than one PBF file into a single OSM storage object. However they might be from different points in time, so
     * it may be cleaner to just map one PBF file to one OSM object.
     * On the other hand, GTFS feeds each have their own namespace. Each GTFS object is for one specific feed, and this
     * distinction should be maintained for various reasons. However, we use the GTFS IDs only for reference, so it
     * doesn't really matter, particularly for analytics.
     */
    public static TransportNetwork fromFiles (String osmFile, List<String> gtfsFiles, TNBuilderConfig config) {
        return fromFiles(osmFile, gtfsFiles, null, config);
    }

    public static TransportNetwork fromDirectory (File directory) throws DuplicateFeedException {
        File osmFile = null;
        List<String> gtfsFiles = new ArrayList<>();
        TNBuilderConfig builderConfig = null;
        //This can exit program if json file has errors.
        builderConfig = loadJson(new File(directory, BUILDER_CONFIG_FILENAME));
        for (File file : directory.listFiles()) {
            switch (InputFileType.forFile(file)) {
                case GTFS:
                    LOG.info("Found GTFS file {}", file);
                    gtfsFiles.add(file.getAbsolutePath());
                    break;
                case OSM:
                    LOG.info("Found OSM file {}", file);
                    if (osmFile == null) {
                        osmFile = file;
                    } else {
                        LOG.warn("Can only load one OSM file at a time.");
                    }
                    break;
                case DEM:
                    LOG.warn("DEM file '{}' not yet supported.", file);
                    break;
                case OTHER:
                    LOG.warn("Skipping non-input file '{}'", file);
            }
        }
        if (osmFile == null) {
            LOG.error("An OSM PBF file is required to build a network.");
            return null;
        } else {
            return fromFiles(osmFile.getAbsolutePath(), gtfsFiles, builderConfig);
        }
    }

    /**
     * Open and parse the JSON file at the given path into a Jackson JSON tree. Comments and unquoted keys are allowed.
     * Returns default config if the file does not exist,
     * Returns null if the file contains syntax errors or cannot be parsed for some other reason.
     * <p>
     * We do not require any JSON config files to be present because that would get in the way of the simplest
     * rapid deployment workflow. Therefore we return default config if file does not exist.
     */
    static TNBuilderConfig loadJson(File file) {
        try (FileInputStream jsonStream = new FileInputStream(file)) {
            TNBuilderConfig config = JsonUtilities.objectMapper
                .readValue(jsonStream, TNBuilderConfig.class);
            config.fillPath(file.getParent());
            LOG.info("Found and loaded JSON configuration file '{}'", file);
            return config;
        } catch (FileNotFoundException ex) {
            LOG.info("File '{}' is not present. Using default configuration.", file);
            return TNBuilderConfig.defaultConfig();
        } catch (Exception ex) {
            LOG.error("Error while parsing JSON config file '{}': {}", file, ex.getMessage());
            System.exit(42); // probably "should" be done with an exception
            return null;
        }
    }

    /**
     * Opens OSM MapDB database if it exists
     * Otherwise it prints a warning
     *
     * OSM MapDB is used for names of streets. Since they are needed only in display of paths.
     * They aren't saved in street layer.
     * @param file
     */
    public void readOSM(File file) {
        if (file.exists()) {
            streetLayer.openOSM(file);
        } else {
            LOG.info("osm.mapdb doesn't exist in graph folder. This means that street names won't be shown");
        }
    }

    /**
     * Represents the different types of files that might be present in a router / graph build directory.
     * We want to detect even those that are not graph builder inputs so we can effectively warn when unrecognized file
     * types are present. This helps point out when config files have been misnamed.
     */
    private static enum InputFileType {
        GTFS, OSM, DEM, CONFIG, OUTPUT, OTHER;
        public static InputFileType forFile(File file) {
            String name = file.getName();
            if (name.endsWith(".zip")) {
                try {
                    ZipFile zip = new ZipFile(file);
                    ZipEntry stopTimesEntry = zip.getEntry("stop_times.txt");
                    zip.close();
                    if (stopTimesEntry != null) return GTFS;
                } catch (Exception e) { /* fall through */ }
            }
            if (name.endsWith(".pbf") || name.endsWith(".vex")) return OSM;
            if (name.endsWith(".tif") || name.endsWith(".tiff")) return DEM; // Digital elevation model (elevation raster)
            if (name.endsWith("network.dat")) return OUTPUT;
            return OTHER;
        }
    }

    /**
     * Build an efficient implicit grid PointSet for this TransportNetwork if it doesn't already exist. Then link that
     * grid pointset to the street layer. This is called when a network is built for analysis purposes, and also after a
     * scenario is applied to rebuild the grid pointset on the scenario copy of the network.
     *
     * This grid PointSet will cover the entire street network layer of this TransportNetwork, which should include
     * every point we can route from or to. Any other destination grid (for the same mode, walking) can be made as a
     * subset of this one since it includes every potentially accessible point.
     */
    public void rebuildLinkedGridPointSet() {
        if (gridPointSet == null) {
            gridPointSet = new WebMercatorGridPointSet(this);
        }
        // Here we are bypassing the GridPointSet's internal cache of linkages because we want this particular
        // linkage to be serialized with the network. The internal Guava cache does not serialize its contents (by design).
        linkedGridPointSet = new LinkedPointSet(gridPointSet, streetLayer, StreetMode.WALK, linkedGridPointSet);
    }

    //TODO: add transit stops to envelope
    public Envelope getEnvelope() {
        return streetLayer.getEnvelope();
    }

    /**
     * Gets timezone of this transportNetwork
     *
     * If transitLayer exists returns transitLayer timezone otherwise GMT
     *
     * It is never null
     * @return TransportNetwork timezone
     */
    public ZoneId getTimeZone() {
        if (transitLayer == null) {
            LOG.warn("TransportNetwork transit layer isn't loaded; API request times will be interpreted as GMT.");
            return ZoneId.of("GMT");
        } else if (transitLayer.timeZone == null) {
            LOG.error(
                "TransportNetwork transit layer is loaded but timezone is unknown; API request times will be interpreted as GMT.");
            return ZoneId.of("GMT");
        } else {
            return transitLayer.timeZone;
        }
    }

    /**
     * Apply the given scenario to this TransportNetwork, copying any elements that are modified to leave the original
     * unscathed. The scenario may be null or empty, in which case this method is a no-op.
     */
    public TransportNetwork applyScenario (Scenario scenario) {
        if (scenario == null || scenario.modifications.isEmpty()) {
            return this;
        }
        return scenario.applyToTransportNetwork(this);
    }

    /**
     * We want to apply Scenarios to TransportNetworks, yielding a new TransportNetwork without disrupting the original
     * one. The approach is to make a copy of the TransportNetwork, then apply all the Modifications in the Scenario one
     * by one to that same copy. Two very different modification strategies are used for the TransitLayer and the
     * StreetLayer. The TransitLayer has a hierarchy of collections, from patterns to trips to stoptimes. We can
     * selectively copy-on-modify these collections without much impact on performance as long as they don't become too
     * large. This is somewhat inefficient but easy to reason about, considering we allow both additions and deletions.
     * We don't use clone() here with the expectation that it will be more clear and maintainable to show exactly how
     * each field is being copied. On the other hand, the StreetLayer contains a few very large lists which would be
     * wasteful to copy. It is duplicated in such a way that it wraps the original lists, allowing them to be
     * non-destructively extended. There will be some performance hit from wrapping these lists, but it's probably
     * negligible.
     *
     * @return a copy of this TransportNetwork that is partly shallow and partly deep.
     */
    public TransportNetwork scenarioCopy(Scenario scenario) {
        // Maybe we should be using clone() here but TransportNetwork has very few fields and most are overwritten.
        TransportNetwork copy = new TransportNetwork();
        // It is important to set this before making the clones of the street and transit layers below.
        copy.scenarioId = scenario.id;
        copy.gridPointSet = this.gridPointSet;
        copy.linkedGridPointSet = this.linkedGridPointSet;
        copy.transitLayer = this.transitLayer.scenarioCopy(copy, scenario.affectsTransitLayer());
        copy.streetLayer = this.streetLayer.scenarioCopy(copy, scenario.affectsStreetLayer());
        copy.fareCalculator = this.fareCalculator;
        return copy;
    }

    /**
     * @return a checksum of the graph, for use in verifying whether it changed or remained the same after
     * some operation.
     */
    public long checksum () {
        LOG.info("Calculating transport network checksum...");
        try {
            File tempFile = File.createTempFile("r5-network-checksum-", ".dat");
            tempFile.deleteOnExit();
            this.write(tempFile);
            HashCode crc32 = Files.hash(tempFile, Hashing.crc32());
            tempFile.delete();
            LOG.info("Network CRC is {}", crc32.hashCode());
            return crc32.hashCode();
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }

}
