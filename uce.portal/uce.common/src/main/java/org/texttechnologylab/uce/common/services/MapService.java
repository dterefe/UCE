package org.texttechnologylab.uce.common.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.exceptions.ExceptionUtils;
import org.texttechnologylab.uce.common.models.dto.map.MapClusterDto;
import org.texttechnologylab.uce.common.models.dto.map.PointDto;

import java.util.List;

@Service
public class MapService {
    private static final Logger logger = LogManager.getLogger(MapService.class);

    private final DataInterface db;

    public MapService(DataInterface db) {
        this.db = db;
    }

    /**
     * This fetches single points of {Geoname,Time} -> Annotation links, such that you can query occurrences
     * of annotations (taxa, ner e.g.) by a given location viewport and a time range.
     * @throws DocumentAccessDeniedException 
     */
    public List<PointDto> getGeoNameTimelineLinks(double minLng,
                                                  double minLat,
                                                  double maxLng,
                                                  double maxLat,
                                                  java.sql.Date fromDate,
                                                  java.sql.Date toDate,
                                                  long corpusId,
                                                  int skip,
                                                  int take,
                                                  String fromAnnotationTypeTable) throws DatabaseOperationException, DocumentAccessDeniedException {
        return db.getGeonameTimelineLinks(minLng, minLat, maxLng, maxLat, fromDate, toDate, corpusId, skip, take, fromAnnotationTypeTable);
    }

    /**
     * This fetches pre-cached clusters of GeoNames that have an annotationlink to other annotations. It also
     * checks for Time links, such that you can not only query by a location viewport, but also by time.
     * @throws DocumentAccessDeniedException 
     */
    public List<MapClusterDto> getTimelineMapClusters(double minLng,
                                                           double minLat,
                                                           double maxLng,
                                                           double maxLat,
                                                           double gridSize,
                                                           java.sql.Date fromDate,
                                                           java.sql.Date toDate,
                                                           long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return db.getGeonameClustersFromTimelineMap(minLng, minLat, maxLng, maxLat, gridSize, fromDate, toDate, corpusId);
    }

    public boolean cachedTimelineMapHasEntries() {
        var entries = ExceptionUtils.tryCatchLog(
                () -> db.getGeonameClustersFromTimelineMap(-180, -90, 180, 90, 1, null, null, -1),
                (ex) -> logger.error("Error getting the count of the cached timeline map.", ex));
        return entries != null && !entries.isEmpty();
    }

    /**
     * Refreshes the Materialized View that acts as a cache between GeoName,Time -> annotationlinks
     * @throws DocumentAccessDeniedException 
     */
    public void refreshCachedTimelineMap(boolean force) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (force || !cachedTimelineMapHasEntries())
            db.refreshGeonameLocations();
    }
}
