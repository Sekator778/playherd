package crowdvsmarket.server.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.formatters.PatternFormatter;

/**
 * Everything any logger says while it is attached to the root logger, one formatted message (no stack trace) per record — for a
 * test of what the server does NOT write to its log. The JBoss log manager is the JVM's ({@code java.util.logging.manager} in the
 * surefire configuration), so this sees what the application logs through JBoss Logging, in a unit test as in a {@code
 * @QuarkusTest}. Use it in a try block: {@code try (LogCapture log = LogCapture.start()) { … }}.
 */
public final class LogCapture extends Handler implements AutoCloseable {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final PatternFormatter formatter = new PatternFormatter("%s");

    private LogCapture() {
    }

    public static LogCapture start() {
        LogCapture capture = new LogCapture();
        java.util.logging.Logger.getLogger("").addHandler(capture);
        return capture;
    }

    public List<String> lines() {
        return lines;
    }

    @Override
    public void publish(LogRecord record) {
        lines.add(record instanceof ExtLogRecord ext ? formatter.format(ext) : String.valueOf(record.getMessage()));
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        java.util.logging.Logger.getLogger("").removeHandler(this);
    }
}
