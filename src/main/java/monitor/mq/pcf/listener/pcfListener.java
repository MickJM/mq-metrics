package monitor.mq.pcf.listener;

/*
 * Copyright 2020
 *
 * Get listener details
 * 
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Tags;
import monitor.mq.metrics.mqmetrics.MQMonitorBase;
import monitor.mq.metrics.mqmetrics.MQPCFConstants;

@Component
public class pcfListener {

    private final static Logger log = LoggerFactory.getLogger(pcfListener.class);

	private String queueManager;
    public void setQueueManagerName() {
    	this.queueManager = getMessageAgent().getQManagerName().trim();    	
    	setTypeList();
    }
	public String getQueueManagerName() {
		return this.queueManager;
	}
    private PCFMessageAgent messageAgent;
    private PCFMessageAgent getMessageAgent() {
    	return this.messageAgent;
    }
    public void setMessageAgent(PCFMessageAgent v) {
    	this.messageAgent = v;
    }
    
    private String lookupListener = "mq:listenerStatus";
    
    /*
     * 22/10/2020 MJM - Amended listenerStatusMap to AtomicLong
     */
    private Map<String,AtomicLong>listenerMap = new HashMap<String,AtomicLong>();

    // Listeners ...
	@Value("${ibm.mq.objects.listeners.exclude}")
    private String[] excludeListeners;
	@Value("${ibm.mq.objects.listeners.include}")
    private String[] includeListeners;
	
	@Value("${ibm.mq.objects.listeners.types.exclude}")
    private String[] excludeTypes;
	@Value("${ibm.mq.objects.listeners.types.include}")
    private String[] includeTypes;

	@Autowired
	private MQMonitorBase base;
	
	// Constructor
    public pcfListener() {
    }

    /*
     * When the class is fully created ...
     */
    @PostConstruct
    private void init() {
		log.info("pcfListener: Object created");
    	setTypeList();
    	
    	log.debug("Excluding listeners ;");
    	for (String s : this.excludeListeners) {
    		log.debug(s);
    	}
    	
    }
    /*
     * Set a list of listener types and values
     */
    private Map<Integer,String>typeList;    
    private void setTypeList() {
    	Map<Integer,String>list = new HashMap<Integer, String>();
        list.put(MQConstants.MQXPT_ALL, "All");
    	list.put(MQConstants.MQXPT_LOCAL, "Local");
    	list.put(MQConstants.MQXPT_LU62, "LU62");
        list.put(MQConstants.MQXPT_TCP, "TCP");
        list.put(MQConstants.MQXPT_NETBIOS, "NETBIOS");
        list.put(MQConstants.MQXPT_SPX, "SPX");
        list.put(MQConstants.MQXPT_DECNET, "DecNet");
        list.put(MQConstants.MQXPT_UDP, "UDP");
        list.put(-2, "Unknown");

    	this.typeList = list;
    	
    }
    
    
    /*
     * Get the listeners ... 
     */
	public void UpdateListenerMetrics() throws MQException, IOException, MQDataException {

		log.debug("pcfListener: inquire listener request");
		
		/*
		 * Clear the metrics every 'x' iteration
		 */
		if (base.getCounter() % base.ClearMetrics() == 0) {
			log.debug("Clearing listener metrics");
			ResetMetrics();
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
			pcfResponse = getMessageAgent().send(pcfRequest);

		} catch (Exception e) {
			log.warn("pcfListener: no response returned - " + e.getMessage());
			
		}
		log.debug("pcfListener: inquire listener response");
        int[] pcfStatAttrs = { 	MQConstants.MQIACF_ALL };
        
		/*
		 * For each listener, process the message 
		 */
        try {
	        for (PCFMessage pcfMsg : pcfResponse) {	
				int portNumber = MQPCFConstants.BASE;
				//int portNumber = pcfMsg.getIntParameterValue(MQConstants.MQIACH_PORT_NUMBER);

				int type = MQPCFConstants.NOTSET;
				String listenerName = 
						pcfMsg.getStringParameterValue(MQConstants.MQCACH_LISTENER_NAME).trim(); 
				
				int listType = 
						pcfMsg.getIntParameterValue(MQConstants.MQIACH_XMIT_PROTOCOL_TYPE);
				String typeName = typeList.get(listType).trim();			
				
				if (checkListenNames(listenerName.trim())) {
					
					// Correct listener type ? Only interested in TCP
					if (checkType(typeName)) {
						log.debug("pcfListener: valid type");
	
						PCFMessage pcfReq = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS);
						pcfReq.addParameter(MQConstants.MQCACH_LISTENER_NAME, listenerName);
						pcfReq.addParameter(MQConstants.MQIACF_LISTENER_STATUS_ATTRS, pcfStatAttrs);
				        PCFMessage[] pcfResp = null;
				        
				        /*
				         * Listener status
				         */
						try {			
							pcfResp = getMessageAgent().send(pcfReq);
							int listenerStatus = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_LISTENER_STATUS);					
							portNumber = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_PORT);
						//	type = pcfResp[0].getIntParameterValue(MQConstants.MQIACH_XMIT_PROTOCOL_TYPE);
						//	String protocol = this.typeList.get(type);
							
							AtomicLong qmListener = listenerMap.get(lookupListener + "_" + listenerName );
							if (qmListener == null) {
								listenerMap.put(lookupListener + "_" + listenerName, base.meterRegistry.gauge(lookupListener, 
										Tags.of("queueManagerName", getQueueManagerName(),
												"listenerName", listenerName,
		//										"type", Integer.toString(type),
												"type", typeName,									
												"port", Integer.toString(portNumber)),
										new AtomicLong(listenerStatus))
										);
							} else {
								qmListener.set(listenerStatus);
							}

						} catch (PCFException pcfe) {
							if (pcfe.reasonCode == MQConstants.MQRCCF_LSTR_STATUS_NOT_FOUND) {
								//String protocol = this.typeList.get(-2);
								AtomicLong qmListener = listenerMap.get(lookupListener + "_" + listenerName);
								if (qmListener == null) {
									listenerMap.put(lookupListener + "_" + listenerName, 
											base.meterRegistry.gauge(lookupListener, 
											Tags.of("queueManagerName", getQueueManagerName(),
													"listenerName", listenerName,
													"type", typeName,
													"port", Integer.toString(portNumber)),
											new AtomicLong(MQPCFConstants.PCF_INIT_VALUE))
											);
								} else {
									qmListener.set(MQPCFConstants.PCF_INIT_VALUE);
								}
							}
							if (pcfe.reasonCode == MQConstants.MQRC_UNKNOWN_OBJECT_NAME) {								
								//String protocol = this.typeList.get(-2);								
								AtomicLong qmListener = listenerMap.get(lookupListener + "_" + listenerName);
								if (qmListener == null) {
									listenerMap.put(lookupListener + "_" + listenerName, 
											base.meterRegistry.gauge(lookupListener, 
											Tags.of("queueManagerName", getQueueManagerName(),
													"listenerName", listenerName,
													"type", typeName,
													"port", Integer.toString(portNumber)),
											new AtomicLong(MQPCFConstants.PCF_INIT_VALUE))
											);
								} else {
									qmListener.set(MQPCFConstants.PCF_INIT_VALUE);
								}
							}
	
							
						} catch (Exception e) {
							//String protocol = this.typeList.get(-2);
							AtomicLong qmListener = listenerMap.get(lookupListener + "_" + listenerName);
							if (qmListener == null) {
								listenerMap.put(lookupListener + "_" + getQueueManagerName(), 
										base.meterRegistry.gauge(lookupListener, 
										Tags.of("queueManagerName", getQueueManagerName(),
												"listenerName", listenerName,
												"type", typeName,
												"port", Integer.toString(portNumber)),
										new AtomicLong(MQPCFConstants.PCF_INIT_VALUE))
										);
							} else {
								qmListener.set(MQPCFConstants.PCF_INIT_VALUE);
							}
						}				
					}
				}
	        }
		} catch (Exception e) {
			log.debug("pcfListener: unable to get listener metrcis " + e.getMessage());
			
		}
	}
	
	/*
	 * Check the listener from the environment variables
	 */
	private boolean checkListenNames(String name) {
		if (name.equals(null)) {
			return false;
		}

		int inc = includeListener(name);
		if (inc == 1) {
			return true;
		}
		
		int exc = excludeListener(name);
		if (exc == 1) {
			return false;
		}
		
		return true;
		
	}

	private int includeListener(String name) {
		
		int ret = 0;
		
		// Check queues against the list 
		for (String s : this.includeListeners) {
			if (s.equals("*")) {
				ret = 2;
			} else {
				if (name.startsWith(s)) {
					ret = 1;
				}				
			}
		}
		return ret;
	}
	
	private int excludeListener(String name) {

		int ret = 0;
		
		// Exclude ...
		for (String s : this.excludeListeners) {
			if (s.equals("*")) {
				ret = 2;
				break;
			} else {
				if (name.startsWith(s)) {
					ret = 1;
				}
			}
		}
		return ret;
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
	public void ResetMetrics() {
		log.debug("pcfListener: resetting metrics");
		DeleteMetrics();
	}
	
	/*
	 * Delete metrics
	 */
	private void DeleteMetrics() {
		base.DeleteMetricEntry(lookupListener);
		this.listenerMap.clear();
	}	
	
}
