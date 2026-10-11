package crowdvsmarket.server.geoip;

import java.io.IOException;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Optional;

/** Where the monthly country database comes from: DB-IP publishes one file per month ({@link DbIpDownloader}). */
interface MonthFetcher {

    /**
     * The database of {@code month}, unpacked into a new temporary file that the caller owns and must delete; empty when
     * the source has no file for that month (HTTP 404: the month's file is not published yet, or never was).
     *
     * @throws IOException anything else that goes wrong: no connection, another HTTP status, a file that is not a gzip
     *                     stream, one that is too large, no complete answer in time
     */
    Optional<Path> fetch(YearMonth month) throws IOException, InterruptedException;
}
