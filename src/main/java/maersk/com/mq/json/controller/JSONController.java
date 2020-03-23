package maersk.com.mq.json.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Meter.Id;

import maersk.com.mq.json.entities.*;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQBase.LEVEL;

@RestController
@ComponentScan
@RequestMapping(value="/json")
public class JSONController extends MQBase {
	//@GetMapping("/json")

	static Logger log = Logger.getLogger(JSONController.class);

	@Autowired
	public MeterRegistry meterRegistry;

	@Value("${ibm.mq.json.sort:false}")	
	private boolean sort;
	
	@Value("${ibm.mq.json.order:ascending}")	
	private String order;
	
	@RequestMapping(method=RequestMethod.GET, value="/getallmetrics", produces={"application/json"})
	public ResponseEntity<Object> allmetrics() {

		if (getDebugLevel() == LEVEL.DEBUG) { log.debug("REST JSON API invoked"); }
		
		List<Object> entities = new ArrayList<Object>();
		
		List<Metric> metrics = new ArrayList<Metric>();
		MetricType mt = new MetricType();
		mt.setName("metrics");
	
		/*
		 * Get all metrics, including system metrics
		 */
		List<Meter.Id> filter = this.meterRegistry.getMeters().stream()
		        .map(Meter::getId)
		        .collect(Collectors.toList());
		
		Iterator<Id> list = filter.iterator();
		while (list.hasNext()) {
			Meter.Id id = list.next();
			Metric m = new Metric();
			m.setName(id.getName());
			
			List<Tag> tags = id.getTags();
			if (tags != null) {
				m.tags = tags;
			}
	
			checkType(metrics, id, m, tags);
		}
		
		mt.setValue(metrics);
        entities.add(mt);
		return new ResponseEntity<Object>(entities, HttpStatus.OK);
	}

	@RequestMapping(method=RequestMethod.GET, value="/getmqmetrics", produces={"application/json"})
	public ResponseEntity<Object> mqmetrics() {

		if (getDebugLevel() == LEVEL.DEBUG) { log.debug("REST MQ JSON API invoked"); }
		
		List<Object> entities = new ArrayList<Object>();
		
		List<Metric> metrics = new ArrayList<Metric>();
		MetricType mt = new MetricType();
		mt.setName("metrics");

		/*
		 * Collect only MQ specific metrics
		 */
		List<Meter.Id> filter = this.meterRegistry.getMeters()
				.stream()
				.map(Meter::getId)
		        .filter(m->m.getName().startsWith("mq:"))
		        .collect(Collectors.toList());
	
		/*
		 * Sort, if we have requred it
		 */
		if (this.sort) {
			if (this.order.isEmpty() || this.order == null) {
				this.order = "ascending";
			}
			Comparator<Meter.Id> byType = (Id a, Id b) -> (a.getName().compareTo(b.getName()));
			if (this.order.equals("ascending")) {
				Collections.sort(filter, byType);
			}
			if (this.order.equals("descending")) {
				Collections.sort(filter, byType.reversed());
			}
		}
		
		Iterator<Id> list = filter.iterator();
		while (list.hasNext()) {
			Meter.Id id = list.next();
			Metric m = new Metric();
			m.setName(id.getName());
			
			List<Tag> tags = id.getTags();
			if (tags != null) {
				m.tags = tags;
			}
	
			checkType(metrics, id, m, tags);
		}
		
		mt.setValue(metrics);
        entities.add(mt);
		return new ResponseEntity<Object>(entities, HttpStatus.OK);

	}
	
	/*
	 * Check the metric type
	 */
	private void checkType(List<Metric> metrics, Id id, Metric m, List<Tag> tags) {

		switch (id.getType()) {
		
			case GAUGE:
				Guage(id, m, tags);
				break;
				
			case COUNTER:
				Counter(id, m, tags);
				break;

			case TIMER:
				Timer(id, m, tags);
				break;
				
			default:
				log.warn("Metric type invalid: type is : " + id.getType().name());
				break;
			
		}
		metrics.add(m);
	}


	/*
	 * Guage metric
	 */
	private void Guage(Id id, Metric m, List<Tag> tags) {
		
		Gauge g = this.meterRegistry.find(id.getName()).tags(tags).gauge();
		try {
			m.setValue(g.value());
			
		} catch (Exception e) {
			m.setValue(0);
		}
		
	}

	/*
	 * Counter
	 */
	private void Counter(Id id, Metric m, List<Tag> tags) {

		Counter c = this.meterRegistry.find(id.getName()).tags(tags).counter();					
		try {
			m.setValue(c.count());
			
		} catch (Exception e) {
			m.setValue(0);
		}
		
	}

	/*
	 * Timer
	 */
	private void Timer(Id id, Metric m, List<Tag> tags) {

		Timer t = this.meterRegistry.find(id.getName()).tags(tags).timer();
		try {
			m.setValue(t.count());
			
		} catch (Exception e) {
			m.setValue(0);
		}
		
	}
	
}

