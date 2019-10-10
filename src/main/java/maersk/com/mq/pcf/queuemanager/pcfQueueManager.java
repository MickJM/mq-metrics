package maersk.com.mq.pcf.queuemanager;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Get queue manager details
 * 
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFAgent;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQConnection;
import maersk.com.mq.metrics.mqmetrics.MQBase.MQPCFConstants;

@Component
public class pcfQueueManager extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private String queueManager;

    @Value("${ibm.mq.event.delayInMilliSeconds}")
	private long resetIterations;

    private PCFMessageAgent messageAgent = null;
    private Map<String,AtomicLong>mqReset = new HashMap<String, AtomicLong>();

	protected static final String cmdLookupStatus = MQPREFIX + "commandServerStatus";
	protected static final String lookupStatus = MQPREFIX + "queueManagerStatus";
	protected static final String lookupReset = MQPREFIX + "resetIterations";
	protected static final String lookupMultiInstance = MQPREFIX + "multiInstance";
	
    //Queue Manager / IIB maps
    //private Map<String,AtomicInteger>qmStatusMap = new HashMap<String, AtomicInteger>();

    //Command Server maps
    //private Map<String,AtomicInteger>cmdStatusMap = new HashMap<String, AtomicInteger>();

    private Boolean multiInstance = false;
    
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
    
    public void setMessageAgent(PCFMessageAgent agent, Boolean mi) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    	this.multiInstance = mi;
    }
	
    /*
     * Set the number of iterations that the metrics are collected
     */
	public void ResetIteration(String queueMan) {

		resetMetric(lookupReset);
		meterRegistry.gauge(lookupReset, 
				Tags.of("queueManagerName", queueMan)
				,this.resetIterations);

	}
	
	/*
	 * Get the cluster name of the queue manager
	 */
	public void CheckQueueManagerCluster() {
		
        int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
        
        PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR);
        pcfRequest.addParameter(MQConstants.MQCA_CLUSTER_Q_MGR_NAME, this.queueManager); 
        pcfRequest.addParameter(MQConstants.MQIACF_CLUSTER_Q_MGR_ATTRS, pcfParmAttrs);
       
        // if an error occurs, ignore it, as the queue manager may not belong to a cluster
        try {
	        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);
	        PCFMessage response = pcfResponse[0];
	        String clusterNames = response.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME);
	        setQueueManagerClusterName(clusterNames.trim());

        } catch (Exception e) {
        	if (this._debug) { log.info("Queue manager " + this.queueManager.trim() + " does not belong to a cluster"); }
        	setQueueManagerClusterName("");
        	
        }	
	}
	
	/*
	 * Get the current status of the queue manager and CommandServer 
	 * ... if we are not connected, then we will not set the status here
	 * ... it will be set in the NotRunning method
	 */
	public void UpdateQMMetrics() throws PCFException, MQException, IOException, MQDataException {

		// Enquire on the queue manager ...
		int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR);
		pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
		PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);		
		PCFMessage response = pcfResponse[0];
	
		// Save the queue monitoring attribute to be used later
		int queueMon = response.getIntParameterValue(MQConstants.MQIA_MONITORING_Q);
		setQueueMonitoringFromQmgr(queueMon);
	
		// Send a queue manager status request
		pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS);
		pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_STATUS_ATTRS, pcfParmAttrs);
		pcfResponse = this.messageAgent.send(pcfRequest);		
		response = pcfResponse[0];       	
		
		// queue manager status
		int qmStatus = response.getIntParameterValue(MQConstants.MQIACF_Q_MGR_STATUS);
		resetMetric(lookupStatus);
		meterRegistry.gauge(lookupStatus, 
				Tags.of("queueManagerName", this.queueManager,
						"cluster",getQueueManagerClusterName())
				,qmStatus);

		// Multi instance
		int val = MQPCFConstants.MULTIINSTANCE;
		if (!this.multiInstance) {
			val = MQPCFConstants.NOT_MULTIINSTANCE;
		}
		resetMetric(lookupMultiInstance);
		meterRegistry.gauge(lookupMultiInstance, 
				Tags.of("queueManagerName", this.queueManager)
				,val);
		
		// command server status
		int cmdStatus = response.getIntParameterValue(MQConstants.MQIACF_CMD_SERVER_STATUS);
		resetMetric(cmdLookupStatus);
		meterRegistry.gauge(cmdLookupStatus, 
				Tags.of("queueManagerName", this.queueManager)
				,cmdStatus);
		
	}
	
	/*
	 * Called from the main class, if we are not running, set the status
	 */
	public void NotRunning(String qm, Boolean mi) {

		if (this.queueManager != null) {
			qm = this.queueManager;
		}
		
		// Set the queue manager status to indicate that its not running
		resetMetric(lookupStatus);
		meterRegistry.gauge(lookupStatus, 
				Tags.of("queueManagerName", qm,
						"cluster",getQueueManagerClusterName())
				,MQPCFConstants.PCF_INIT_VALUE);

		int val = MQPCFConstants.MULTIINSTANCE;
		if (!mi) {
			val = MQPCFConstants.NOT_MULTIINSTANCE;
		}
		// Set the queue manager status to indicate that its not running
		resetMetric(lookupMultiInstance);
		meterRegistry.gauge(lookupMultiInstance, 
				Tags.of("queueManagerName", qm)
				,val);
		
		
		
	}
	
	/*
	 * Remove the metric
	 */	
	private void resetMetric(String val) {
		DeleteMetricEntry(val);

	}

	
}
