package maersk.com.mq.pcf.queuemanager;

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
	//public void setResetIterations(long value) {
	//	this.resetIterations = value;
	//}	

    private PCFMessageAgent messageAgent = null;

    private Map<String,AtomicLong>mqReset = new HashMap<String, AtomicLong>();

    //Queue Manager / IIB maps
    private Map<String,AtomicInteger>qmStatusMap = new HashMap<String, AtomicInteger>();

    //Command Server maps
    private Map<String,AtomicInteger>cmdStatusMap = new HashMap<String, AtomicInteger>();


	private String queueManagerClusterName;
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
    
    public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    	
    }
	
    /*
     * Set the number of iterations that the metrics are collected
     */
	public void ResetIteration() {
		
        AtomicLong q = mqReset.get(this.queueManager);
		if (q == null) {
			mqReset.put(this.queueManager, 
					Metrics.gauge(new StringBuilder()
							.append(MQPREFIX)
							.append("resetIterations").toString(),  
					Tags.of("queueManagerName", this.queueManager),
					new AtomicLong(this.resetIterations)));
		} else {
			q.set(this.resetIterations);
		}        
		
	}
	
	/*
	 * Get the cluster name of the queue manager
	 */
	public void CheckQueueManagerCluster() {
		
        //int[] pcfParmAttrs = { MQConstants.MQIACF_Q_MGR_CLUSTER };
        int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
        
        PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR);
        //pcfRequest1.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
        //pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, pcfParmAttrs);
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
		//pcfRequest.addParameter(MQConstants.MQIACF_Q_MGR_CLUSTER, pcfParmAttrs);
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
		AtomicInteger q = qmStatusMap.get(this.queueManager);
		if (q == null) {
			qmStatusMap.put(this.queueManager, 
				Metrics.gauge(new StringBuilder()
				.append(MQPREFIX)
				.append("queueManagerStatus")
				.toString(),  
				Tags.of("queueManagerName", this.queueManager,
				"cluster",getQueueManagerClusterName()),
			new AtomicInteger(qmStatus)));
		} else {
			q.set(qmStatus);
		}        
	
		// command server status
		int cmdStatus = response.getIntParameterValue(MQConstants.MQIACF_CMD_SERVER_STATUS);
		AtomicInteger cmd = cmdStatusMap.get(this.queueManager);
		if (cmd == null) {
			cmdStatusMap.put(this.queueManager, 
			Metrics.gauge(new StringBuilder()
				.append(MQPREFIX)
				.append("commandServerStatus")
				.toString(), 
				Tags.of("queueManagerName", this.queueManager), 
			new AtomicInteger(cmdStatus)));
		} else {
			cmd.set(cmdStatus);
		}        	
	}
	
	/*
	 * Called from the main class, if we are not running, set the status
	 */
	public void NotRunning(String qm) {

		if (this.queueManager != null) {
			qm = this.queueManager;
		}
		
		// Set the queue manager status to indicate that its not running
		AtomicInteger q = qmStatusMap.get(qm);
		if (q == null) {
			qmStatusMap.put(this.queueManager, 
					Metrics.gauge("mq:queueManagerStatus", 
					Tags.of("queueManagerName", qm,
							"cluster","unknown"), 
					new AtomicInteger(MQPCFConstants.PCF_INIT_VALUE)));
		} else {
			q.set(MQPCFConstants.PCF_INIT_VALUE);
		}        
		
	}
	
}
