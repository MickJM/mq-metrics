package maersk.com.mq.metrics.mqmetrics;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;

public class MQBase implements MQPCFConstants {

	@Autowired
	public MeterRegistry meterRegistry;

	protected static final String MQPREFIX = "mq:";

	//@Value("${application.debug:false}")
    //protected boolean _debug;
	
	//@Value("${application.debugLevel:DEBUG}")
	//protected String _debugLevel;
	
	protected LEVEL lev;
	public enum LEVEL {	
		NONE,
		INFO,
		DEBUG,
		WARN,
		ERROR,
		TRACE
	}

	
	protected void setDebugLevel(String level) {
		this.lev = LEVEL.valueOf(level);
	}
	
	public LEVEL getDebugLevel() {
		return this.lev;
	}
	
	@Value("${ibm.mq.clearMetrics:10}")
	protected int CONST_CLEARMETRICS;

	protected int clearMetrics;

	
	/*
	 * Delete the appropriate metric
	 */
	protected void deleteMetricEntry(String lookup) {
		
		List<Meter.Id> meterIds = null;
		meterIds = this.meterRegistry.getMeters().stream()
		        .map(Meter::getId)
		        .collect(Collectors.toList());
		
		Iterator<Id> list = meterIds.iterator();
		while (list.hasNext()) {
			Meter.Id id = list.next();
			if (id.getName().contains(lookup)) {
				this.meterRegistry.remove(id);
			}
		}
		
	}

	/*
	 * Find the appropriate metric
	 */
	protected Meter.Id FindMetricEntry(String lookup) {
		
		List<Meter.Id> meterIds = null;
		meterIds = this.meterRegistry.getMeters().stream()
		        .map(Meter::getId)
		        .collect(Collectors.toList());
		
		Iterator<Id> list = meterIds.iterator();
		Meter.Id id = null;
		
		while (list.hasNext()) {
			id = list.next();
			if (id.getName().contains(lookup)) {
				//this.meterRegistry.remove(id);
				break;
			}
		}
		
		return id; 
	}

}
