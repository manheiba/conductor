/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.conductor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.orchestration.ExecutionDAOFacade;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;

/**
 * @author Viren
 *
 */
@Singleton
public class WorkflowMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowMonitor.class);

	private final MetadataDAO metadataDAO;
	private final QueueDAO queueDAO;
	private final ExecutionDAOFacade executionDAOFacade;

	private ScheduledExecutorService scheduledExecutorService;

	private List<TaskDef> taskDefs;
	private List<WorkflowDef> workflowDefs;

	private int refreshCounter = 0;
	private int metadataRefreshInterval;
	private int statsFrequencyInSeconds;
	private List<String> workflowMetadataList;
	
	@Inject
	public WorkflowMonitor(MetadataDAO metadataDAO, QueueDAO queueDAO, ExecutionDAOFacade executionDAOFacade, Configuration config) {
		this.metadataDAO = metadataDAO;
		this.queueDAO = queueDAO;
		this.executionDAOFacade = executionDAOFacade;
		this.metadataRefreshInterval = config.getIntProperty("workflow.monitor.metadata.refresh.counter", 10);
		this.statsFrequencyInSeconds = config.getIntProperty("workflow.monitor.stats.freq.seconds", 60);
		init();
	}

	public void init() {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("Thread-WorkflowMonitor-Scheduled-%d").build();
		this.scheduledExecutorService = Executors.newScheduledThreadPool(1,threadFactory);
		this.scheduledExecutorService.scheduleWithFixedDelay(() -> {
			try {
				if (refreshCounter <= 0) {
					//update by manheiba @20191213 for change `getPendingWorkflowCount` to `getRunningWorkflowIds`
					// workflowDefs = metadataDAO.getAll();
					workflowMetadataList = metadataDAO.findAll();
					taskDefs = new ArrayList<>(metadataDAO.getAllTaskDefs());
					refreshCounter = metadataRefreshInterval;
				}
				monitorInformation();
			} catch (Exception e) {
				LOGGER.error("Error while publishing scheduled metrics", e);
			}
		}, 1, statsFrequencyInSeconds, TimeUnit.SECONDS);
	}
	
	public void monitorInformation() {
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("\n");
		stringBuilder.append("<<<<<<<<<<<<<<<<<<<<<<<<<<<< Concutor Monitor Start<<<<<<<<<<<<<<<<<<<<<<<<<<<< \n");
		try {
			workflowMetadataList.forEach(workflowName -> {
//				String name = workflowDef.getName();
//				String version = String.valueOf(workflowDef.getVersion());
//				String ownerApp = workflowDef.getOwnerApp();
				long count = executionDAOFacade.getPendingWorkflowCount(workflowName);
				Monitors.recordRunningWorkflows(count, workflowName);
				stringBuilder.append("RunningWorkflows "+workflowName+" count "+count+"\n");
			});

			taskDefs.forEach(taskDef -> {
				long size = queueDAO.getSize(taskDef.getName());
				long inProgressCount = executionDAOFacade.getInProgressTaskCount(taskDef.getName());
				Monitors.recordQueueDepth(taskDef.getName(), size, taskDef.getOwnerApp());
				if(taskDef.concurrencyLimit() > 0) {
					Monitors.recordTaskInProgress(taskDef.getName(), inProgressCount, taskDef.getOwnerApp());
				}
				stringBuilder.append("TaskInProgress "+taskDef.getName()+" count "+inProgressCount + "\n");
			});

			// added by manheiba @20191213 for all queue depth monitor , use queue.size + unack.size;
			queueDAO.queuesDetailVerbose().forEach((queueName,queueMap) -> {
				queueMap.forEach((shard,queueShardMap) -> {
					String queueNameShard = queueName+"."+shard;
					long size = queueShardMap.get("size") + queueShardMap.get("uacked");
					Monitors.recordQueueDepth(queueNameShard, size , "");
					stringBuilder.append("QueueNameShard "+queueNameShard+" count "+size+" : size "+queueShardMap.get("size")+" unack "+ queueShardMap.get("uacked") +"\n");
				});

			});
			stringBuilder.append("<<<<<<<<<<<<<<<<<<<<<<<<<<<< Concutor Monitor End <<<<<<<<<<<<<<<<<<<<<<<<<<<<");
		}finally {
			LOGGER.info(stringBuilder.toString());
			stringBuilder.delete(0, stringBuilder.length());
		}

	}
}
