package com.netflix.conductor.contribs.spectator.reporter;

import java.io.File;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.CsvReporter;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.netflix.conductor.core.events.SimpleEventProcessor;
import com.netflix.spectator.api.Clock;
import com.netflix.spectator.api.CompositeRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Spectator;
import com.netflix.spectator.metrics3.MetricsRegistry;

@Singleton
public class ReportersManager {
	
    private static final Logger logger = LoggerFactory.getLogger(ReportersManager.class);

	private final JmxReporter jmxReporter;
	//private final CsvReporter csvReporter;

	@Inject
	ReportersManager(MetricRegistry codaRegistry) {

		jmxReporter = JmxReporter.forRegistry(codaRegistry).build();

		jmxReporter.start();

		File dir = new File("./build/metrics");
		dir.mkdirs();
		//csvReporter = CsvReporter.forRegistry(codaRegistry).build(dir);
		//csvReporter.start(5, TimeUnit.SECONDS);
	}

	@PreDestroy
	private void shutdown() {
		jmxReporter.stop();
		logger.info("jmxReporter stoped!");
		//csvReporter.stop();
	}
}

// @Override
// protected void configure() {
// bind(Clock.class).toInstance(Clock.SYSTEM);
// // bind(Server.class).asEagerSingleton();
// bind(ReportersManager.class).asEagerSingleton();
// }
//
// @Provides
// @Singleton
// private MetricRegistry providesCodaRegistry() {
// return new MetricRegistry();
// }
//
// @Provides
// @Singleton
// private Registry providesRegistry(Clock clock, MetricRegistry codaRegistry) {
// return new MetricsRegistry(clock, codaRegistry);
// }