package com.sprintwise.gtfs.onebusaway;

import static com.sprintwise.gtfs.GtfsImportDiagnostic.UNSPECIFIED;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.gtfs.GtfsLoadException;
import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.gtfs.validation.GtfsFeedValidationException;
import com.sprintwise.gtfs.validation.GtfsFeedValidator;
import com.sprintwise.model.GtfsFeed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.serialization.GtfsReader;

/**
 * OneBusAway ingestion boundary. Parser-owned entities are mapped to SprintWise
 * models and then checked by the shared parser-neutral feed validator.
 */
public final class OneBusAwayGtfsLoader implements GtfsLoader {

    private final OneBusAwayGtfsMapper mapper = new OneBusAwayGtfsMapper();

    @Override
    public GtfsFeed load(Path source, String feedId) {
        Objects.requireNonNull(source, "source");
        String namespace = GtfsFeedValidator.requireValidFeedId(feedId);

        if (!Files.exists(source)) {
            throw OneBusAwayImportDiagnostics.failure(
                source,
                namespace,
                GtfsDiagnosticCode.SOURCE_MISSING,
                UNSPECIFIED,
                "feed",
                namespace,
                "source",
                UNSPECIFIED,
                "GTFS source does not exist"
            );
        }

        var dao = new GtfsRelationalDaoImpl();
        var reader = new GtfsReader();
        try {
            reader.setInputLocation(source.toFile());
            reader.setEntityStore(dao);
            reader.setInternStrings(true);
            reader.setDefaultAgencyId(namespace);
            reader.run();

            GtfsFeed feed = mapper.map(dao, namespace, source);
            try {
                GtfsFeedValidator.validate(feed);
            } catch (GtfsFeedValidationException exception) {
                throw OneBusAwayImportDiagnostics.validationFailure(
                    source,
                    namespace,
                    exception
                );
            }
            return feed;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof GtfsLoadException loadException) {
                throw loadException;
            }
            throw OneBusAwayImportDiagnostics.readFailure(source, namespace, exception);
        }
    }
}
