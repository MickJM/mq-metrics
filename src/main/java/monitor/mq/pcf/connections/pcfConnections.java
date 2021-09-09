package monitor.mq.pcf.connections;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Tags;
import monitor.mq.metrics.mqmetrics.MQMonitorBase;

@Component
public class pcfConnections {

	private final static Logger log = LoggerFactory.getLogger(pcfConnections.class);
    
	/*
	 * 22/10/2020 MJM - Amended connMap to AtomicLong 
	 */
	private Map<String,AtomicLong>connMap = new HashMap<String,AtomicLong>();
	private String lookupConns = "mq:connections";
	
	private String queueManagerName;
	public void setQueueManagerName() {
    	this.queueManagerName = getMessageAgent().getQManagerName().trim();    	
	}
	public void setQueueManagerName(String v) {
    	this.queueManagerName = v.trim();    	
	}
	
	public String getQueueManagerName() {
		return this.queueManagerName;
	}
	
	
    private PCFMessageAgent messageAgent;
	public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    }
	public PCFMessageAgent getMessageAgent() {
		return this.messageAgent;
	}
    
	@Autowired
	private MQMonitorBase base;

	public pcfConnections() {
	}
	
	@PostConstruct
	private void init() {
    	//setQueueManagerName(getMessageAgent().getQManagerName().trim());    	
		
	}
	
	public void updateConnectionsMetrics() throws MQException, IOException, MQDataException {
		
		log.debug("pcfConnections: inquire on connections");

		/*
		 * **** Dont clear the metrics for these, as we want to see who has been connected ****
		 * Clear the metrics every 'x' iteration
		 */
		//if (base.getCounter() % base.ClearMetrics() == 0) {
		//	log.debug("Clearing connections metrics");
		//	resetMetrics();
		//}

		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CONNECTION);
		
		pcfRequest.addParameter(MQConstants.MQBACF_GENERIC_CONNECTION_ID, new byte[0]);
		pcfRequest.addParameter(MQConstants.MQIACF_CONNECTION_ATTRS, new int[] { MQConstants.MQIA_APPL_TYPE, 
				MQConstants.MQCACF_USER_IDENTIFIER,
				MQConstants.MQIACF_PROCESS_ID, 
				MQConstants.MQCACF_APPL_TAG,
				MQConstants.MQCACH_CHANNEL_NAME,
				MQConstants.MQCACH_CONNECTION_NAME });
		//pcfRequest.addParameter(MQConstants.MQIACF_CONNECTION_ATTRS, MQConstants.MQIACF_ALL);		

		/*
		 * Get a list of connections
		 */
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = getMessageAgent().send(pcfRequest);
		
		} catch (Exception e) {
			log.warn("pcfConnections: no response returned - " + e.getMessage());	

		}
		log.debug("pcfConnections: inquire connections response");

		final Map<String,ConnectionsObject>countsMap = new HashMap<String,ConnectionsObject>();
		//countsMap.clear();
		
		for (PCFMessage pcfMsg : pcfResponse) {
			
			try {

				int applType = pcfMsg.getIntParameterValue(MQConstants.MQIA_APPL_TYPE);

				switch (applType) {
				
					case MQConstants.MQAT_USER: // User process
						increamentCount(pcfMsg, countsMap);
						break;
						
					case MQConstants.MQAT_QMGR: // Queue manager process
						// may add this later
						break;

					case MQConstants.MQAT_CHANNEL_INITIATOR: // Channel initiator process
						// may add this later
						break;
						
					default:
						break;
				}
				
			} catch (Exception e) {
				log.debug("pcfQueue: unable to get queue metrcis : " + e.getMessage());
			}
			
		}

		
		for (ConnectionsObject cso : countsMap.values()) {

			createMetric(cso.getPcfMsg(), cso.getCount());
			
		}	
		
		int x = 0;
	}
	
	/*
	 * Counts
	 */
	private void increamentCount(PCFMessage pcfMsg, Map<String, ConnectionsObject> countsMap) throws PCFException {

		int applType = pcfMsg.getIntParameterValue(MQConstants.MQIA_APPL_TYPE);
		String applTag = pcfMsg.getStringParameterValue(MQConstants.MQCACF_APPL_TAG).trim();
		String user = pcfMsg.getStringParameterValue(MQConstants.MQCACF_USER_IDENTIFIER).trim();
		int procId = pcfMsg.getIntParameterValue(MQConstants.MQIACF_PROCESS_ID);
		String channelName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim();
		String connectionName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).trim();		
		
		String label = lookupConns + "_" + applType + "_" + channelName + "_" + connectionName + "_" + procId;
		ConnectionsObject co = countsMap.get(label);
		if (co == null) {
			ConnectionsObject nco = new ConnectionsObject();
			nco.setCount(1);
			nco.setLabel(label);
			nco.setPcfMsg(pcfMsg);
			countsMap.put(label, nco);
			
		} else {
			int v = co.getCount();
			co.setCount(v+1);
		}
		
	}
	
	/*
	 * Create metric
	 */
	private void createMetric(PCFMessage pcfMsg, int count) throws PCFException {

		int applType = pcfMsg.getIntParameterValue(MQConstants.MQIA_APPL_TYPE);
		String applTag = pcfMsg.getStringParameterValue(MQConstants.MQCACF_APPL_TAG).trim();
		String user = pcfMsg.getStringParameterValue(MQConstants.MQCACF_USER_IDENTIFIER).trim();
		int procId = pcfMsg.getIntParameterValue(MQConstants.MQIACF_PROCESS_ID);
		String channelName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim();
		String connectionName = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).trim();		

		String label = lookupConns + "_" + applType + "_" + channelName + "_" + connectionName + "_" + procId;
		AtomicLong c = connMap.get(label);
		
		if (c == null) {			
			connMap.put(label, base.meterRegistry.gauge(lookupConns, 
				Tags.of("queueManagerName", getQueueManagerName(),
						"appType", "user",
						"tag", applTag,
						"user",user,
						"channelName",channelName,
						"processId",Integer.toString(procId),
						"connectionName",connectionName
						),
				new AtomicLong(count))
				);
		} else {
			c.set(count);
		}
				
	}
	
	/*
	 * Reset metrics
	 */
	public void resetMetrics() {
		log.debug("pcfQueue: resetting metrics");
		deleteMetrics();
	}
	
	/*
	 * Clear the metrics ....
	 * 
	 */
	private void deleteMetrics() {

		base.DeleteMetricEntry(lookupConns);

	    this.connMap.clear();
		
	}
	
}
