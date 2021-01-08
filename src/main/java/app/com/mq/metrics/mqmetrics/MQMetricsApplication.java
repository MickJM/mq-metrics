package app.com.mq.metrics.mqmetrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@ComponentScan(basePackages = { "app.com.mq.metrics.mqmetrics"} )
@ComponentScan("app.com.mq.pcf.queuemanager")
@ComponentScan("app.com.mq.pcf.listener")
@ComponentScan("app.com.mq.pcf.queue")
@ComponentScan("app.com.mq.pcf.channel")
@ComponentScan("app.com.mq.pcf.connections")
@SpringBootApplication
@EnableScheduling
public class MQMetricsApplication {

	public static void main(String[] args) {
		SpringApplication sa = new SpringApplication(MQMetricsApplication.class);
		sa.run(args);
		
	}
}
