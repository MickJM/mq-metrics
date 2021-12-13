package monitor.mq.pcf.queuemanager;

/*
 * Copyright 2020
 *
 * Get queue manager details
 * 
 * 22/10/2019 - When the queue manager is not running, check if the status is multi-instance
 *              and set the status accordingly
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
import monitor.mq.metrics.mqmetrics.MQMetricsQueueManager;
import monitor.mq.metrics.mqmetrics.MQMonitorBase;
import monitor.mq.metrics.mqmetrics.MQPCFConstants;

@Component
public class pcfQueueManager {

	private final static Logger log = LoggerFactory.getLogger(pcfQueueManager.class);

	private String queueManager;
	public void setQueueManagerName(String v) {
		this.queueManager = v;
	}
	public String getQueueManagerName() {
		return this.queueManager;
	}
	
    @Value("${ibm.mq.event.delayInMilliSeconds}")
	private int resetIterations;

    private PCFMessageAgent messageAgent = null;
    private PCFMessageAgent getMessageAgent() {
    	return this.messageAgent;
    }

    @Autowired
    private MQMonitorBase base;
    
    @Autowired
    private MQMetricsQueueManager metqm;

    private String cmdLookupStatus = "mq:commandServerStatus";
    private String lookupStatus = "mq:queueManagerStatus";
    private String lookupStandby = "mq:standbyStatus";

    private String lookupReset = "mq:resetIterations";
    private String lookupMultiInstance = "mq:multiInstance";
    private String lookupMemory = "mq:memory";
	
    private Map<String,AtomicLong>qmMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>standbyMap = new HashMap<String,AtomicLong>();

    private Map<String,AtomicLong>cmdMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>iterMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>multiMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>memoryMap = new HashMap<String,AtomicLong>();

    //private Boolean multiInstance = false;
    
	private String queueManagerClusterName = "";
	public String getQueueManagerClusterName() {
		return this.queueManagerClusterName;
	}
	public void setQueueManagerClusterName(String value) {
		this.queueManagerClusterName = value;
	}

    private int queueMonitoringFromQmgr;
    public int getQueueMonitoringFromQmgr() {
		return queueMonitoringFromQmgr;
    }
	public void setQueueMonitoringFromQmgr(int value) {
		this.queueMonitoringFromQmgr = value;
	}

	/*
	 * Accounting saved value
	 */
	private int savedQAcct;
    public int getSavedQAcct() {
		return savedQAcct;
    }
	public void setSavedQAcct(int value) {
		this.savedQAcct = value;
	}
	/*
	 * Statistics saved value
	 */
	private int savedQStat;
    public int getSavedQStat() {
		return savedQStat;
    }
	public void setSavedQStat(int value) {
		this.savedQStat = value;
	}
	
	private boolean qAcct;

	private boolean connectionBroken;
	
	
	public void setQAcct(boolean v) {
		this.qAcct = v;
	}
	public boolean getQAcct() {
		return qAcct;
	}
	
	// Constructor
    public pcfQueueManager() {
    }

    @PostConstruct
    public void init() {
    	log.info("Queue Manager ...");
    	setQAcct(true);
    }
    
    /*
     * Set the message agant object and the queue manager name
     */
    public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	setQueueManagerName(getMessageAgent().getQManagerName().trim());
    }
	
    /*
     * Set the number of iterations for the metrics to be collected
     */
	public void ResetMetricsIteration(String queueMan) {

		AtomicLong value = iterMap.get(lookupReset + "_" + queueMan);
		if (value == null) {
			iterMap.put(lookupReset + "_" + queueMan, base.meterRegistry.gauge(lookupReset, 
					Tags.of("queueManagerName", queueMan),
					new AtomicLong(this.resetIterations))
					);
		} else {
			value.set(this.resetIterations);
		}
	}
	
	/*
	 * Get the cluster name of the queue manager
	 */
	public void CheckQueueManagerCluster() {

		log.debug("pcfQueueManager: checkQueueManagerCluster");

		/*
		 * Clear the metrics every 'x' iteration
		 * 
		 * *** dont need to clear metrics for queue manager - metrics dont grow like queues, channels etc
		 */
		if (base.getCounter() % base.ClearMetrics() == 0) {
			log.debug("Clearing QueueManager metrics");
		//	ResetMetrics();
		}
		
        int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };        
        PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR);
        pcfRequest.addParameter(MQConstants.MQCA_CLUSTER_Q_MGR_NAME, getQueueManagerName()); 
        pcfRequest.addParameter(MQConstants.MQIACF_CLUSTER_Q_MGR_ATTRS, pcfParmAttrs);
       
        /*
         *  if an error occurs, ignore it, as the queue manager may not belong to a cluster
         */
        try {
	        PCFMessage[] pcfResponse = getMessageAgent().send(pcfRequest);
	        PCFMessage response = pcfResponse[0];
	        String clusterNames = response.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME);
	        setQueueManagerClusterName(clusterNames.trim());

        } catch (Exception e) {
    		log.debug("pcfQueueManager: Exception, queue manager doesn't belong to a cluster");
        }	
	}
	
	/*
	 * Get the current status of the queue manager and CommandServer 
	 * ... if we are not connected, then we will not set the status here
	 * ... it will be set in the NotRunning method
	 */
	public void UpdateQMMetrics() throws PCFException, MQException, IOException, MQDataException {

		/*
		 *  Inquire on the queue manager ...
		 */
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR);
		pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
		PCFMessage[] pcfResponse = getMessageAgent().send(pcfRequest);		
		PCFMessage response = pcfResponse[0];
	
		/*
		 *  Save the queue monitoring / stats attribute to be used later
		 */
		int queueMon = response.getIntParameterValue(MQConstants.MQIA_MONITORING_Q);
		setQueueMonitoringFromQmgr(queueMon); // Queue monitoring, not currently used
		
		int stats = response.getIntParameterValue(MQConstants.MQIA_STATISTICS_Q);
		if (getSavedQStat() != response.getIntParameterValue(MQConstants.MQIA_STATISTICS_Q)) {
			metqm.QueueManagerStatistics(stats);
			setSavedQStat(stats);
			setQAcct(true);			
		}
		
		/*
		 *  Save the queue accounting, update the value only when it changes
		 */
		int qAcctValue = response.getIntParameterValue(MQConstants.MQIA_ACCOUNTING_Q);
		if (getSavedQAcct() != response.getIntParameterValue(MQConstants.MQIA_ACCOUNTING_Q)) {
			metqm.Accounting(qAcctValue);
			setSavedQAcct(qAcctValue);
			setQAcct(true);
		}
		
		if (getQAcct()) {
			String s = getAccountingStatus(qAcctValue);
			log.info("Queue manager accounting is set to " + s);

			s = getAccountingStatus(stats);			
			log.info("Queue manager statistics is set to " + s);			
			setQAcct(false);
		}
		
		/*
		 *  Send a queue manager status request
		 */
		pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS);
		pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_STATUS_ATTRS, pcfParmAttrs);
		pcfResponse = getMessageAgent().send(pcfRequest);		
		response = pcfResponse[0];       	
		
		/*
		 *  queue manager status
		 */
		log.debug("pcfQueueManager: queue manager status");
		int qmStatus = response.getIntParameterValue(MQConstants.MQIACF_Q_MGR_STATUS);
		AtomicLong qmStat = qmMap.get(lookupStatus + "_" + getQueueManagerName());
		if (qmStat == null) {
			qmMap.put(lookupStatus + "_" + getQueueManagerName(), base.meterRegistry.gauge(lookupStatus, 
					Tags.of("queueManagerName", getQueueManagerName(),
							"cluster",getQueueManagerClusterName()),
					new AtomicLong(qmStatus))
					);
		} else {
			qmStat.set(qmStatus);
		}

		/*
		 *  command server status
		 */
		log.debug("pcfQueueManager: command server status");
		int cmdStatus = response.getIntParameterValue(MQConstants.MQIACF_CMD_SERVER_STATUS);
		AtomicLong cmdVal = cmdMap.get(cmdLookupStatus + "_" + getQueueManagerName());
		if (cmdVal == null) {
			cmdMap.put(cmdLookupStatus + "_" + getQueueManagerName(), base.meterRegistry.gauge(cmdLookupStatus, 
					Tags.of("queueManagerName", getQueueManagerName()),
					new AtomicLong(cmdStatus))
					);
		} else {
			cmdVal.set(cmdStatus);
		}
		
		/*
		 * Standby
		 */
		log.debug("pcfQueueManager: standby");
		int standBy = response.getIntParameterValue(MQConstants.MQIACF_PERMIT_STANDBY);
		AtomicLong stndby = standbyMap.get(lookupStandby + "_" + getQueueManagerName());
		if (stndby == null) {
			standbyMap.put(lookupStandby + "_" + getQueueManagerName(), base.meterRegistry.gauge(lookupStandby, 
					Tags.of("queueManagerName", getQueueManagerName()),
					new AtomicLong(standBy))
					);
		} else {
			stndby.set(standBy);
		}
				
				
	}

	/*
	 * Calc memory
	 */
	public void memoryMetrics() throws Exception {

		Runtime rt = Runtime.getRuntime();
		long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
		
		AtomicLong value = memoryMap.get(lookupMemory + "_" + getQueueManagerName());
		if (value == null) {
			memoryMap.put(lookupMemory + "_" + getQueueManagerName(), 
					base.meterRegistry.gauge(lookupMemory, 
					Tags.of("queueManagerName", getQueueManagerName()),
					new AtomicLong(usedMB))
					);
		} else {
			value.set(usedMB);
		}		

	}
	
	/*
	 * Whats the accounting type set to ?
	 */
	private String getAccountingStatus(int v) {
		String s = "";
		switch (v) {
			case MQConstants.MQMON_NONE:
				s = "NONE";
				break;
			case MQConstants.MQMON_OFF:
				s = "OFF";
				break;
			case MQConstants.MQMON_ON:
				s = "ON";
				break;
			default:
				s = "OFF";
				break;	
		}
		return s;
	}
	
	/*
	 * Called from the main class, if we are not running, set the status
	 */
	public void NotRunning(String qm, Boolean mi, int status) {

		if (getQueueManagerName() != null) {
			qm = getQueueManagerName();
		}
		
		// Set the queue manager status to indicate that its not running
		// If the multiInstance flag is set to true, then set the queue manager to be in standby
		//
		// NOTE: There is an issue with the above, as if the queue manager was in a STOPPED state, then
		//       the status would ALWAYS show as STANDBY
		// 0 - stopped, 1 - starting, 2 - running, 3 - quiescing, 4 - standby
		//
		// Can never get starting, quiescing or standby at the moment using a client connection ...
		//   as there needs to be a connection to the queue manager
		//
		
		int val = MQPCFConstants.PCF_INIT_VALUE;		
		if (status == MQConstants.MQRC_STANDBY_Q_MGR) {
			val = MQConstants.MQQMSTA_STANDBY;
		} 		
		if (this.connectionBroken) {
			if (status == MQConstants.MQRC_Q_MGR_QUIESCING) {
				val = MQConstants.MQQMSTA_QUIESCING;
			} 
			if (status == MQConstants.MQRC_CONNECTION_QUIESCING) {
				val = MQConstants.MQQMSTA_QUIESCING;
			} 

			//if (status == MQConstants.MQRC_JSSE_ERROR) {
			//	val = MQConstants.MQQMSTA_QUIESCING;
			//} 
			if (status == MQConstants.MQRC_CONNECTION_BROKEN) {
				val = MQConstants.MQQMSTA_QUIESCING;
			} 

		}
		
		AtomicLong value = qmMap.get(lookupStatus + "_" + qm);
		if (value == null) {
			qmMap.put(lookupStatus + "_" + qm, base.meterRegistry.gauge(lookupStatus, 
					Tags.of("queueManagerName", qm,
							"cluster",getQueueManagerClusterName()),
					new AtomicLong(val))
					);
		} else {
			value.set(val);
		}
						
	}

	/*
	 * API running for multi-instance
	 */
	public void MulitInstance(String qm, Boolean mi) {
		
		/*
		 *  Set the queue manager status to indicate that its in multi-instance
		 */
		int val = MQPCFConstants.NOT_MULTIINSTANCE;
		if (mi) {
			val = MQPCFConstants.MULTIINSTANCE;
		}
		AtomicLong multiVal = multiMap.get(lookupMultiInstance + "_" + qm);
		if (multiVal == null) {
			multiMap.put(lookupMultiInstance + "_" + qm, base.meterRegistry.gauge(lookupMultiInstance, 
					Tags.of("queueManagerName", qm),
					new AtomicLong(val))
					);
		} else {
			multiVal.set(val);
		}
	}

	/*
	 * Reset metrics
	 */
	public void ResetMetrics() {
		log.debug("pcfQueueManager: resetting metrics");
		deleteMetrics();
		
	}

	/*
	 * Remove the metric
	 */	
	private void deleteMetrics() {
		base.DeleteMetricEntry(cmdLookupStatus);
		base.DeleteMetricEntry(lookupStatus);
		base.DeleteMetricEntry(lookupReset);
		base.DeleteMetricEntry(lookupMultiInstance);
		base.DeleteMetricEntry(lookupMemory);
		base.DeleteMetricEntry(lookupStandby);
						
	}
	
	/*
	 * Connection Broken ?
	 */
	public void connectionBroken(int status) {
		if (status == MQConstants.MQRC_CONNECTION_BROKEN
				|| status == MQConstants.MQRC_CONNECTION_QUIESCING
				|| status == MQConstants.MQRC_Q_MGR_QUIESCING) {
			this.connectionBroken = true;
		} else {
			connectionBroken();
		}
		
	}
	public void connectionBroken() {
		this.connectionBroken = false;
	}

}
