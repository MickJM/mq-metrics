package maersk.com.mq.metrics.mqmetrics;

import static org.junit.Assert.assertTrue;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.test.context.junit4.SpringRunner;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

//@ActiveProfiles("test")
//@SpringBootApplication

@RunWith(SpringRunner.class)
@SpringBootTest(classes = { MQMetricsApplication.class })
@Component
public class MQMetricsApplicationTests {

	static Logger log = Logger.getLogger(MQMetricsApplicationTests.class);
		
	@Autowired
	private MQMetricsQueueManager qman;
		
	@Test
	@Order(1)
	public void testConnectionToTheQueueManager() {

		log.info("Queue manager connection");
		String mess = "";
		
		try {
			
			mess = "Queue manager";
			MQQueueManager qm = this.qman.createQueueManager();
			assert (qm) != null;
			
		} catch (Exception e) {
			log.info("Error: " + mess);
			e.printStackTrace();
			
		}
	}

	
	@Test
	@Order(2)
	public void createPCFMessageAgent() {

		log.info("Queue manager connection");
		String mess = "";
		
		try {
			
			mess = "Queue manager";
			MQQueueManager qm = this.qman.createQueueManager();
			assert (qm) != null;

			mess = "PCF Agent";
			PCFMessageAgent ag = this.qman.createMessageAgent(qm);
			assert (ag) != null;
			
			
		} catch (Exception e) {
			log.info("Error: " + mess);
			e.printStackTrace();
			
		}
	}
}
