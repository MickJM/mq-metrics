package maersk.com.mq.metrics.mqmetrics;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;

public class MQBase {

	@Autowired
	public MeterRegistry meterRegistry;

	protected static final String MQPREFIX = "mq:";

	@Value("${application.debug:false}")
    protected boolean _debug;


	public interface MQPCFConstants {
		
		public int BASE = 0;
		public int PCF_INIT_VALUE = 0;

		public int NOTSET = -1;
	}
	
	protected void DeleteMetricEntry(String lookup) {
		
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

}
