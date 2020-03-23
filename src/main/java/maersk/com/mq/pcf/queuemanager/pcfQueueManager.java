package maersk.com.mq.pcf.queuemanager;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Get queue manager details
 * 
 * 22/10/2019 - When the queue manager is not running, check if the status is multi-instance
 *              and set the status accordingly
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
public class pcfQueueManager extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private String queueManager;

    @Value("${ibm.mq.event.delayInMilliSeconds}")
	private int resetIterations;

    private PCFMessageAgent messageAgent = null;

	protected static final String cmdLookupStatus = MQPREFIX + "commandServerStatus";
	protected static final String lookupStatus = MQPREFIX + "queueManagerStatus";
	protected static final String lookupReset = MQPREFIX + "resetIterations";
	protected static final String lookupMultiInstance = MQPREFIX + "multiInstance";
	
    private Map<String,AtomicInteger>qmMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>cmdMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>iterMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>multiMap = new HashMap<String,AtomicInteger>();

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

	// Constructor
    public pcfQueueManager() {
    }

    /*
     * Set the message agant object and the queue manager name
     */
    public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    }
	
    /*
     * Set the number of iterations for the metrics to be collected
     */
	public void ResetIteration(String queueMan) {

		AtomicInteger value = iterMap.get(lookupReset + "_" + queueMan);
		if (value == null) {
			iterMap.put(lookupReset + "_" + this.queueManager, meterRegistry.gauge(lookupReset, 
					Tags.of("queueManagerName", queueMan),
					new AtomicInteger(this.resetIterations))
					);
		} else {
			value.set(this.resetIterations);
		}
	}
	
	/*
	 * Get the cluster name of the queue manager
	 */
	public void checkQueueManagerCluster() {

		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfQueueManager: checkQueueManagerCluster"); }

        int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
        
        PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR);
        pcfRequest.addParameter(MQConstants.MQCA_CLUSTER_Q_MGR_NAME, this.queueManager); 
        pcfRequest.addParameter(MQConstants.MQIACF_CLUSTER_Q_MGR_ATTRS, pcfParmAttrs);
       
        /*
         *  if an error occurs, ignore it, as the queue manager may not belong to a cluster
         */
        try {
	        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);
	        PCFMessage response = pcfResponse[0];
	        String clusterNames = response.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME);
	        setQueueManagerClusterName(clusterNames.trim());

        } catch (Exception e) {
    		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfQueueManager: Exception, queue manager doesn't belong to a cluster"); }
        }	
	}
	
	/*
	 * Get the current status of the queue manager and CommandServer 
	 * ... if we are not connected, then we will not set the status here
	 * ... it will be set in the NotRunning method
	 */
	public void updateQMMetrics() throws PCFException, MQException, IOException, MQDataException {

		/*
		 *  Inquire on the queue manager ...
		 */
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR);
		pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
		PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);		
		PCFMessage response = pcfResponse[0];
	
		/*
		 *  Save the queue monitoring attribute to be used later
		 */
		int queueMon = response.getIntParameterValue(MQConstants.MQIA_MONITORING_Q);
		setQueueMonitoringFromQmgr(queueMon);
	
		/*
		 *  Send a queue manager status request
		 */
		pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS);
		pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_STATUS_ATTRS, pcfParmAttrs);
		pcfResponse = this.messageAgent.send(pcfRequest);		
		response = pcfResponse[0];       	
		
		/*
		 *  queue manager status
		 */
		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfQueueManager: queue manager status"); }
		int qmStatus = response.getIntParameterValue(MQConstants.MQIACF_Q_MGR_STATUS);
		AtomicInteger qmStat = qmMap.get(lookupStatus + "_" + this.queueManager);
		if (qmStat == null) {
			qmMap.put(lookupStatus + "_" + this.queueManager, meterRegistry.gauge(lookupStatus, 
					Tags.of("queueManagerName", this.queueManager,
							"cluster",getQueueManagerClusterName()),
					new AtomicInteger(qmStatus))
					);
		} else {
			qmStat.set(qmStatus);
		}

		/*
		 *  command server status
		 */
		if (getDebugLevel() == LEVEL.TRACE) { log.trace("pcfQueueManager: command server status"); }
		int cmdStatus = response.getIntParameterValue(MQConstants.MQIACF_CMD_SERVER_STATUS);
		AtomicInteger value = cmdMap.get(cmdLookupStatus + "_" + this.queueManager);
		if (value == null) {
			cmdMap.put(cmdLookupStatus + "_" + this.queueManager, meterRegistry.gauge(cmdLookupStatus, 
					Tags.of("queueManagerName", this.queueManager),
					new AtomicInteger(cmdStatus))
					);
		} else {
			value.set(cmdStatus);
		}

	}
	
	/*
	 * Called from the main class, if we are not running, set the status
	 */
	public void notRunning(String qm, Boolean mi, int status) {

		if (this.queueManager != null) {
			qm = this.queueManager;
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
		if (status == MQConstants.MQRC_Q_MGR_QUIESCING) {
			val = MQConstants.MQQMSTA_QUIESCING;
		} 
		AtomicInteger value = qmMap.get(lookupStatus + "_" + this.queueManager);
		if (value == null) {
			qmMap.put(lookupStatus + "_" + qm, meterRegistry.gauge(lookupStatus, 
					Tags.of("queueManagerName", qm,
							"cluster",getQueueManagerClusterName()),
					new AtomicInteger(val))
					);
		} else {
			value.set(val);
		}
				
		/*
		 *  Set the queue manager status to indicate that its in multi-instance
		 */
		val = MQPCFConstants.NOT_MULTIINSTANCE;
		if (mi) {
			val = MQPCFConstants.MULTIINSTANCE;
		}
		AtomicInteger multiVal = multiMap.get(lookupMultiInstance + "_" + qm);
		if (multiVal == null) {
			multiMap.put(lookupMultiInstance + "_" + qm, meterRegistry.gauge(lookupMultiInstance, 
					Tags.of("queueManagerName", qm),
					new AtomicInteger(val))
					);
		} else {
			multiVal.set(val);
		}
		
	}
	
	/*
	 * Remove the metric
	 */	
	private void resetMetric() {
		deleteMetricEntry(lookupReset);
		deleteMetricEntry(lookupStatus);
		deleteMetricEntry(cmdLookupStatus);
		deleteMetricEntry(lookupMultiInstance);
		
	}

}
