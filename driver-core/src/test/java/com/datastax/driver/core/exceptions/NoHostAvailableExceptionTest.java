/*
 * Copyright (C) 2012-2017 DataStax Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.driver.core.exceptions;

import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class NoHostAvailableExceptionTest {
    @Test(groups = "unit")
    public void should_build_default_message_when_less_than_3_errors() {
        NoHostAvailableException e = new NoHostAvailableException(buildMockErrors(3));
        String message = e.getMessage();
        assertThat(message).startsWith("All host(s) tried for query failed");
        assertThat(message).contains("/127.0.0.1:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 1)");
        assertThat(message).contains("/127.0.0.2:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 2)");
        assertThat(message).contains("/127.0.0.3:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 3)");
    }

    @Test(groups = "unit")
    public void should_build_default_message_when_more_than_3_errors() {
        NoHostAvailableException e = new NoHostAvailableException(buildMockErrors(4));
        String message = e.getMessage();
        assertThat(message).startsWith("All host(s) tried for query failed");
        assertThat(message).contains("/127.0.0.1:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 1)");
        assertThat(message).contains("/127.0.0.2:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 2)");
        assertThat(message).contains("/127.0.0.3:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 3)");
        assertThat(message).contains("only showing errors of first 3 hosts, use getErrors() for more details");
    }

    @Test(groups = "unit")
    public void should_build_formatted_message_without_stack_traces() {
        NoHostAvailableException e = new NoHostAvailableException(buildMockErrors(3));
        String message = e.getCustomMessage(3, true, false);
        assertThat(message).startsWith("All host(s) tried for query failed (tried:\n");
        assertThat(message).contains("/127.0.0.1:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 1)\n");
        assertThat(message).contains("/127.0.0.2:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 2)\n");
        assertThat(message).contains("/127.0.0.3:9042 (com.datastax.driver.core.exceptions.NoHostAvailableExceptionTest$MockError: mock error 3)\n");
    }

    @Test(groups = "unit")
    public void should_build_formatted_message_with_stack_traces() {
        NoHostAvailableException e = new NoHostAvailableException(buildMockErrors(3));
        String message = e.getCustomMessage(3, true, true);
        assertThat(message).startsWith("All host(s) tried for query failed (tried:\n");
        assertThat(message).contains("/127.0.0.1:9042\nmock stack trace 1\n");
        assertThat(message).contains("/127.0.0.3:9042\nmock stack trace 3\n");
        assertThat(message).contains("/127.0.0.2:9042\nmock stack trace 2\n");
    }

    private static Map<InetSocketAddress, Throwable> buildMockErrors(int count) {
        Map<InetSocketAddress, Throwable> errors = new HashMap<InetSocketAddress, Throwable>();
        for (int i = 1; i <= count; i++) {
            errors.put(new InetSocketAddress("127.0.0." + i, 9042), new MockError(i));
        }
        return errors;
    }

    static class MockError extends Exception {
        private final int i;

        MockError(int i) {
            super("mock error " + i);
            this.i = i;
        }

        @Override
        public void printStackTrace(PrintWriter writer) {
            writer.printf("mock stack trace %d", i);
        }
    }
}
