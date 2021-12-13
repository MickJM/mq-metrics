package monitor.mq.resources;

/*
 * Copyright 2021
 *
 * Subscribes to MQ resource usage topic and create metrics from PCF data.
 *  
 */

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.MQTopic;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.pcf.MQCFIN;
import com.ibm.mq.headers.pcf.MQCFIN64;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFParameter;

import io.micrometer.core.instrument.Tags;
import monitor.mq.metrics.mqmetrics.MQMonitorBase;

@Component
public class MQResources {

    private final static Logger log = LoggerFactory.getLogger(MQResources.class);
    
    public MQResources() {
    }

	@Autowired
	private MQMonitorBase base;

    private boolean objectCreated = false;
    public boolean ObjectCreated() {
    	return this.objectCreated;
    }
    public void ObjectCreated(boolean v) {
    	this.objectCreated = v;
    }

    private boolean isRunning = false;
    public boolean IsRunning() {
    	return this.isRunning;
    }
    public void IsRunning(boolean v) {
    	this.isRunning = v;
    }

    private boolean isQueueOpen = false;
    public boolean IsQueueOpen() {
    	return this.isQueueOpen;
    }
    public void IsQueueOpen(boolean v) {
    	this.isQueueOpen = v;
    }
    
    private Map<String,AtomicLong>qmgrCPU = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>sysSummary = new HashMap<String,AtomicLong>();

    private Map<Integer,String>cpuLiterals = new HashMap<Integer,String>();
    private Map<Integer,String>sysLiterals = new HashMap<Integer,String>();
    
    private static String lookupQmgrCPU = "mq:QmgrCPU";
    private static String lookupSysSummary = "mq:SysSummary";

    @PostConstruct
    private void initSetup() {
		log.info("MQResources: Object created");
		IsQueueOpen(false);
		ObjectCreated(true);
		IsRunning(false);
		
		// Set the literals for the Metrics
		cpuLiterals.put(0, "User CPU time");
		cpuLiterals.put(1, "System CPU time");
		cpuLiterals.put(2, "RAM");

		sysLiterals.put(0, "User CPU time");
		sysLiterals.put(1, "System CPU time");
		sysLiterals.put(2, "CPU Load - one");
		sysLiterals.put(3, "CPU Load - five ");
		sysLiterals.put(4, "CPU Load - fifteen");
		sysLiterals.put(5, "RAM free percentage");
		sysLiterals.put(6, "RAM Free Total MB");

    }

    @Async("cpu")
    public void ProcessCPUMetrics(MQQueueManager qm, int metric) throws Exception {
    
    	Thread.currentThread().setName("MQCPU");
    	IsRunning(true);

    	ProcessResources(qm, metric);
    }

    @Async("system")
    public void ProcessSystemMetrics(MQQueueManager qm, int metric) throws Exception {

    	Thread.currentThread().setName("MQSystem");
    	IsRunning(true);
    	
    	ProcessResources(qm, metric);
    }

