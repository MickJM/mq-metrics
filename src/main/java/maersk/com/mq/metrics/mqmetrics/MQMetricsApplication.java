package maersk.com.mq.metrics.mqmetrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@ComponentScan("maersk.com.mq.metrics.mqmetrics.MQConnection")
@ComponentScan("maersk.com.mq.pcf.channel.pcfChannel")
@SpringBootApplication
@EnableScheduling
public class MQMetricsApplication {

	public static void main(String[] args) {
		//SpringApplication.run(MqMetricsApplication.class, args);
		SpringApplication sa = new SpringApplication(MQMetricsApplication.class);
		sa.run(args);
		
	}
	
	
}
