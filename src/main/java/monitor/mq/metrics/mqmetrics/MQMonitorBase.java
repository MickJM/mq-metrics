package monitor.mq.metrics.mqmetrics;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

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

	//@Value("${application.debug:false}")
    //protected boolean _debug;
	
	//@Value("${application.debugLevel:DEBUG}")
	//protected String _debugLevel;
	
	protected int lev;
	
	@Value("${ibm.mq.clearMetrics:10}")
	private int const_clearmetrics;
	public int ClearMetrics() {
		return this.const_clearmetrics;
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

	public static int CPUMETRIC = 0;
	public static int SYSTEMMETRIC = 1;
	
	/*
	 * Delete the appropriate metric
	 */
	public void DeleteMetricEntry(String lookup) {
		
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
