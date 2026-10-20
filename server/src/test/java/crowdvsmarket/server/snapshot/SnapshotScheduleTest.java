package crowdvsmarket.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The cron the application ships for the nightly check ({@code herd.snapshot.check-cron} in {@code application.properties}) is one the
 * scheduler can read - a cron it cannot read would stop the server at start, and every other test switches the check off. Parsed with
 * the same library and cron type (Quartz) as the Quarkus scheduler; no application is started.
 */
class SnapshotScheduleTest {

    @Test
    void theShippedCronOfTheNightlyCheckParses() {
        String properties;
        try {
            properties = Files.readString(Path.of("src/main/resources/application.properties"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher m = Pattern.compile("(?m)^herd\\.snapshot\\.check-cron=\\$\\{HERD_SNAPSHOT_CHECK_CRON:([^}]*)}$").matcher(properties);
        assertThat(m.find()).as("the property is there with a default").isTrue();
        String cron = m.group(1);

        new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse(cron).validate();

        assertThat(cron).isEqualTo("0 30 3 * * ?");
    }
}
