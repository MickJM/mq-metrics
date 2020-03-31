package maersk.com.mq.metrics.mqmetrics;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.text.ParseException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;

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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;
import maersk.com.mq.json.entities.Metric;

//@ActiveProfiles("test")
//@SpringBootApplication

@RunWith(SpringRunner.class)
@SpringBootTest(classes = { MQMetricsApplication.class })
@Component
public class MQMetricsApplicationTests {

	static Logger log = Logger.getLogger(MQMetricsApplicationTests.class);
		
	@Autowired
	private MQMetricsQueueManager qman;
	public MQMetricsQueueManager getQueMan() {
		return this.qman;
	}
	
	@Autowired
	private MQConnection conn;
	
	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	@Order(1)
	public void findGaugeMetrics() {
		
		String mess = "";
		
		conn.scheduler();
		try {
			
			mess = "Getting metrics";
			conn.getMetrics();

			List<Meter.Id> filter = this.meterRegistry.getMeters().stream()
			        .map(Meter::getId)
			        .collect(Collectors.toList());

			Comparator<Meter.Id> byType = (Id a, Id b) -> (a.getName().compareTo(b.getName()));
			Collections.sort(filter, byType);
			
			Iterator<Id> list = filter.iterator();
			while (list.hasNext()) {
				Meter.Id id = list.next();
				Metric m = new Metric();
				m.setName(id.getName());
				
				List<Tag> tags = id.getTags();
				
				if (id.getType() == Meter.Type.GAUGE) {
					Gauge g = this.meterRegistry.find(id.getName()).tags(tags).gauge();
					assert(g) != null;
				}
			}
			
		} catch (MQException | IOException | MQDataException | ParseException e) {
			log.info("Error: " + mess);
			e.printStackTrace();

		}
		
	}
	
	@Test
	@Order(2)
	public void testConnectionToTheQueueManager() {

		log.info("Queue manager connection");
		String mess = "";
		
		try {
			
			mess = "Queue manager";
			MQQueueManager qm = getQueMan().createQueueManager();
			assert (qm) != null;
			
		} catch (Exception e) {
			log.info("Error: " + mess);
			e.printStackTrace();
			
		}
	}

	
	@Test
	@Order(3)
	public void createPCFMessageAgent() {

		log.info("Queue manager connection");
		String mess = "";
		
		try {
			
			mess = "Queue manager";
			MQQueueManager qm = getQueMan().createQueueManager();
			assert (qm) != null;

			mess = "PCF Agent";
			PCFMessageAgent ag = getQueMan().createMessageAgent(qm);
			assert (ag) != null;
			
			int qmgrAcct = getQueMan().getAccounting();
			
		} catch (Exception e) {
			log.info("Error: " + mess);
			e.printStackTrace();
			
		}
	}
}
