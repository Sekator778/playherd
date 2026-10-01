package crowdvsmarket.server.prices;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.logging.Logger;

/**
 * A {@link Logger} that keeps what is logged at WARN and INFO as formatted text, so that a test
 * can say how often and with which words something was logged — in particular through a
 * {@link ThrottledLog} built on it.
 */
public final class RecordingLogger extends Logger {

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final List<String> infos = new CopyOnWriteArrayList<>();

    public RecordingLogger() {
        super("recording");
    }

    public List<String> warnings() {
        return warnings;
    }

    public List<String> infos() {
        return infos;
    }

    @Override
    public boolean isEnabled(Level level) {
        return true;
    }

    @Override
    protected void doLog(Level level, String loggerClassName, Object message, Object[] parameters, Throwable thrown) {
        record(level, String.valueOf(message));
    }

    @Override
    protected void doLogf(Level level, String loggerClassName, String format, Object[] parameters, Throwable thrown) {
        record(level, parameters == null || parameters.length == 0 ? format : String.format(format, parameters));
    }

    private void record(Level level, String text) {
        if (level == Level.WARN) {
            warnings.add(text);
        } else if (level == Level.INFO) {
            infos.add(text);
        }
    }
}
