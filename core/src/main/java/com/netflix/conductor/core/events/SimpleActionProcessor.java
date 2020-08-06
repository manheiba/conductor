/*
 * Copyright 2017 Netflix, Inc.
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
package com.netflix.conductor.core.events;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.common.metadata.events.EventHandler.Action;
import com.netflix.conductor.common.metadata.events.EventHandler.StartWorkflow;
import com.netflix.conductor.common.metadata.events.EventHandler.TaskDetails;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.Task.Status;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.core.execution.ApplicationException;
import com.netflix.conductor.core.execution.ParametersUtils;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.core.utils.JsonUtils;

/**
 * @author Viren
 * Action Processor subscribes to the Event Actions queue and processes the actions (e.g. start workflow etc)
 */
public class SimpleActionProcessor implements ActionProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SimpleActionProcessor.class);

    private final WorkflowExecutor executor;
    private final ParametersUtils parametersUtils;
    private final JsonUtils jsonUtils;

    @Inject
    public SimpleActionProcessor(WorkflowExecutor executor, ParametersUtils parametersUtils, JsonUtils jsonUtils) {
        this.executor = executor;
        this.parametersUtils = parametersUtils;
        this.jsonUtils = jsonUtils;
    }

    public Map<String, Object> execute(Action action, Object payloadObject, String event, String messageId) {

        logger.debug("Executing action: {} for event: {} with messageId:{}", action.getAction(), event, messageId);

        Object jsonObject = payloadObject;
        if (action.isExpandInlineJSON()) {
            jsonObject = jsonUtils.expand(payloadObject);
        }

        switch (action.getAction()) {
            case start_workflow:
                return startWorkflow(action, jsonObject, event, messageId);
            case complete_task:
                return completeTask(action, jsonObject, action.getComplete_task(), Status.COMPLETED, event, messageId);
            case fail_task:
                return completeTask(action, jsonObject, action.getFail_task(), Status.FAILED, event, messageId);
            default:
                break;
        }
        throw new UnsupportedOperationException("Action not supported " + action.getAction() + " for event " + event);
    }

    private Map<String, Object> completeTask(Action action, Object payload, TaskDetails taskDetails, Status status, String event, String messageId) {

        Map<String, Object> input = new HashMap<>();
        input.put("workflowId", taskDetails.getWorkflowId());
        input.put("taskId", taskDetails.getTaskId());
        input.put("taskRefName", taskDetails.getTaskRefName());
        input.putAll(taskDetails.getOutput());

        Map<String, Object> replaced = parametersUtils.replace(input, payload);
        String workflowId = (String) replaced.get("workflowId");
        String taskId = (String) replaced.get("taskId");
        String taskRefName = (String) replaced.get("taskRefName");

        // update by manheiba @20191212 for check workflowId nullable;
        if (StringUtils.isEmpty(workflowId)) {
        	replaced.put("error", "workflow id is null");
            return replaced;
        }
        Task task = null;
        if (StringUtils.isNotEmpty(taskId)) {
            task = executor.getTask(taskId);
        } else if (StringUtils.isNotEmpty(workflowId) && StringUtils.isNotEmpty(taskRefName)) {
            Workflow workflow = executor.getWorkflow(workflowId, true);
            if (workflow == null) {
            	logger.error("No workflow found with ID: {}" , workflowId);
                replaced.put("error", "No workflow found with ID: " + workflowId);
                return replaced;
            }
            // update by manheiba @20191212 check workflow status  is Terminal;
            if (workflow.getStatus().isTerminal()) {
            	logger.error("Workflow {} status is Terminal. Status is {} " ,workflow.getWorkflowId(), workflow.getStatus());
                replaced.put("error", "Workflow status is Terminal. Status is " + workflow.getStatus());
                return replaced;
            }
            
            // TODO: manheiba@20121226 check contained taskdefine
            task = workflow.getTaskByRefName(taskRefName);
        } 

        if (task == null ) {
        	replaced.put("taskerror", "No task found with taskId: " + taskId + ", reference name: " + taskRefName + ", workflowId: " + workflowId );
            replaced.put("error", "No task found with taskId: " + taskId + ", reference name: " + taskRefName + ", workflowId: " + workflowId );
            return replaced;
        } else if(task.getStatus() == Task.Status.SCHEDULED ){
        	replaced.put("taskerror", "Task.status not IN_PROGRESS: " + taskId + ", reference name: " + taskRefName + ", workflowId: " + workflowId +" or task.status is "+task.getStatus());
            replaced.put("error","Task.status not IN_PROGRESS: " + taskId + ", reference name: " + taskRefName + ", workflowId: " + workflowId +" or task.status is "+task.getStatus());
            return replaced;
        }else {
        	taskId = task.getTaskId();
        }

        task.setStatus(status);
        task.setOutputData(replaced);
        task.setOutputMessage(taskDetails.getOutputMessage());
        task.getOutputData().put("conductor.event.messageId", messageId);
        task.getOutputData().put("conductor.event.name", event);

        try {
            executor.updateTask(new TaskResult(task));
            logger.debug("Updated task: {} in workflow:{} with status: {} for event: {} for message:{}", taskId, workflowId, status, event, messageId);
        } catch (RuntimeException e) {
            if (e instanceof ApplicationException && ((ApplicationException) e).getCode() == ApplicationException.Code.LOCKED_ERROR) {
            	replaced.put("taskerror", "Task " + taskId +" had be locked. workflow id is "+ workflowId);
                replaced.put("error", "Task " + taskId +" had be locked. workflow id is "+ workflowId);
                return replaced;
            }
            logger.error("Error updating task: {} in workflow: {} in action: {} for event: {} for message: {}", taskDetails.getTaskRefName(), workflowId, action.getAction(), event, messageId, e);
            replaced.put("error", e.getMessage());
            throw e;
        }
        return replaced;
    }

    private Map<String, Object> startWorkflow(Action action, Object payload, String event, String messageId) {
        StartWorkflow params = action.getStart_workflow();
        Map<String, Object> output = new HashMap<>();
        try {
            Map<String, Object> inputParams = params.getInput();
            Map<String, Object> workflowInput = parametersUtils.replace(inputParams, payload);

            Map<String, Object> paramsMap = new HashMap<>();
            Optional.ofNullable(params.getCorrelationId())
                    .ifPresent(value -> paramsMap.put("correlationId", value));
            Map<String, Object> replaced = parametersUtils.replace(paramsMap, payload);

            workflowInput.put("conductor.event.messageId", messageId);
            workflowInput.put("conductor.event.name", event);

            String workflowId = executor.startWorkflow(params.getName(), params.getVersion(),
                    Optional.ofNullable(replaced.get("correlationId")).map(Object::toString)
                            .orElse(params.getCorrelationId()),
                    workflowInput, null, event, params.getTaskToDomain());
            output.put("workflowId", workflowId);
            logger.debug("Started workflow: {}/{}/{} for event: {} for message:{}", params.getName(), params.getVersion(), workflowId, event, messageId);

        } catch (RuntimeException e) {
            logger.error("Error starting workflow: {}, version: {}, for event: {} for message: {}", params.getName(), params.getVersion(), event, messageId, e);
            output.put("error", e.getMessage());
            throw e;
        }
        return output;
    }
}