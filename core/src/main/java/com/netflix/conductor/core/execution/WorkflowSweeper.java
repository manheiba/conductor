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
package com.netflix.conductor.core.execution;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.conductor.core.WorkflowContext;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.execution.ApplicationException.Code;
import com.netflix.conductor.core.utils.GracefullyShutdownUtil;
import com.netflix.conductor.core.utils.ShutdownHookManager;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @author Viren
 * @author Vikram
 *
 */
@Singleton
public class WorkflowSweeper {

	private static final Logger logger = LoggerFactory.getLogger(WorkflowSweeper.class);

	private ExecutorService executorService;
	
	private ScheduledExecutorService deciderPool;

	private Configuration config;

	private QueueDAO queueDAO;

	private int executorThreadPoolSize;

	private static final String className = WorkflowSweeper.class.getSimpleName();

	@Inject
	public WorkflowSweeper(WorkflowExecutor workflowExecutor, Configuration config, QueueDAO queueDAO) {
		this.config = config;
		this.queueDAO = queueDAO;
		this.executorThreadPoolSize = config.getIntProperty("workflow.sweeper.thread.count", 5);
		if(this.executorThreadPoolSize > 0) {
			ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("Thread-WorkflowSweeper-Fixed-%d").build();
			this.executorService = Executors.newFixedThreadPool(executorThreadPoolSize,threadFactory);
			init(workflowExecutor);
			logger.info("Workflow Sweeper Initialized");
		} else {
			logger.warn("Workflow sweeper is DISABLED");
		}
		//Runtime.getRuntime().addShutdownHook(new Thread(()-> {shutdown();}));
		ShutdownHookManager.get().addShutdownHook(()-> {shutdown();}, 4);
	}
    
    //@PreDestroy
    private void shutdown() {
        GracefullyShutdownUtil.shutdownExecutorService("WorkflowSweeper.deciderPool",deciderPool,0,30);
        GracefullyShutdownUtil.shutdownExecutorService("WorkflowSweeper.executorService",executorService,0,30);
    }

	public void init(WorkflowExecutor workflowExecutor) {
		ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("Thread-WorkflowSweeper-Scheduled-%d").build();
		deciderPool = Executors.newScheduledThreadPool(1,threadFactory);
		deciderPool.scheduleWithFixedDelay(() -> {
			try {
				boolean disable = config.disableSweep();
				if (disable) {
					logger.info("Workflow sweep is disabled.");
					return;
				}
				List<String> workflowIds = queueDAO.pop(WorkflowExecutor.DECIDER_QUEUE, 2 * executorThreadPoolSize, 2000);
				int currentQueueSize = queueDAO.getSize(WorkflowExecutor.DECIDER_QUEUE);
				logger.debug("Sweeper's current deciderqueue size: {}.", currentQueueSize);
				int retrievedWorkflows = (workflowIds != null) ? workflowIds.size() : 0;
				logger.debug("Sweeper retrieved {} workflows from the decider queue.", retrievedWorkflows);

				sweep(workflowIds, workflowExecutor);
			} catch (Exception e) {
				Monitors.error(className, "sweep");
				logger.error("Error when sweeping workflow", e);
			}
		}, 500, 500, TimeUnit.MILLISECONDS);
	}

	public void sweep(List<String> workflowIds, WorkflowExecutor workflowExecutor) throws Exception {

		List<Future<?>> futures = new LinkedList<>();
		for (String workflowId : workflowIds) {
			Future<?> future = executorService.submit(() -> {
				try {

					WorkflowContext workflowContext = new WorkflowContext(config.getAppId());
					WorkflowContext.set(workflowContext);
					if(logger.isDebugEnabled()) {
						logger.debug("Running sweeper for workflow {}", workflowId);
					}
					boolean done = workflowExecutor.decide(workflowId);
					if(!done) {
						queueDAO.setUnackTimeout(WorkflowExecutor.DECIDER_QUEUE, workflowId, config.getSweepFrequency() * 1000);
					} else {
						queueDAO.remove(WorkflowExecutor.DECIDER_QUEUE, workflowId);
					}

				} catch (ApplicationException e) {
					if(e.getCode().equals(Code.NOT_FOUND)) {
						logger.error("Workflow NOT found for id: " + workflowId, e);
						queueDAO.remove(WorkflowExecutor.DECIDER_QUEUE, workflowId);
					}

				} catch (Exception e) {
					Monitors.error(className, "sweep");
					logger.error("Error running sweep for " + workflowId, e);
				}
			});
			futures.add(future);
		}

		for (Future<?> future : futures) {
			future.get();
		}
	}
}
