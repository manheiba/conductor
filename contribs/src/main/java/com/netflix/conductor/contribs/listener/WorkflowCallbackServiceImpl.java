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

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.annotations.Audit;
import com.netflix.conductor.annotations.Service;
import com.netflix.conductor.annotations.Trace;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.core.execution.ApplicationException;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.core.utils.QueueUtils;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.service.ExecutionService;

/**
 * @author Manheiba
 */
@Audit
@Singleton
@Trace
public class WorkflowCallbackServiceImpl implements WorkflowCallbackService {
	
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowCallbackServiceImpl.class);

    private final QueueDAO queueDAO;
    
    private final WorkflowExecutor workflowExecutor;
    
    private static final int MAX_POLL_TIMEOUT_MS = 5000;

    @Inject
    public WorkflowCallbackServiceImpl(ExecutionService executionService, QueueDAO queueDAO,WorkflowExecutor workflowExecutor) {
        this.queueDAO = queueDAO;
        this.workflowExecutor = workflowExecutor;
    }
	
	
    /**
     * Batch Poll for a task of a certain type.
     *
     * @param workflowListenerQueue workflowListenerQueue
     * @param workerId Id of the workflow
     * @param domain   Domain of the workflow
     * @param count    Number of tasks
     * @param timeout  Timeout for polling in milliseconds
     * @return list of {@link Task}
     */
    @Service
    public List<Workflow> batchPoll(String workflowListenerQueue, String workerId, String domain, Integer count, Integer timeout) {
    	List<Workflow> workflows = poll(workflowListenerQueue, workerId, domain, count, timeout);
        LOGGER.debug("The Tasks {} being returned for /tasks/poll/{}?{}&{}",
        		workflows.stream()
                                .map(Workflow::getWorkflowId)
                                .collect(Collectors.toList()), workflowListenerQueue, workerId, domain);
        Monitors.recordTaskPollCount(workflowListenerQueue, domain, workflows.size());
        return workflows;
    }
    
    public List<Workflow> poll(String workflowListenerQueue, String workerId, String domain, int count, int timeoutInMilliSecond) {
		if (timeoutInMilliSecond > MAX_POLL_TIMEOUT_MS) {
			throw new ApplicationException(ApplicationException.Code.INVALID_INPUT,
					"Long Poll Timeout value cannot be more than 5 seconds");
		}
		String queueName = QueueUtils.getQueueName(workflowListenerQueue, domain,null,null);

		List<Workflow> workflows = new LinkedList<>();
		try {
			List<String> workflowIds = queueDAO.pop(queueName, count, timeoutInMilliSecond);
			for (String workflowId : workflowIds) {
				Workflow workflow = getWorkflow(workflowId);
				if (workflow == null) {
					continue;
				}
				workflows.add(workflow);
			}
			Monitors.recordTaskPoll(queueName);
		} catch (Exception e) {
			LOGGER.error("Error polling for task: {} from worker: {} in domain: {}, count: {}", workflowListenerQueue, workerId, domain, count, e);
			Monitors.error(this.getClass().getCanonicalName(), "taskPoll");
		}
		return workflows;
	}
    
	public Workflow getWorkflow(String workflowId) {
		return workflowExecutor.getWorkflow(workflowId, false);
	}
    
    

    /**
     * Ack workflow is received.
     *
     * @param workflowId Id of the workflow
     * @return `true|false` if workflow if received or not
     */
    @Service
    public String ackWorkflowCallbackReceived(String queuename,String workflowId) {
        LOGGER.debug("Ack workflow for callback: {}", workflowId);
        boolean ackResult;
        try {
        	ackResult = queueDAO.ack(QueueUtils.getQueueName(queuename, null,null,null), workflowId);
        } catch (Exception e) {
            // safe to ignore exception here, since the task will not be processed by the worker due to ack failure
            // The task will eventually be available to be polled again after the unack timeout
            LOGGER.error("Exception when trying to ack the workflow callback {}", workflowId, e);
            ackResult = false;
        }
        return String.valueOf(ackResult);
    }
}
