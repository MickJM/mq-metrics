package maersk.com.mq.pcf.listener;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;

@Component
public class pcfListener extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private String queueManager;
    private PCFMessageAgent messageAgent;
    
    /*
     * Set a list of listener types and values
     */
    private Map<Integer,String>typeList;    
    private void setTypeList() {
    	Map<Integer,String>list = new HashMap<Integer, String>();
        list.put(MQConstants.MQXPT_LU62, "LU62");
        list.put(MQConstants.MQXPT_TCP, "TCP");
        list.put(MQConstants.MQXPT_NETBIOS, "NETBIOS");
        list.put(MQConstants.MQXPT_SPX, "SPX");        
    	this.typeList = list;
    	
    }
    
    /*
     * Set the MQ message agent and obtain the queue manager name
     */
    public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    	setTypeList();
    }

    //Listener status maps
    private Map<String,AtomicInteger>listenerStatusMap = new HashMap<String, AtomicInteger>();
    
	protected static final String lookupListener = MQPREFIX + "listenerStatus";

    // Listeners ...
	@Value("${ibm.mq.objects.listeners.exclude}")
    private String[] excludeListeners;
	@Value("${ibm.mq.objects.listeners.include}")
    private String[] includeListeners;
	
	@Value("${ibm.mq.objects.listeners.types.exclude}")
    private String[] excludeTypes;
	@Value("${ibm.mq.objects.listeners.types.include}")
    private String[] includeTypes;

	// Constructor
    public pcfListener() {
    	setTypeList();
    }

    /*
     * Get the listeners ...
     * 
     */
	public void UpdateListenerMetrics() throws MQException, IOException, MQDataException {

		if (this._debug) { log.info("pcfListener: inquire listener request"); }

		resetMetric();
		
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER);
		pcfRequest.addParameter(MQConstants.MQCACH_LISTENER_NAME, "*");
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		pcfRequest.addParameter(MQConstants.MQIACF_LISTENER_ATTRS, pcfParmAttrs);
		
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = this.messageAgent.send(pcfRequest);

		} catch (Exception e) {
			if (this._debug) { log.warn("pcfListener: no response returned - " + e.getMessage()); }
			
		}
		if (this._debug) { log.info("pcfListener: inquire listener response"); }
        
        int[] pcfStatAttrs = { 	MQConstants.MQIACF_ALL };
		// For each response back, loop to process 
		
        try {
	        for (PCFMessage pcfMsg : pcfResponse) {	
				int portNumber = MQPCFConstants.BASE;
				int type = MQPCFConstants.NOTSET;
				String listenerName = 
						pcfMsg.getStringParameterValue(MQConstants.MQCACH_LISTENER_NAME).trim(); 
				int listType = 
						pcfMsg.getIntParameterValue(MQConstants.MQIACH_XMIT_PROTOCOL_TYPE);
				String typeName = typeList.get(listType).trim();			
				if (checkListenNames(listenerName.trim())) {
					
					// Correct listener type ? Only interested in TCP
					if (checkType(typeName)) {
						if (this._debug) { log.info("pcfListener: valid type"); }
	
						PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS);
						pcfReq.addParameter(MQConstants.MQCACH_LISTENER_NAME, listenerName);
						pcfReq.addParameter(MQConstants.MQIACF_LISTENER_STATUS_ATTRS, pcfStatAttrs);
		
				        PCFMessage[] pcfResp = null;
						try {			
							pcfResp = this.messageAgent.send(pcfReq);
							int listenerStatus = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_LISTENER_STATUS);					
							portNumber = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_PORT);
							type = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_XMIT_PROTOCOL_TYPE);
							meterRegistry.gauge(lookupListener, 
									Tags.of("queueManagerName", this.queueManager,
											"listenerName", listenerName,
											"type", Integer.toString(type),
											"port", Integer.toString(portNumber))
									,listenerStatus);

							/*
							AtomicInteger l = listenerStatusMap.get(listenerName);
							if (l == null) {
								listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("listenerStatus").toString(), 
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type", Integer.toString(type),
												"port", Integer.toString(portNumber))
										, new AtomicInteger(listenerStatus)));
							} else {
								l.set(listenerStatus);
							}
							*/
							
						} catch (PCFException pcfe) {
							if (pcfe.reasonCode == MQConstants.MQRCCF_LSTR_STATUS_NOT_FOUND) {
								meterRegistry.gauge(lookupListener, 
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type", Integer.toString(type),
												"port", Integer.toString(portNumber))
										,MQPCFConstants.PCF_INIT_VALUE);
								
								/*
								AtomicInteger l = listenerStatusMap.get(listenerName);
								if (l == null) {
									listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
											.append(MQPREFIX)
											.append("listenerStatus").toString(), 
											Tags.of("queueManagerName", this.queueManager,
													"listenerName", listenerName,
													"type",Integer.toString(listType),
													"port", Integer.toString(portNumber))
											, new AtomicInteger(MQPCFConstants.PCF_INIT_VALUE)));
								} else {
									l.set(MQPCFConstants.PCF_INIT_VALUE);
								}
								*/
							}
							if (pcfe.reasonCode == MQConstants.MQRC_UNKNOWN_OBJECT_NAME) {								
								meterRegistry.gauge(lookupListener, 
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type", Integer.toString(type),
												"port", Integer.toString(portNumber))
										,MQPCFConstants.PCF_INIT_VALUE);
								
								/*
								AtomicInteger l = listenerStatusMap.get(listenerName);
								if (l == null) {
									listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
											.append(MQPREFIX)
											.append("listenerStatus").toString(), 
											Tags.of("queueManagerName", this.queueManager,
													"listenerName", listenerName,
													"type",Integer.toString(listType),
													"port", Integer.toString(portNumber))
											, new AtomicInteger(MQPCFConstants.PCF_INIT_VALUE)));
								} else {
									l.set(MQPCFConstants.PCF_INIT_VALUE);
								}
								*/
							}
	
							
						} catch (Exception e) {
							meterRegistry.gauge(lookupListener, 
									Tags.of("queueManagerName", this.queueManager,
											"listenerName", listenerName,
											"type", Integer.toString(type),
											"port", Integer.toString(portNumber))
									,MQPCFConstants.PCF_INIT_VALUE);

							/*
							AtomicInteger l = listenerStatusMap.get(listenerName);
							if (l == null) {
								listenerStatusMap.put(listenerName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("listenerStatus").toString(),
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type", Integer.toString(type),
												"port", Integer.toString(portNumber))
										, new AtomicInteger(MQPCFConstants.PCF_INIT_VALUE)));
							} else {
								l.set(MQPCFConstants.PCF_INIT_VALUE);
							}
							*/
						}				
					}
				}
	        }
		} catch (Exception e) {
			if (this._debug) { log.warn("pcfListener: unable to get listener metrcis " + e.getMessage()); }
			
		}
	}
	
	/*
	 * Check the listener from the environment variables
	 */
	private boolean checkListenNames(String name) {

		// Exclude ...
		for (String s : this.excludeListeners) {
			if (s.equals("*")) {
				break;
			} else {
				if (name.startsWith(s)) {
					return false;
				}
			}
		}
	
		// Check listeners against the list 
		for (String s : this.includeListeners) {
			if (s.equals("*")) {
				return true;
			} else {
				if (name.startsWith(s)) {
					return true;
				}				
			}
		}		
		return false;
		
	}
    
	/*
	 * Check the type of the listener
	 */
	private boolean checkType(String type) {

		// Exclude ...
		for (String s : this.excludeTypes) {
			if (s.equals("*")) {
				break;
			} else {
				if (type.startsWith(s)) {
					return false;
				}
			}
		}

		// Check listeners against the list 
		for (String s : this.includeTypes) {
			if (s.equals("*")) {
				return true;
			} else {
				if (type.startsWith(s)) {
					return true;
				}				
			}
		}		
		
		return false;

	}

	
	// Not running
	//public void NotRunning() {
	//	SetMetricsValue(0);
	//}

	public void resetMetric() {
		DeleteMetricEntry(lookupListener);

	}

	// If the queue manager is not running, set any listeners state not running
	public void SetMetricsValue(int val) {

		
		// For each listener, set the status to indicate its not running, as the ...
		// ... queue manager is not running
		Iterator<Entry<String, AtomicInteger>> listListener = this.listenerStatusMap.entrySet().iterator();
		while (listListener.hasNext()) {
	        Map.Entry pair = (Map.Entry)listListener.next();
	        String key = (String) pair.getKey();
	        try {
				AtomicInteger i = (AtomicInteger) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        }
		}
		
	}
	
}
