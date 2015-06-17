package com.conveyal.osmlib;

import com.conveyal.osmlib.serializer.NodeSerializer;
import com.conveyal.osmlib.serializer.WaySerializer;
import org.mapdb.BTreeKeySerializer;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Fun.Tuple3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.NavigableSet;

/**
 * osm-lib representation of a subset of OpenStreetMap. One or more OSM files (e.g. PBF) can be loaded into this
 * object, which serves as a simple in-process database for fetching and iterating over OSM elements.
 * Using DB TreeMaps is often not any slower than memory. HashMaps are both bigger and slower because our keys
 * are so small: a hashmap needs to store both the long key and its hash.
 *
 * FIXME rename this to OSMStorage or OSMDatabase
 */
public class OSM implements OSMEntitySource, OSMEntitySink {

    private static final Logger LOG = LoggerFactory.getLogger(OSM.class);

    public Map<Long, Node> nodes;
    public Map<Long, Way> ways;
    public Map<Long, Relation> relations;

    /** A tile-based spatial index. */
    public NavigableSet<Tuple3<Integer, Integer, Long>> index; // (x_tile, y_tile, wayId)

    /** The nodes that are referenced at least once by ways in this OSM. */
    NodeTracker referencedNodes = new NodeTracker();

    /** The nodes which are referenced more than once by ways in this OSM. */
    NodeTracker intersectionNodes = new NodeTracker();

    /** The MapDB backing this OSM, if any. */
    DB db = null;

    /* If true, insert all incoming ways in the index table. */
    public boolean tileIndexing = false;

    /* If true, track which nodes are referenced by more than one way. */
    public boolean intersectionDetection = false;

    /**
     * Construct a new MapDB-based random-access OSM data store.
     * If diskPath is null, OSM will be loaded into a temporary file and deleted on shutdown.
     * If diskPath is the string "__MEMORY__" the OSM will be stored entirely in memory. 
     * 
     * @param diskPath - the file in which to save the data, null for a temp file, or "__MEMORY__" for in-memory.
     */
    public OSM (String diskPath) {
        DBMaker dbMaker;
        if (diskPath == null) {
            LOG.info("OSM will be stored in a temporary file.");
            dbMaker = DBMaker.newTempFileDB().deleteFilesAfterClose();
        } else {
            if (diskPath.equals("__MEMORY__")) {
                LOG.info("OSM will be stored in memory.");
                // 'direct' means off-heap memory, no garbage collection overhead
                dbMaker = DBMaker.newMemoryDirectDB(); 
            } else {
                LOG.info("OSM will be stored in file {}.", diskPath);
                dbMaker = DBMaker.newFileDB(new File(diskPath));
            }
        }
        
        // Compression has no appreciable effect on speed but reduces file size by about 16 percent.
        // Hash table cache (eviction by collision) is on by default with a size of 32k records.
        // http://www.mapdb.org/doc/caches.html
        // Our objects do not have semantic hash and equals functions, but I suppose it's the table keys that are hashed
        // not the values.
        db = dbMaker.asyncWriteEnable()
                .transactionDisable()
                //.cacheDisable()
                .compressionEnable()
                .mmapFileEnableIfSupported()
                .closeOnJvmShutdown()
                .make();

        if (db.getAll().isEmpty()) {
            LOG.info("No OSM tables exist yet, they will be created.");
        }
        
        nodes = db.createTreeMap("nodes")
                .keySerializer(BTreeKeySerializer.ZERO_OR_POSITIVE_LONG)
                .valueSerializer(new NodeSerializer())
                .makeOrGet();
        
        ways =  db.createTreeMap("ways")
                .keySerializer(BTreeKeySerializer.ZERO_OR_POSITIVE_LONG)
                .valueSerializer(new WaySerializer())
                .makeOrGet();
                
        relations = db.createTreeMap("relations")
                .keySerializer(BTreeKeySerializer.ZERO_OR_POSITIVE_LONG)
                .makeOrGet();

        // Serializer delta-compresses the tuple as a whole and variable-width packs ints,
        // but does not recursively delta-code its elements.
        index = db.createTreeSet("spatial_index")
                .serializer(BTreeKeySerializer.TUPLE3) 
                .makeOrGet();

        db.createAtomicLong("timestamp", 0);

    }

    // TODO put these read/write methods on all sources/sinks
    public void readFromFile(String filePath) {
        try {
            LOG.info("Reading OSM from file '{}'.", filePath);
            OSMEntitySource source = OSMEntitySource.forFile(filePath);
            source.copyTo(this);
        } catch (Exception ex) {
            throw new RuntimeException("Error occurred while parsing OSM file " + filePath, ex);
        }
    }

    public void writeToFile(String filePath) {
        try {
            LOG.info("Writing OSM to file '{}'.", filePath);
            OSMEntitySink sink = OSMEntitySink.forFile(filePath);
            this.copyTo(sink);
        } catch (Exception ex) {
            throw new RuntimeException("Error occurred while parsing OSM file " + filePath, ex);
        }
    }

