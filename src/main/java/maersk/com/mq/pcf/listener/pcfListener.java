package maersk.com.mq.pcf.listener;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Get listener details
 * 
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQPCFConstants;

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
    private Map<String,AtomicInteger>listenerMap = new HashMap<String,AtomicInteger>();

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
		if (!(getDebugLevel() == LEVEL.NONE)) { log.info("pcfListener: Object created"); }
    	setTypeList();
    }

    /*
     * When the class is fully created ...
     */
    @PostConstruct
    private void PostMethod() {
    	log.info("Excluding listeners ;");
    	for (String s : this.excludeListeners) {
    		log.info(s);
    	}
    }
    
    /*
     * Get the listeners ... 
     */
	public void UpdateListenerMetrics() throws MQException, IOException, MQDataException {

		if (getDebugLevel() == LEVEL.DEBUG
				|| (getDebugLevel() == LEVEL.TRACE))  { log.info("pcfListener: inquire listener request"); }
		
		/*
		 * Clear the metrics every 'x' iteration
		 */
		this.clearMetrics++;
		if (this.clearMetrics % CONST_CLEARMETRICS == 0) {
			this.clearMetrics = 0;
			if (getDebugLevel() == LEVEL.TRACE) {
				log.trace("Clearing listener metrics");
			}
			resetMetrics();
		}
		
		/*
		 * Get a list of listeners
		 */
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER);
		pcfRequest.addParameter(MQConstants.MQCACH_LISTENER_NAME, "*");
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		pcfRequest.addParameter(MQConstants.MQIACF_LISTENER_ATTRS, pcfParmAttrs);
		
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = this.messageAgent.send(pcfRequest);

		} catch (Exception e) {
			if (getDebugLevel() == LEVEL.WARN) { log.warn("pcfListener: no response returned - " + e.getMessage()); }
			
		}
		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfListener: inquire listener response"); }
        int[] pcfStatAttrs = { 	MQConstants.MQIACF_ALL };
        
		/*
		 * For each listener, process the message 
		 */
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
						if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfListener: valid type"); }
	
						PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS);
						pcfReq.addParameter(MQConstants.MQCACH_LISTENER_NAME, listenerName);
						pcfReq.addParameter(MQConstants.MQIACF_LISTENER_STATUS_ATTRS, pcfStatAttrs);
				        PCFMessage[] pcfResp = null;
				        
				        /*
				         * Listener status
				         */
						try {			
							pcfResp = this.messageAgent.send(pcfReq);
							int listenerStatus = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_LISTENER_STATUS);					
							portNumber = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_PORT);
							type = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_XMIT_PROTOCOL_TYPE);

							AtomicInteger qmListener = listenerMap.get(lookupListener + "_" + this.queueManager);
							if (qmListener == null) {
								listenerMap.put(lookupListener + "_" + this.queueManager, meterRegistry.gauge(lookupListener, 
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type", Integer.toString(type),
												"port", Integer.toString(portNumber)),
										new AtomicInteger(listenerStatus))
										);
							} else {
								qmListener.set(listenerStatus);
							}

						} catch (PCFException pcfe) {
							if (pcfe.reasonCode == MQConstants.MQRCCF_LSTR_STATUS_NOT_FOUND) {
								AtomicInteger qmListener = listenerMap.get(lookupListener + "_" + this.queueManager);
								if (qmListener == null) {
									listenerMap.put(lookupListener + "_" + this.queueManager, meterRegistry.gauge(lookupListener, 
											Tags.of("queueManagerName", this.queueManager,
													"listenerName", listenerName,
													"type", Integer.toString(type),
													"port", Integer.toString(portNumber)),
											new AtomicInteger(MQPCFConstants.PCF_INIT_VALUE))
											);
								} else {
									qmListener.set(MQPCFConstants.PCF_INIT_VALUE);
								}
							}
							if (pcfe.reasonCode == MQConstants.MQRC_UNKNOWN_OBJECT_NAME) {								
								AtomicInteger qmListener = listenerMap.get(lookupListener + "_" + this.queueManager);
								if (qmListener == null) {
									listenerMap.put(lookupListener + "_" + this.queueManager, meterRegistry.gauge(lookupListener, 
											Tags.of("queueManagerName", this.queueManager,
													"listenerName", listenerName,
													"type", Integer.toString(type),
													"port", Integer.toString(portNumber)),
											new AtomicInteger(MQPCFConstants.PCF_INIT_VALUE))
											);
								} else {
									qmListener.set(MQPCFConstants.PCF_INIT_VALUE);
								}
							}
	
							
						} catch (Exception e) {
							AtomicInteger qmListener = listenerMap.get(lookupListener + "_" + this.queueManager);
							if (qmListener == null) {
								listenerMap.put(lookupListener + "_" + this.queueManager, meterRegistry.gauge(lookupListener, 
										Tags.of("queueManagerName", this.queueManager,
												"listenerName", listenerName,
												"type", Integer.toString(type),
												"port", Integer.toString(portNumber)),
										new AtomicInteger(MQPCFConstants.PCF_INIT_VALUE))
										);
							} else {
								qmListener.set(MQPCFConstants.PCF_INIT_VALUE);
							}
						}				
					}
				}
	        }
		} catch (Exception e) {
			if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfListener: unable to get listener metrcis " + e.getMessage()); }
			
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

	/*
	 * Allow access to delete the metrics
	 */
	public void resetMetrics() {
		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfListener: resetting metrics"); }
		deleteMetrics();
	}
	
	/*
	 * Delete metrics
	 */
	private void deleteMetrics() {
		deleteMetricEntry(lookupListener);
		this.listenerMap.clear();
	}	
	
}
