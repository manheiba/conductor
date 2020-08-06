/*
 * Copyright 2018 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.conductor.contribs.listener;

import java.util.List;

import javax.validation.constraints.NotEmpty;

import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.run.Workflow;

/**
 * @author Manheiba
 */
public interface WorkflowCallbackService {

	/**
     * Batch Poll for a workflow of a certain type.
     *
     * @param workflowListenerQueue workflowListenerQueue
     * @param workerId Id of the workflow
     * @param domain   Domain of the workflow
     * @param count    Number of tasks
     * @param timeout  Timeout for polling in milliseconds
     * @return list of {@link Task}
     */
    List<Workflow> batchPoll(@NotEmpty(message = "workflowListenerQueue cannot be null or empty.") String workflowListenerQueue, String workerId, String domain, Integer count, Integer timeout);
    
    
    /**
     * Ack workflow is received.
     *
     * @param workflowId   Id of the workflow
     * @param workerId Id of the worker
     * @return `true|false` if task if received or not
     */
    String ackWorkflowCallbackReceived(@NotEmpty(message = "TaskId cannot be null or empty.") String workflowId, String workerId);

}
