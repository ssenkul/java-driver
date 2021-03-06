/*
 *      Copyright (C) 2012 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core.querybuilder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.datastax.driver.core.RegularStatement;

/**
 * A built BATCH statement.
 */
public class Batch extends BuiltStatement {

    private final List<RegularStatement> statements;
    private final boolean logged;
    private final Options usings;
    private ByteBuffer routingKey;

    Batch(RegularStatement[] statements, boolean logged) {
        super((String)null);
        this.statements = statements.length == 0
                        ? new ArrayList<RegularStatement>()
                        : new ArrayList<RegularStatement>(statements.length);
        this.logged = logged;
        this.usings = new Options(this);

        for (int i = 0; i < statements.length; i++)
            add(statements[i]);
    }

    @Override
    StringBuilder buildQueryString(List<ByteBuffer> variables) {
        StringBuilder builder = new StringBuilder();

        builder.append(isCounterOp()
                       ? "BEGIN COUNTER BATCH"
                       : (logged ? "BEGIN BATCH" : "BEGIN UNLOGGED BATCH"));

        if (!usings.usings.isEmpty()) {
            builder.append(" USING ");
            Utils.joinAndAppend(builder, " AND ", usings.usings, variables);
        }
        builder.append(" ");

        for (int i = 0; i < statements.size(); i++) {
            RegularStatement stmt = statements.get(i);
            if (stmt instanceof BuiltStatement) {
                BuiltStatement bst = (BuiltStatement)stmt;
                builder.append(maybeAddSemicolon(bst.buildQueryString(variables)));

            } else {
                String str = stmt.getQueryString();
                builder.append(str);
                if (!str.trim().endsWith(";"))
                    builder.append(";");

                if (variables != null) {
                    ByteBuffer[] vars = stmt.getValues();
                    if (vars != null) {
                        for (int idx = 0; idx < vars.length; idx++)
                            variables.add(vars[idx]);
                    }
                }
            }
        }
        builder.append("APPLY BATCH;");
        return builder;
    }

    /**
     * Adds a new statement to this batch.
     *
     * @param statement the new statement to add.
     * @return this batch.
     *
     * @throws IllegalArgumentException if counter and non-counter operations
     * are mixed.
     */
    public Batch add(RegularStatement statement) {
        boolean isCounterOp = statement instanceof BuiltStatement && ((BuiltStatement) statement).isCounterOp();

        if (this.isCounterOp == null)
            setCounterOp(isCounterOp);
        else if (isCounterOp() != isCounterOp)
            throw new IllegalArgumentException("Cannot mix counter operations and non-counter operations in a batch statement");

        this.statements.add(statement);

        if (statement instanceof BuiltStatement)
            this.hasBindMarkers = ((BuiltStatement)statement).hasBindMarkers;
        else
            // For non-BuiltStatement, we cannot know if it includes a bind makers. So we assume it does.
            this.hasBindMarkers = true;

        checkForBindMarkers(null);

        if (routingKey == null && statement.getRoutingKey() != null)
            routingKey = statement.getRoutingKey();

        return this;
    }

    /**
     * Adds a new options for this BATCH statement.
     *
     * @param using the option to add.
     * @return the options of this BATCH statement.
     */
    public Options using(Using using) {
        return usings.and(using);
    }

    /**
     * Returns the first non-null routing key of the statements in this batch
     * or null otherwise.
     *
     * @return the routing key for this batch statement.
     */
    @Override
    public ByteBuffer getRoutingKey() {
        return routingKey;
    }

    /**
     * Returns the keyspace of the first statement in this batch.
     *
     * @return the keyspace of the first statement in this batch.
     */
    @Override
    public String getKeyspace() {
        return statements.isEmpty() ? null : statements.get(0).getKeyspace();
    }

    /**
     * The options of a BATCH statement.
     */
    public static class Options extends BuiltStatement.ForwardingStatement<Batch> {

        private final List<Using> usings = new ArrayList<Using>();

        Options(Batch statement) {
            super(statement);
        }

        /**
         * Adds the provided option.
         *
         * @param using a BATCH option.
         * @return this {@code Options} object.
         */
        public Options and(Using using) {
            usings.add(using);
            checkForBindMarkers(using);
            return this;
        }

        /**
         * Adds a new statement to the BATCH statement these options are part of.
         *
         * @param statement the statement to add.
         * @return the BATCH statement these options are part of.
         */
        public Batch add(RegularStatement statement) {
            return this.statement.add(statement);
        }
    }
}
