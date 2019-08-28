package maersk.com.mq.metrics.mqmetrics;

import org.springframework.beans.factory.annotation.Value;

public class MQBase {

	protected static final String MQPREFIX = "mq:";

	@Value("${application.debug:false}")
    protected boolean _debug;
	
	public interface MQPCFConstants {
		
		public int BASE = 0;
		public int PCF_INIT_VALUE = 0;

		public int NOTSET = -1;
	}
}
