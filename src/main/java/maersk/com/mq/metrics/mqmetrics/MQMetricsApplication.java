package maersk.com.mq.metrics.mqmetrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@ComponentScan(basePackages = { "maersk.com.mq.metrics.mqmetrics"} )
@ComponentScan("maersk.com.mq.pcf.queuemanager")
@ComponentScan("maersk.com.mq.pcf.listener")
@ComponentScan("maersk.com.mq.pcf.queue")
@ComponentScan("maersk.com.mq.metricsummary")
@ComponentScan("maersk.com.mq.pcf.channel")
@ComponentScan("maersk.com.mq.pcf.connections")
@SpringBootApplication
@EnableScheduling
public class MQMetricsApplication {

	public static void main(String[] args) {
		SpringApplication sa = new SpringApplication(MQMetricsApplication.class);
		sa.run(args);
		
	}
}
