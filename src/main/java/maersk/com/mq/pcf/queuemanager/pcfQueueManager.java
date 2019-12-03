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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.search.RequiredSearch;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQConnection;
import maersk.com.mq.metrics.mqmetrics.MQBase.MQPCFConstants;

import java.util.Collections.*;

@Component
public class pcfQueueManager extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private String queueManager;

    @Value("${ibm.mq.event.delayInMilliSeconds}")
	private int resetIterations;

    private PCFMessageAgent messageAgent = null;
    private Map<String,AtomicLong>mqReset = new HashMap<String, AtomicLong>();
    private Map<String,AtomicInteger>qmStatusMap = new HashMap<String, AtomicInteger>();

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
     * Set the number of iterations for the metrics to be collected
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
	public void NotRunning(String qm, Boolean mi, int status) {

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
		
		resetMetric(lookupStatus);
		int val = MQPCFConstants.PCF_INIT_VALUE;
		if (status == MQConstants.MQRC_STANDBY_Q_MGR) {
			val = MQConstants.MQQMSTA_STANDBY;
		} 		
		if (status == MQConstants.MQRC_Q_MGR_QUIESCING) {
			val = MQConstants.MQQMSTA_QUIESCING;
		} 		

		meterRegistry.gauge(lookupStatus, 
				Tags.of("queueManagerName", qm,
						"cluster",getQueueManagerClusterName())
				,val);

		// Set the queue manager status to indicate that its in multi-instance
		val = MQPCFConstants.NOT_MULTIINSTANCE;
		if (mi) {
			val = MQPCFConstants.MULTIINSTANCE;
		}
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
