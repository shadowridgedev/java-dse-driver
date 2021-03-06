/*
 * Copyright (C) 2012-2017 DataStax Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.driver.core;

import org.testng.annotations.Test;

import static com.datastax.driver.core.Assertions.assertThat;

public abstract class AbstractBatchIdempotencyTest {

    protected abstract TestBatch createBatch();

    /**
     * Unify Batch and BatchStatement to avoid duplicating all tests
     */
    protected interface TestBatch {
        //Batch only accepts RegularStatement, so we use it for common interface
        void add(RegularStatement statement);

        Boolean isIdempotent();

        void setIdempotent(boolean idempotent);
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_true_if_no_statements_added() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_true_if_all_statements_are_idempotent() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isTrue();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_false_if_any_statements_is_nonidempotent() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(false));
        assertThat(batch.isIdempotent()).isFalse();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isFalse();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_null_if_no_nonidempotent_statements_and_some_are_nullidempotent() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(null));
        assertThat(batch.isIdempotent()).isNull();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isNull();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_false_if_both_nonidempotent_and_nullidempotent_statements_present() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(null));
        assertThat(batch.isIdempotent()).isNull();

        batch.add(statementWithIdempotency(false));
        assertThat(batch.isIdempotent()).isFalse();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isFalse();

        batch.add(statementWithIdempotency(null));
        assertThat(batch.isIdempotent()).isFalse();

        batch.add(statementWithIdempotency(false));
        assertThat(batch.isIdempotent()).isFalse();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_override_flag_if_no_statements_added() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.setIdempotent(false);
        assertThat(batch.isIdempotent()).isFalse();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_override_flag_if_calculated_idempotency_true() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(true));
        assertThat(batch.isIdempotent()).isTrue();

        batch.setIdempotent(false);
        assertThat(batch.isIdempotent()).isFalse();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_override_flag_if_calculated_idempotency_null() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(null));
        assertThat(batch.isIdempotent()).isNull();

        batch.setIdempotent(false);
        assertThat(batch.isIdempotent()).isFalse();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_override_flag_if_calculated_idempotency_false() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(false));
        assertThat(batch.isIdempotent()).isFalse();

        batch.setIdempotent(true);
        assertThat(batch.isIdempotent()).isTrue();
    }

    @Test(groups = "unit")
    public void isIdempotent_should_return_override_flag_if_calculated_idempotency_equals_override_value() {
        TestBatch batch = createBatch();
        assertThat(batch.isIdempotent()).isTrue();

        batch.add(statementWithIdempotency(false));
        assertThat(batch.isIdempotent()).isFalse();

        batch.setIdempotent(false);
        assertThat(batch.isIdempotent()).isFalse();
    }

    private RegularStatement statementWithIdempotency(Boolean idempotency) {
        RegularStatement statement = new SimpleStatement("fake statement");
        if (idempotency != null) {
            statement.setIdempotent(idempotency);
            assertThat(statement.isIdempotent()).isEqualTo(idempotency);
        } else {
            assertThat(statement.isIdempotent()).isNull();
        }
        return statement;
    }
}
