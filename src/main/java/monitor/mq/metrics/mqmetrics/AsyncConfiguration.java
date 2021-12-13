package monitor.mq.metrics.mqmetrics;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfiguration {

	private final static Logger log = LoggerFactory.getLogger(AsyncConfiguration.class);
	
	/*
	 * Create single thread pool to process CPUMetrics and SystemMetrics
	 * ... Could have used ThreadPoolTaskExecutor
	 * ... The names of the treads are set in the MQResources class
	 */
	
	@Bean (name = "cpu")
	public ExecutorService cpuTaskExecutor() {
	
        log.debug("Creating Async CPU Task Executor");
        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        return executorService;
    }
	
	
	@Bean (name = "system")
	public ExecutorService systemTaskExecutor() {

        log.debug("Creating Async System Task Executor");
        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        return executorService;

    }
	
}
