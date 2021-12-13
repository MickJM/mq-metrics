package monitor.mq.metrics.mqmetrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@ComponentScan(basePackages = { "monitor.mq.metrics.mqmetrics"} )
@ComponentScan("monitor.mq.pcf.queuemanager")
@ComponentScan("monitor.mq.pcf.listener")
@ComponentScan("monitor.mq.pcf.queue")
@ComponentScan("monitor.mq.pcf.channel")
@ComponentScan("monitor.mq.pcf.connections")
@ComponentScan("monitor.mq.resources")
@SpringBootApplication
@EnableScheduling
public class MQMetricsApplication {

	public static void main(String[] args) {
		SpringApplication sa = new SpringApplication(MQMetricsApplication.class);
		sa.run(args);
		
	}
}