    // Common process
    public void ProcessResources(MQQueueManager qm, int metric) throws Exception {
    	    	    	
    	log.debug("MQResources: Processing Resources ...");    	
    	
    	// Open options for subscription ... stop is quiescing, managed and non-durable 
    	int openOptionsForGet = MQConstants.MQSO_CREATE |
    			MQConstants.MQSO_FAIL_IF_QUIESCING | 
    			MQConstants.MQSO_MANAGED | 
    			MQConstants.MQSO_NON_DURABLE;
    	
    	MQTopic subscriber = null;
    	MQGetMessageOptions gmo = new MQGetMessageOptions();
        gmo.options = MQConstants.MQGMO_WAIT |
        		MQConstants.MQGMO_SYNCPOINT |
        		MQConstants.MQGMO_CONVERT |
        		MQConstants.MQGMO_FAIL_IF_QUIESCING |
        		MQConstants.MQGMO_NO_PROPERTIES;
        
        gmo.waitInterval = 10000; // 10 seconds
        gmo.matchOptions = MQConstants.MQGMO_NONE;
            
        boolean mqexception = true;
        boolean exception = true;

        String qmgrName = qm.getName().trim();
        String queryType = "";
        if (metric == MQMonitorBase.CPUMETRIC) {
        	queryType = "QMgrSummary";
        } else {
        	queryType = "SystemSummary";
        }
        
        // Define the topic, substitute the queue manager name and query type
        String topicQuery = String.format("$SYS/MQ/INFO/QMGR/%s/Monitor/CPU/%s",qmgrName, queryType, queryType);
                	
        log.info("Topic: " + topicQuery);
        
        // Main processing ...
        L: while (IsRunning()) {
	        try {
	        	
	        	// Subscribe to the topic as non-durable
	        	subscriber = qm.accessTopic( topicQuery,
	        			null,
	        			MQConstants.MQTOPIC_OPEN_AS_SUBSCRIPTION,
	        			openOptionsForGet);
	        
	        	// Main loop ...
	        	// Continue while there is no signal to stop
		    	while (IsRunning()) {
		    		// As this object runs in a seperate thread, if its interrupted, then stop the thread ...
		    		if (Thread.interrupted()) {
		    			log.info("Thread interrupted, disconnecting from queue manager, MQResources thread stopping " );
		    			IsRunning(false);
		    			break L;
		    		}
		    		log.debug("Thread name:" + Thread.currentThread().getName() + " - Processing MQ resources ... ");
		    
		    		// Create a new message object and get messages from the subscribed topic 
		    		// ... This will 'block' for 10 seconds 
		            MQMessage msg = new MQMessage();
		            msg.messageId = MQConstants.MQMI_NONE;
		            msg.correlationId = MQConstants.MQMI_NONE;		            
		    		subscriber.get(msg, gmo);

		    		// MONITOR_CLASS = 0, MONITOR_TYPE = 0 == SystemSummary Stats
		    		// MONITOR_CLASS = 0, MONITOR_TYPE = 1 == QMgrSummary Stats
	
		    		// When we get a message, create a PCF message from it
		    		if (msg != null) {
		    			PCFMessage pcf = new PCFMessage(msg);		    			
		    			int msgClass = pcf.getIntParameterValue(MQConstants.MQIAMO_MONITOR_CLASS);
		    			int msgType = pcf.getIntParameterValue(MQConstants.MQIAMO_MONITOR_TYPE);
		    			int parameterId = -1;	    	
		    			int paramCount = 0;
		    			long paramValue = 0l;
		    			
		    			if (msgClass == 0) {	
		    				switch (msgType) {
		    				
		    				case 0:
		    	    			paramCount = pcf.getParameterCount();
		    	    			log.debug("System Summary Param count: " + paramCount);
	
		    					Enumeration<PCFParameter> allParam = pcf.getParameters();
	
		    					while (allParam.hasMoreElements()) {
		    						PCFParameter currentParameter = allParam.nextElement();
		    						switch (currentParameter.getParameter()) {
		    						
		    						case MQConstants.MQCA_Q_MGR_NAME:
		    							log.debug("Queue manager ...");
		    							break;
	
		    						case MQConstants.MQIAMO_MONITOR_CLASS :
		    						case MQConstants.MQIAMO_MONITOR_TYPE:
		    							log.debug("Class / Type...");
		    							break;
	
		    						case MQConstants.MQIAMO64_MONITOR_INTERVAL :
		    							log.debug("Monitor interval...");
		    							break;
		    							
		    						case MQConstants.MQIAMO_MONITOR_UNIT:
		    							log.debug("UNIT: " + currentParameter.getParameter());
		    							if (currentParameter.getType() == MQConstants.MQCFT_INTEGER) {
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();

		    							} else {
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();
		    							}
	
			    	    				if (parameterId >= 0) {
			    	    					String div = "";
			    	    					if (parameterId == 6) {
			    	    						div = "0";
			    	    					} else {
			    	    						div = "100";
			    	    					}
			    	    					String lit = GetLiteral(msgType, parameterId);
			    	    			        AtomicLong sysCPU = sysSummary.get(lookupSysSummary + "_" + qmgrName + "_" + parameterId);
					    	    			if (sysCPU == null) {
					    						sysSummary.put(lookupSysSummary + "_" + qmgrName + "_" + parameterId, base.meterRegistry.gauge(lookupSysSummary, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "System",
					    										"divisor",div,
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						sysCPU.set(paramValue);
					    					}
			    	    				}
		    							break;
	
		    						case 0:
		    							log.debug("NULL (0): " + currentParameter.getParameter());
		    							if (currentParameter.getType() == MQConstants.MQCFT_INTEGER) {
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();

		    							} else {
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();
		    							}
	
			    	    				if (parameterId >= 0) {
			    	    					String div = "";
			    	    					if (parameterId == 6) {
			    	    						div = "0";
			    	    					} else {
			    	    						div = "100";
			    	    					}
			    	    					String lit = GetLiteral(msgType, parameterId);
			    	    			        AtomicLong sysCPU = sysSummary.get(lookupSysSummary + "_" + qmgrName + "_" + parameterId);
					    	    			if (sysCPU == null) {
					    	    				sysSummary.put(lookupSysSummary + "_" + qmgrName + "_" + parameterId, base.meterRegistry.gauge(lookupSysSummary, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "System",
					    										"divisor",div,
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						sysCPU.set(paramValue);
					    					}
			    	    				}
		    							break;
		    							
		    						// Disk RAM
		    						case MQConstants.MQIAMO_MONITOR_DELTA:
		    							log.debug("DELTA: " + currentParameter.getParameter());
		    							if (currentParameter.getType() == MQConstants.MQCFT_INTEGER) {
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();

		    							} else {
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();	    								
		    							}
	
			    	    				if (parameterId >= 0) {
			    	    					String div = "";
			    	    					if (parameterId == 6) {
			    	    						div = "0";
			    	    					} else {
			    	    						div = "100";
			    	    					}
			    	    					String lit = GetLiteral(msgType, parameterId);
			    	    			        AtomicLong sysCPU = sysSummary.get(lookupSysSummary + "_" + qmgrName + "_" + parameterId);
					    	    			if (sysCPU == null) {
					    	    				sysSummary.put(lookupSysSummary + "_" + qmgrName + "_" + parameterId, base.meterRegistry.gauge(lookupSysSummary, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "System",
					    										"divisor",div,
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						sysCPU.set(paramValue);
					    					}
			    	    				}
		    							break;
		    							
		    						default:
		    							log.debug("Default ...Type: " + currentParameter.getType()) ;
		    							switch (currentParameter.getType()) {
		    							
		    							case (MQConstants.MQCFT_INTEGER):
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();
		    								break;
		    							case (MQConstants.MQCFT_INTEGER64):
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();
		    								break;
		    							default:
		    								break;
		    							}
		    							
				    	    			log.debug("SystemCummary: " + paramValue);
			    	    				if (parameterId >= 0) {
			    	    					String div = "";
			    	    					if (parameterId == 6) {
			    	    						div = "0";
			    	    					} else {
			    	    						div = "100";
			    	    					}
			    	    					String lit = GetLiteral(msgType, parameterId);
					    	    			String lookupString = lookupSysSummary + "_" + qmgrName + "_" + parameterId;

			    	    					AtomicLong sysCPU = sysSummary.get(lookupString);
					    	    			if (sysCPU == null) {
					    						sysSummary.put(lookupString, 
					    								base.meterRegistry.gauge(lookupSysSummary, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "System",
					    										"divisor",div,
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						sysCPU.set(paramValue);
					    					}
			    	    				}
			    	    				
				    				}
		    					}	    					
		    					break;
		    					
		    				case 1:
		    	    			paramCount = pcf.getParameterCount();
		    	    			log.debug("CPU Param count: " + paramCount);
	
		    					Enumeration<PCFParameter> allParameters = pcf.getParameters();
		    					while (allParameters.hasMoreElements()) {
		    						PCFParameter currentParameter = allParameters.nextElement();
		    						switch (currentParameter.getParameter()) {
		    						
		    						case MQConstants.MQCA_Q_MGR_NAME:
		    							log.debug("Queue manager ...");
		    							break;
	
		    						case MQConstants.MQIAMO_MONITOR_CLASS :
		    						case MQConstants.MQIAMO_MONITOR_TYPE:
		    							log.debug("Class / Type...");
		    							break;
	
		    						case MQConstants.MQIAMO64_MONITOR_INTERVAL :
		    							log.debug("Monitor interval...");
		    							break;
		    							
		    						case MQConstants.MQIAMO_MONITOR_UNIT:
		    							log.debug("UNIT: " + currentParameter.getParameter());
		    							if (currentParameter.getType() == MQConstants.MQCFT_INTEGER) {
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();
		    							} else {
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();	    								
		    							}
	
			    	    				if (parameterId >= 0) {
			    	    					String lit = GetLiteral(msgType, parameterId);
			    	    			        AtomicLong userCPU = qmgrCPU.get(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId);
					    	    			if (userCPU == null) {
					    						qmgrCPU.put(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId, base.meterRegistry.gauge(lookupQmgrCPU, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "CPU",
					    										"divisor","100",
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						userCPU.set(paramValue);
					    					}
			    	    				}
		    							break;
	
		    						case 0:
		    							log.debug("CPU NULL (0): " + currentParameter.getParameter());
		    							if (currentParameter.getType() == MQConstants.MQCFT_INTEGER) {
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();
		    							} else {
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();	    								
		    							}
	
			    	    				if (parameterId >= 0) {
			    	    					String lit = GetLiteral(msgType, parameterId);
			    	    			        AtomicLong userCPU = qmgrCPU.get(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId);
					    	    			if (userCPU == null) {
					    						qmgrCPU.put(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId, base.meterRegistry.gauge(lookupQmgrCPU, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "CPU",
					    										"divisor","100",
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						userCPU.set(paramValue);
					    					}
			    	    				}
		    							break;
		    							
		    						// Disk RAM
		    						case MQConstants.MQIAMO_MONITOR_DELTA:
		    							log.debug("DELTA: " + currentParameter.getParameter());
		    							if (currentParameter.getType() == MQConstants.MQCFT_INTEGER) {
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();
		    							} else {
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();	    								
		    							}
	
			    	    				if (parameterId >= 0) {
			    	    					String lit = GetLiteral(msgType, parameterId);
			    	    			        AtomicLong userCPU = qmgrCPU.get(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId);
					    	    			if (userCPU == null) {
					    						qmgrCPU.put(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId, base.meterRegistry.gauge(lookupQmgrCPU, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "CPU",
					    										"divisor","0",
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						userCPU.set(paramValue);
					    					}
			    	    				}
		    							break;
		    							
		    						default:
		    							log.debug("CPU Type: " + currentParameter.getType()) ;
		    							switch (currentParameter.getType()) {
		    							
		    							case (MQConstants.MQCFT_INTEGER):
		    								MQCFIN statistic = (MQCFIN) currentParameter;
		    								parameterId = statistic.getParameter();
		    								paramValue = statistic.getIntValue();
		    								log.debug("Integer - ID / Value: " + parameterId + " / " + paramValue);
		    								break;
		    							case (MQConstants.MQCFT_INTEGER64):
		    								MQCFIN64 stat64 = (MQCFIN64) currentParameter;
		    								parameterId = stat64.getParameter();
		    								paramValue = stat64.getLongValue();
		    								log.debug("Integer - ID / Value: " + parameterId + " / " + paramValue);
		    								break;
		    								
		    							default:
		    								break;
		    							}
		    							    					
				    	    			log.debug("UserCPU: " + paramValue);
			    	    				if (parameterId >= 0) {
			    	    					String lit = GetLiteral(msgType, parameterId);
			    	    			        AtomicLong userCPU = qmgrCPU.get(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId);
					    	    			if (userCPU == null) {
					    						qmgrCPU.put(lookupQmgrCPU + "_" + qmgrName + "_" + parameterId, base.meterRegistry.gauge(lookupQmgrCPU, 
					    								Tags.of("queueManagerName", qmgrName,
					    										"type", "CPU",
					    										"divisor", "0",
					    										"metric", lit
					    										),
					    								new AtomicLong(paramValue))
					    								);
					    					} else {
					    						userCPU.set(paramValue);
					    					}
			    	    				}
				    				}
		    					}	    					
		    					break;
		    					
		    				default:
		    					log.warn("Unknown stats - type: " + msgType);
		    					break;
		    				}	    				
		    			}	    			
		    		}
		    		Thread.sleep(5000);
		    	}
	
	        } catch (InterruptedException e) {
	        	log.debug("Thread interrupted .... " + e.getMessage());
	        	IsRunning(false);
	        	break L;
	        	
	        } catch (MQException e) {
	        	if (mqexception) {
	        		log.warn("MQException caught (usually due to incorrect privileges on the topic) " + e.getMessage());
	        		mqexception = false;	        			
	        	}
	        } catch (Exception e) {
	        	if (exception) {
	        		log.warn("Exception caught (usually due to thread / application stopping) " + e.getMessage());
	        		exception = false;
	        	}
	        } finally {
	        	Thread.sleep(5000);
	        }
        }
        
        log.info("Metrics thread complete ...." + Thread.currentThread().getName());
        try {
	    	if (subscriber != null) {
	    		subscriber.close();
	    	}
        } catch (Exception e) {
        	
        }
    }

    /*
     * Check the CPU literal
     */
    private String GetLiteral(int type, int paramValue) {
		String r;
		
		try {
			switch (type) {
			case 0:
				r = sysLiterals.get(paramValue);
				break;
			case 1:
				r = cpuLiterals.get(paramValue);
				break;
			default:
				r = "Unknown_Value_" + paramValue;
				break;
			}
		} catch (Exception e) {
			r = "Unknown_Value_" + paramValue;
		}
		return r;
		
    }
    @PreDestroy
    private void CleanUp() {
    	IsRunning(false);
    	log.info("Thread is stopping ..." + Thread.currentThread().getName());

    }
}
