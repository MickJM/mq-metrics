package maersk.com.mq.metrics.mqmetrics;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;
import maersk.com.mq.json.entities.Metric;
import maersk.com.mq.pcf.listener.pcfListener;

//@ActiveProfiles("test")
//@SpringBootApplication

@RunWith(SpringRunner.class)
@SpringBootTest(classes = { MQMetricsApplication.class })
@Component
@ActiveProfiles("test")
public class MQMetricsApplicationTests {

	private final static Logger log = LoggerFactory.getLogger(MQMetricsApplicationTests.class);
		
	//@Autowired
	//private MQMetricsQueueManager qman;
	//public MQMetricsQueueManager getQueMan() {
	//	return this.qman;
	//}
	
	@Autowired
	private MQConnection conn;
	
	@Autowired
	private MeterRegistry meterRegistry;
	
	@Value("${ibm.mq.queueManager}")
	private String queueManager;
	public void setQueueManager(String v) {
		this.queueManager = v;
	}
	public String getQueueManagerName() { return this.queueManager; }
	
	@Test
	@Order(1)
	public void testConnectionToTheQueueManager() throws InterruptedException, MQException  {

		log.info("Attempting to connect to {}", getQueueManagerName());		
		Thread.sleep(2000);

		MQQueueManager qm = conn.getMQQueueManager();
		assert (conn) != null;
		
		assert (conn.getReasonCode() != MQConstants.MQRC_NOT_AUTHORIZED) : "Not authorised to access the queue manager";
		assert (conn.getReasonCode() != MQConstants.MQRC_ENVIRONMENT_ERROR) : "An environment error has been detected, the most likely cause is trying to connect using a password greater than 12 characters";
		assert (conn.getReasonCode() != MQConstants.MQRC_HOST_NOT_AVAILABLE) : "MQ host is not available";
		assert (conn.getReasonCode() != MQConstants.MQRC_UNSUPPORTED_CIPHER_SUITE) : "TLS unsupported cipher";
		assert (conn.getReasonCode() != MQConstants.MQRC_JSSE_ERROR) : "JSSE error - most likely cause being that certificates are wrong or have expired";
		assert (conn.getReasonCode() == 0) : "MQ error occurred" ;
		
	}

	
	@Test
	@Order(2)
	public void testFindGaugeMetrics() throws MQDataException, ParseException, 
			MQException, IOException, InterruptedException {
		
		log.info("Attempting to connect to {}", getQueueManagerName());		
		Thread.sleep(2000);
		
		conn.getMQQueueManager();
		conn.getMetrics();
		
		List<Meter.Id> filter = this.meterRegistry.getMeters().stream()
		        .map(Meter::getId)
		        .collect(Collectors.toList());

		Comparator<Meter.Id> byType = (Id a, Id b) -> (a.getName().compareTo(b.getName()));
		Collections.sort(filter, byType);
		
		Iterator<Id> list = filter.iterator();
		assert(list) != null : "no metrics were returned";
		
		int mqMetrics = 0;
		while (list.hasNext()) {
			Meter.Id id = list.next();
			if (id.getName().startsWith("mq:")) {
				mqMetrics++;
			}
			/*
			 * 			
			Metric m = new Metric();
			m.setName(id.getName());

			List<Tag> tags = id.getTags();
			
			if (id.getType() == Meter.Type.GAUGE) {
				Gauge g = this.meterRegistry.find(id.getName()).tags(tags).gauge();
				assert(g) != null;
			}
			*/
		}
		assert (mqMetrics > 0) : "No mq: metrics generated";
		
	}
	
	
}
