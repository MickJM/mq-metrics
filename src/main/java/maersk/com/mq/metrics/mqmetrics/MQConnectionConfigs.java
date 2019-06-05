package maersk.com.mq.metrics.mqmetrics;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.pcf.PCFMessageAgent;

@Configuration
public class MQConnectionConfigs {
	
	@Bean
	public Map<Integer, String>typeList()  {

		Map<Integer, String>list = new HashMap<Integer, String>();
        list.put(MQConstants.MQXPT_LU62, "LU62");
        list.put(MQConstants.MQXPT_TCP, "TCP");
        list.put(MQConstants.MQXPT_NETBIOS, "NETBIOS");
        list.put(MQConstants.MQXPT_SPX, "SPX");        
        return list;
	    
	}
	
	
}
