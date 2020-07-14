package com.netflix.counductor.integration.test

import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.workflow.TaskType
import com.netflix.conductor.common.run.Workflow
import com.netflix.conductor.core.execution.WorkflowExecutor
import com.netflix.conductor.core.execution.WorkflowSweeper
import com.netflix.conductor.service.ExecutionService
import com.netflix.conductor.service.MetadataService
import com.netflix.conductor.test.util.WorkflowTestUtil
import com.netflix.conductor.tests.utils.TestModule
import com.netflix.governator.guice.test.ModulesForTesting
import spock.lang.Specification

import javax.inject.Inject

import static com.netflix.conductor.test.util.WorkflowTestUtil.verifyPolledAndAcknowledgedTask

@ModulesForTesting([TestModule.class])
class EventWorkflowSpec extends Specification {

    @Inject
    ExecutionService workflowExecutionService

    @Inject
    MetadataService metadataService

    @Inject
    WorkflowExecutor workflowExecutor

    @Inject
    WorkflowSweeper workflowSweeper

    @Inject
    WorkflowTestUtil workflowTestUtil

    def EVENT_BASED_WORKFLOW = 'test_event_workflow'

    def setup() {
        workflowTestUtil.registerWorkflows(
                'event_workflow_integration_test.json'
        )
    }

    def cleanup() {
        workflowTestUtil.clearWorkflows()
    }

    def "Verify that a event based simple work flow is executed"() {
        when: "Start a event based workflow"
        def workflowInstanceId = workflowExecutor.startWorkflow(EVENT_BASED_WORKFLOW, 1,
                '', [:], null, null, null)

        and:"Sleep for 1 second to mimic the event trigger"
        Thread.sleep(1000)

        then: "Retrieve the workflow "
        with(workflowExecutionService.getExecutionStatus(workflowInstanceId, true)) {
            status == Workflow.WorkflowStatus.RUNNING
            tasks.size() == 2
            tasks[0].taskType == TaskType.EVENT.name()
            tasks[0].status == Task.Status.COMPLETED
            tasks[0].outputData['event_produced']
            tasks[1].taskType == 'integration_task_1'
            tasks[1].status == Task.Status.SCHEDULED
        }

        when:"The integration_task_1 is polled and completed"
        def polledAndCompletedTry1 = workflowTestUtil.pollAndCompleteTask('integration_task_1', 'task1.integration.worker')

        then:"verify that the task was polled and completed and the workflow is in a complete state"
        verifyPolledAndAcknowledgedTask(polledAndCompletedTry1)
        with(workflowExecutionService.getExecutionStatus(workflowInstanceId, true)) {
            status == Workflow.WorkflowStatus.COMPLETED
            tasks.size() == 2
            tasks[1].taskType == 'integration_task_1'
            tasks[1].status == Task.Status.COMPLETED
        }

    }

}