package com.netflix.conductor.core.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.execution.tasks.SystemTaskWorkerCoordinator;

public class GracefullyShutdownUtil {

	private static final Logger logger = LoggerFactory.getLogger(SystemTaskWorkerCoordinator.class);

	/**
	 * Gracefully Shutdown
	 * 
	 * @param executorServiceName
	 * @param execService
	 *            ExecutorService
	 * @param delay
	 *            seconds
	 * @param timeout
	 *            seconds
	 */
	public static void shutdownExecutorService(String executorServiceName, ExecutorService executorService, long delay,
			long timeout) {
		logger.info("{} shutdown delay {} seconds, max waiting for {}, executorService is {} ", executorServiceName,
				delay, timeout, executorService);
		if (StringUtils.isEmpty(executorServiceName)) {
			executorServiceName = "unnamed";
		}
		if (executorService == null) {
			logger.warn("executorService is null , return!");
			return;
		}
		if (delay > 60 || timeout > 60) {
			logger.warn("delay {} or timeout {} greate than 60 seconds", delay, timeout);
		}

		try {
			TimeUnit.SECONDS.sleep(delay);
			executorService.shutdown();
			if (executorService.awaitTermination(timeout, TimeUnit.SECONDS)) {
				logger.debug("tasks completed, shutting down");
			} else {
				logger.warn("Forcing shutdown after waiting for {} seconds", timeout);
				executorService.shutdownNow();
			}
		} catch (InterruptedException ie) {
			logger.warn("Shutdown interrupted, invoking shutdownNow on scheduledThreadPoolExecutor for delay queue");
			executorService.shutdownNow();
			Thread.currentThread().interrupt();
		} finally {
			logger.info("{} shutdown finished, executorService is {} ", executorServiceName,
					executorService);
		}
	}
}
