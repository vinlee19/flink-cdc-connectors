/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.cdc.runtime.operators.schema;

import org.apache.flink.cdc.common.annotation.Internal;
import org.apache.flink.cdc.common.event.Event;
import org.apache.flink.cdc.common.event.FlushEvent;
import org.apache.flink.cdc.common.event.SchemaChangeEvent;
import org.apache.flink.cdc.common.event.TableId;
import org.apache.flink.cdc.runtime.operators.schema.coordinator.SchemaRegistry;
import org.apache.flink.cdc.runtime.operators.schema.event.CoordinationResponseUtils;
import org.apache.flink.cdc.runtime.operators.schema.event.ReleaseUpstreamRequest;
import org.apache.flink.cdc.runtime.operators.schema.event.ReleaseUpstreamResponse;
import org.apache.flink.cdc.runtime.operators.schema.event.SchemaChangeRequest;
import org.apache.flink.cdc.runtime.operators.schema.event.SchemaChangeResponse;
import org.apache.flink.runtime.jobgraph.tasks.TaskOperatorEventGateway;
import org.apache.flink.runtime.operators.coordination.CoordinationRequest;
import org.apache.flink.runtime.operators.coordination.CoordinationResponse;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.util.SerializedValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * The operator will evolve schemas in {@link SchemaRegistry} for incoming {@link
 * SchemaChangeEvent}s and block the stream for tables before their schema changes finish.
 */
@Internal
public class SchemaOperator extends AbstractStreamOperator<Event>
        implements OneInputStreamOperator<Event, Event> {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(SchemaOperator.class);

    private transient TaskOperatorEventGateway toCoordinator;

    public SchemaOperator() {
        this.chainingStrategy = ChainingStrategy.ALWAYS;
    }

    @Override
    public void setup(
            StreamTask<?, ?> containingTask,
            StreamConfig config,
            Output<StreamRecord<Event>> output) {
        super.setup(containingTask, config, output);
        this.toCoordinator = containingTask.getEnvironment().getOperatorCoordinatorEventGateway();
    }

    /**
     * This method is guaranteed to not be called concurrently with other methods of the operator.
     */
    @Override
    public void processElement(StreamRecord<Event> streamRecord) {
        Event event = streamRecord.getValue();
        if (event instanceof SchemaChangeEvent) {
            TableId tableId = ((SchemaChangeEvent) event).tableId();
            LOG.info(
                    "Table {} received SchemaChangeEvent and start to be blocked.",
                    tableId.toString());
            handleSchemaChangeEvent(tableId, (SchemaChangeEvent) event);
            return;
        }
        output.collect(streamRecord);
    }

    // ----------------------------------------------------------------------------------

    private void handleSchemaChangeEvent(TableId tableId, SchemaChangeEvent schemaChangeEvent) {
        // The request will need to send a FlushEvent or block until flushing finished
        SchemaChangeResponse response = requestSchemaChange(tableId, schemaChangeEvent);
        if (response.isShouldSendFlushEvent()) {
            LOG.info(
                    "Sending the FlushEvent for table {} in subtask {}.",
                    tableId,
                    getRuntimeContext().getIndexOfThisSubtask());
            output.collect(new StreamRecord<>(new FlushEvent(tableId)));
            output.collect(new StreamRecord<>(schemaChangeEvent));
            // The request will block until flushing finished in each sink writer
            requestReleaseUpstream();
        }
    }

    private SchemaChangeResponse requestSchemaChange(
            TableId tableId, SchemaChangeEvent schemaChangeEvent) {
        return sendRequestToCoordinator(new SchemaChangeRequest(tableId, schemaChangeEvent));
    }

    private ReleaseUpstreamResponse requestReleaseUpstream() {
        return sendRequestToCoordinator(new ReleaseUpstreamRequest());
    }

    private <REQUEST extends CoordinationRequest, RESPONSE extends CoordinationResponse>
            RESPONSE sendRequestToCoordinator(REQUEST request) {
        try {
            CompletableFuture<CoordinationResponse> responseFuture =
                    toCoordinator.sendRequestToCoordinator(
                            getOperatorID(), new SerializedValue<>(request));
            return CoordinationResponseUtils.unwrap(responseFuture.get());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to send request to coordinator: " + request.toString(), e);
        }
    }
}