    public void readVex(InputStream inputStream) {
        try {
            OSMEntitySource source = new VexInput(inputStream);
            source.copyTo(this);
        } catch (IOException ex) {
            LOG.error("Error occurred while parsing VEX stream.");
            ex.printStackTrace();
        }
    }

    public void readPbf(InputStream inputStream) {
        try {
            OSMEntitySource source = new PBFInput(inputStream);
            source.copyTo(this);
        } catch (IOException ex) {
            LOG.error("Error occurred while parsing VEX stream.");
            ex.printStackTrace();
        }
    }

    /** Write the contents of this OSM MapDB out to a stream in VEX binary format. */
    public void writeVex(OutputStream outputStream) throws IOException {
        OSMEntitySink sink = new VexOutput(outputStream);
        this.copyTo(sink);
    }

    /** Write the contents of this OSM MapDB out to a stream in PBF binary format. */
    public void writePbf(OutputStream outputStream) throws IOException {
        OSMEntitySink sink = new PBFOutput(outputStream);
        this.copyTo(sink);
    }

    /** Write the contents of this OSM MapDB out to an OSM entity sink (implements OSMEntitySource). */
    @Override
    public void copyTo (OSMEntitySink sink) throws IOException {
        sink.writeBegin();
        for (Map.Entry<Long, Node> nodeEntry : this.nodes.entrySet()) {
            sink.writeNode(nodeEntry.getKey(), nodeEntry.getValue());
        }
        for (Map.Entry<Long, Way> wayEntry : this.ways.entrySet()) {
            sink.writeWay(wayEntry.getKey(), wayEntry.getValue());
        }
        for (Map.Entry<Long, Relation> relationEntry : this.relations.entrySet()) {
            sink.writeRelation(relationEntry.getKey(), relationEntry.getValue());
        }
        sink.writeEnd();
    }


    /**
     * Insert the given way into the tile-based spatial index, based on its current node locations in the database.
     * If the way does not exist, this method does nothing (leaving any reference to the way in the index) because
     * it can't know anything about the location of a way that's already deleted. If the way object is not supplied
     * it will be looked up by its ID.
     */
    public void indexWay(long wayId, Way way) {
        // We could also insert using ((float)lat, (float)lon) as a key
        // but depending on whether MapDB does tree path compression this might take more space
        WebMercatorTile tile = tileForWay(wayId, way);
        if (way == null) {
            LOG.debug("Attempted insert way {} into the spatial index, but it is not currently in the database.", wayId);
        } else {
            this.index.add(new Tuple3(tile.xtile, tile.ytile, wayId));
        }
    }

    public void unIndexWay(long wayId) {
        Way way = ways.get(wayId);
        if (way == null) {
            LOG.debug("Attempted to remove way {} from the spatial index, but it is not currently in the database.", wayId);
        } else {
            WebMercatorTile tile = tileForWay(wayId, way);
            if (tile != null) {
                this.index.remove(new Tuple3(tile.xtile, tile.ytile, wayId));
            }
        }
    }

    /** @return null if the way is not in the database and therefore can't be located. */
    private WebMercatorTile tileForWay (long wayId, Way way) {
        if (way == null) way = ways.get(wayId); // Way object was not supplied, fetch it from the database.
        if (way == null) return null; // Way does not exist anymore in the database, ignore it.
        long firstNodeId = way.nodes[0];
        Node firstNode = this.nodes.get(firstNodeId);
        if (firstNode == null) {
            LOG.debug("Leaving way {} out of the index. It references node {} that was not (yet) provided.",
                      wayId, firstNodeId);
            return null;
        } else {
            WebMercatorTile tile = new WebMercatorTile(firstNode.getLat(), firstNode.getLon());
            return tile;
        }
    }

    /* OSM DATA SINK INTERFACE */

    @Override
    public void writeBegin() throws IOException {
        // Do nothing. Could initialize database here.
    }

    @Override
    public void writeNode(long id, Node node) {
        this.nodes.put(id, node);
    }

    @Override
    public void writeWay(long id, Way way) {

        // Insert the way into the MapDB table.
        this.ways.put(id, way);

        // Optionally track which nodes are referenced by more than one way.
        if (intersectionDetection) {
            for (long nodeId : way.nodes) {
                if (referencedNodes.contains(nodeId)) {
                    intersectionNodes.add(nodeId);
                } else {
                    referencedNodes.add(nodeId);
                }
            }
        }

        // Insert the way into the tile-based spatial index according to its first node.
        if (tileIndexing) {
            indexWay(id, way);
        }

    }

    @Override
    public void writeRelation(long id, Relation relation) {
        this.relations.put(id, relation);
    }

    @Override
    public void writeEnd() throws IOException {
        // Do nothing.
    }

}
