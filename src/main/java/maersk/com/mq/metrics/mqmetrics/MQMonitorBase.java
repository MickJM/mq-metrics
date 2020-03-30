package maersk.com.mq.metrics.mqmetrics;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class MQMonitorBase implements MQPCFConstants {

	@Autowired
	public MeterRegistry meterRegistry;

	//protected static final String MQPREFIX = "mq:";

	@Value("${application.debug:false}")
    protected boolean _debug;
	
	@Value("${application.debugLevel:DEBUG}")
	protected String _debugLevel;
	
	protected int lev;
	//public enum LEVEL {	
	//	NONE,
	//	INFO,
	//	DEBUG,
	//	WARN,
	//	ERROR,
	//	TRACE
	//}

	
	//protected void setDebugLevel(String level) {
	//	this.lev = LEVEL.valueOf(level);
	//}
	
	
	@Value("${ibm.mq.clearMetrics:10}")
	private int CONST_CLEARMETRICS;
	public int getClearMetrics() {
		return this.CONST_CLEARMETRICS;
	}
	
	protected int clearMetrics;
	public synchronized void setCounter(int v) {
		this.clearMetrics = v;
	}
	public synchronized void setCounter() {
		this.clearMetrics++;
	}
	public synchronized int getCounter() {
		return this.clearMetrics;
	}

	public int getDebugLevel() {
		return this.lev;
	}

	@PostConstruct
	private void setDebug() {

		if (this._debug) {
			if (this._debugLevel.equals("NONE")) {
					this._debugLevel = "DEBUG";
			}
		}
		if (!this._debug) {
			this._debugLevel = "NONE";
		}
		
		switch (this._debugLevel) {
			case "NONE":
				this.lev = NONE;
				break;
			case "INFO":
				this.lev = INFO;
				break;
			case "DEBUG":
				this.lev = DEBUG;
				break;
			case "WARN":
				this.lev = WARN;
				break;
			case "ERROR":
				this.lev = ERROR;
				break;
			case "TRACE":
				this.lev = TRACE;
				break;
			
			default:
				this.lev = NONE;
				break;
		}
		
	}
	
	/*
	 * Delete the appropriate metric
	 */
	public void deleteMetricEntry(String lookup) {
		
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
