package com.netflix.conductor.contribs.spectator;


import com.codahale.metrics.MetricRegistry;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.netflix.conductor.contribs.spectator.reporter.ReportersManager;
import com.netflix.conductor.service.WorkflowMonitor;
import com.netflix.spectator.api.Clock;
import com.netflix.spectator.api.CompositeRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Spectator;
import com.netflix.spectator.metrics3.MetricsRegistry;


public class MetricsModule extends AbstractModule{

	@Override
	protected void configure() {
		bind(Clock.class).toInstance(Clock.SYSTEM);
		bind(WorkflowMonitor.class).asEagerSingleton();
		//((CompositeRegistry)Spectator.globalRegistry()).add(Clock.SYSTEM,new MetricsRegistry());
		bind(ReportersManager.class).asEagerSingleton();
	}
	
	@Provides
	@Singleton
	private MetricRegistry providesCodaRegistry(Clock clock) {
		MetricRegistry metricRegistry = new MetricRegistry();
		MetricsRegistry metricsRegistry = new MetricsRegistry(clock, metricRegistry);
		((CompositeRegistry)Spectator.globalRegistry()).add(metricsRegistry);
		return metricRegistry;
	}
}
