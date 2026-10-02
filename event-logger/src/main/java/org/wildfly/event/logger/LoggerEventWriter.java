/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.event.logger;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An {@link EventWriter} that formats each event and passes the result to a named
 * {@link Logger} category at {@link Level#INFO}.
 *
 * <p>The category is a constructor argument so this class carries no knowledge of
 * any particular subsystem.
 *
 * @author Tomasz Adamski
 */
public final class LoggerEventWriter implements EventWriter {

    private final Logger logger;
    private final EventFormatter formatter;

    private LoggerEventWriter(final String category, final EventFormatter formatter) {
        this.logger = Logger.getLogger(category);
        this.formatter = formatter;
    }

    /**
     * Creates a new writer that logs to the named category.
     *
     * @param category  the logger category (e.g. {@code "org.jboss.as.ejb3.access-log"})
     * @param formatter the formatter to convert each event to a string
     * @return a new writer
     */
    public static LoggerEventWriter of(final String category, final EventFormatter formatter) {
        return new LoggerEventWriter(category, formatter);
    }

    @Override
    public void write(final Event event) {
        final String line = formatter.format(event);
        logger.log(Level.INFO, line);
    }

    @Override
    public void close() {
        // Logger has no resource to release; nothing to do.
    }
}
