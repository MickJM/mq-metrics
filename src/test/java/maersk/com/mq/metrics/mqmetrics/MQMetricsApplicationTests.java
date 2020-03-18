package maersk.com.mq.metrics.mqmetrics;

import static org.junit.Assert.assertTrue;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

//@RunWith(SpringRunner.class)
//@ActiveProfiles("test")
@SpringBootApplication
public class MQMetricsApplicationTests {

	static Logger log = Logger.getLogger(MQMetricsApplicationTests.class);
		
	private MQMetricsQueueManager qman;
	
	@Test
	@Order(1)
	public void minimumMQProperties() {

		log.info("MQ Connection properties");

		assert(System.getenv("IBM.MQ.HOSTNAME")) != null;
		assert(System.getenv("IBM.MQ.CHANNEL")) != null;
		assert(System.getenv("IBM.MQ.QUEUEMANAGER")) != null;
		assert(System.getenv("IBM.MQ.USERID")) != null;
		assert(System.getenv("IBM.MQ.PASSWORD")) != null;
		
	}
	
	@Test
	@Order(2)
	public void tlsConnectionProperties() {

		log.info("TLS channel connection ");

		String s = System.getenv("IBM.MQ.USESSL");
		boolean useSSL = false;
		if (s.equalsIgnoreCase("true")) {
			useSSL = true;
		}
		if (useSSL) {
			assert(System.getenv("IBM.MQ.TRUSTSTORE")) != null;
			assert(System.getenv("IBM.MQ.TRUSTSTOREPASS")) != null;
			assert(System.getenv("IBM.MQ.KEYSTORE")) != null;
			assert(System.getenv("IBM.MQ.KEYSTOREPASS")) != null;
			
		}
	}
	
	@Test
	@Order(3)
	public void queueManagerConnectionTest() {

		log.info("Queue manager connection");

		String mess = "Object ";
		
		try {
			this.qman = new MQMetricsQueueManager();
			this.qman.setConnName(System.getenv("IBM.MQ.HOSTNAME"));
			this.qman.setChannelName(System.getenv("IBM.MQ.CHANNEL"));
			this.qman.setQueueManager(System.getenv("IBM.MQ.QUEUEMANAGER"));
			this.qman.setUserId(System.getenv("IBM.MQ.USERID"));
			this.qman.setPassword(System.getenv("IBM.MQ.PASSWORD"));
			
			mess = "Queue manager";
			MQQueueManager qm = qman.createQueueManager();
			assert (qm) != null;

			mess = "Close ";
			this.qman.CloseConnection(qm, null);
			
		
		} catch (MQException | MQDataException e) {
			log.info("Error: " + mess);
			e.printStackTrace();
		}
		
	}

	
	@Test
	@Order(4)
	public void pcfMessageAgentTest() {

		String mess = "MQ Metrics object";
		
		try {
			this.qman = new MQMetricsQueueManager();
			this.qman.setConnName("localhost(1442)");
			this.qman.setChannelName("MQ.MONITOR.SVRCONN");
			this.qman.setQueueManager("QMAP01");
			this.qman.setUserId("MQMon01");
			this.qman.setPassword("Passw0rd");
			
			mess = "Queue manager object";
			MQQueueManager qm = qman.createQueueManager();
			assert (qm) != null;

			mess = "Message agent object";
			PCFMessageAgent ag = qman.createMessageAgent(qm);
			assert (ag) != null;
			
			mess = "Close ";
			this.qman.CloseConnection(qm, ag);
			
		
		} catch (MQException | MQDataException e) {
			log.info("Error: " + mess);
			e.printStackTrace();
		}
		
	}
	
	
	
}
