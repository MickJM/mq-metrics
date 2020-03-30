package maersk.com.mq.metrics.mqmetrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

//@ComponentScan("maersk.com.mq.metrics.mqmetrics.MQConnection")
//@ComponentScan("maersk.com.mq.metrics.mqmetrics.MQMetricsApplicationTests")
//@ComponentScan("maersk.com.mq.json.controller.JSONController")
// ,"maersk.com.mq.json.controller"
@ComponentScan(basePackages = { "maersk.com.mq.metrics.mqmetrics"} )
@ComponentScan("maersk.com.mq.pcf.queuemanager")
@ComponentScan("maersk.com.mq.pcf.listener")
@ComponentScan("maersk.com.mq.pcf.queue")
@ComponentScan("maersk.com.mq.metricsummary")
@ComponentScan("maersk.com.mq.pcf.channel")

@SpringBootApplication
@EnableScheduling
public class MQMetricsApplication {

	public static void main(String[] args) {
		SpringApplication sa = new SpringApplication(MQMetricsApplication.class);
		sa.run(args);
		
	}
	
	
}
