/**
 * Copyright 2016 Netflix, Inc.
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
/**
 *
 */
package com.netflix.conductor.contribs.archive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.Workflow.WorkflowStatus;
import com.netflix.conductor.core.execution.tasks.SystemTaskWorkerCoordinator;
import com.netflix.conductor.dao.ExecutionDAO;
import com.netflix.conductor.dao.IndexDAO;
import com.netflix.conductor.service.WorkflowService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * @author Manheiba
 */
@Api(value = "/WorkflowArchive", produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON, tags = "WorkflowArchive Management")
@Path("/workflowArchive")
@Produces({ MediaType.APPLICATION_JSON })
@Consumes({ MediaType.APPLICATION_JSON })
@Singleton
public class WorkflowArchiveResource {
	
	private static final Logger logger = LoggerFactory.getLogger(WorkflowArchiveResource.class);

	private final ExecutionDAO executionDAO;
	private final WorkflowService workflowService;
	private final IndexDAO indexDAO;
	
	@Inject
	public WorkflowArchiveResource(ExecutionDAO executionDAO, 
			WorkflowService workflowService,IndexDAO indexDAO) {
		this.executionDAO = executionDAO;
		this.workflowService = workflowService;
		this.indexDAO = indexDAO;
	}

	@GET
	@Path("/redis/workflowsStatus/search/{name}")
	@ApiOperation("search workflows status count in redis £¬WARNING:Server maybe crash cause by time range too large!")
	@Consumes(MediaType.WILDCARD)
	public Map<WorkflowStatus, Long> searchWorkflowsStatusCountByType(@PathParam("name") String workflowName,
			@QueryParam("startTime") Long startTime, @QueryParam("endTime") Long endTime) {
		List<Workflow> workflowList = executionDAO.getWorkflowsByType(workflowName, startTime, endTime);
		Map<WorkflowStatus, Long> statusCountMap = new HashMap<WorkflowStatus, Long>();
		workflowList.forEach(workflow -> {
			
			Long count = statusCountMap.get(workflow.getStatus());
			if(count == null) { 
				count=0L;
			}
			statusCountMap.put(workflow.getStatus(), count+1);
		});

		return statusCountMap;
	}

	@POST
	@Path("/redis/workflowsStatus/archive/{name}")
	@ApiOperation("archive workflows by status search by redis WARNING:Server maybe crash cause by time range too large!")
	@Consumes(MediaType.WILDCARD)
	public Map<WorkflowStatus, Long> archiveWorkflowsByType(@PathParam("name") String workflowName,
			@QueryParam("startTime") Long startTime, @QueryParam("endTime") Long endTime) {
		// only archive terminal workflow
		List<Workflow> workflowList = executionDAO.getWorkflowsByType(workflowName, startTime, endTime);
		Map<WorkflowStatus, Long> statusCountMap = new HashMap<WorkflowStatus, Long>();
		workflowList.forEach(workflow -> {
			WorkflowStatus workflowStatus = workflow.getStatus();
			if(workflowStatus.isTerminal()) {
				workflowService.deleteWorkflow(workflow.getWorkflowId(), true);
			}else {
				Long count = statusCountMap.get(workflow.getStatus());
				if(count == null) { 
					count=0L;
				}
				statusCountMap.put(workflow.getStatus(), count+1);
			}
		});
		return statusCountMap;
	}
	
	@GET
	@Path("/index/archivable/search")
	@ApiOperation("Search archivable workflows, workflow status as [COMPLETED¡¢FAILED¡¢TIMED_OUT¡¢TERMINATED] £¬SystemDate-ttl-1 <= during < SystemDate-ttl")
	@Consumes(MediaType.WILDCARD)
	public List<String> indexArchivableByTtlDays(@QueryParam("ArchiveTtlDays") Long archiveTtlDays) {
		Preconditions.checkNotNull(archiveTtlDays, "archiveTtlDays cannot be null");
		return indexDAO.searchArchivableWorkflows("conductor_workflow", archiveTtlDays);
	}
	
	@POST
	@Path("/index/archivable/archive")
	@ApiOperation("archive workflows, workflow status as [COMPLETED¡¢FAILED¡¢TIMED_OUT¡¢TERMINATED]£¬SystemDate-ttl-1 <= during < SystemDate-ttl")
	@Consumes(MediaType.WILDCARD)
	public synchronized List<List<String>> removeArchivableByTtlDays(@QueryParam("ArchiveTtlDays") Long archiveTtlDays) {
//		Preconditions.checkNotNull(archiveTtlDays, "archiveTtlDays cannot be null");
//		Preconditions.checkArgument(archiveTtlDays>9, "archiveTtlDays must be gt 10");
		List<List<String>> archiveResult = new ArrayList<List<String>>();
		List<String> successResult = new ArrayList<String>();
		List<String> failedResult = new ArrayList<String>();
		List<String> archivableWorkflowList = indexDAO.searchArchivableWorkflows("conductor_workflow", archiveTtlDays);
		archivableWorkflowList.forEach(workflowId -> {
			try {
				workflowService.deleteWorkflow(workflowId, true);
				successResult.add("S: "+workflowId);
				logger.info("archive workflow {} completed",workflowId);
			}catch(Exception e){
				failedResult.add("F: "+workflowId + "  ["+e.getCause().getMessage()+"]");
				logger.error("archive workflow {} unsuccessful",workflowId);
			}
		});
		archiveResult.add(successResult);
		archiveResult.add(failedResult);
		return archiveResult;
		
	}
}