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
import maersk.com.mq.metrics.mqmetrics.MQConnection;

@Component
public class pcfQueueManager {

	private static final String MQPREFIX = "mq:";

	private String queueManager;

	private long resetIterations;
	public void setResetIterations(long value) {
		this.resetIterations = value;
	}
	
	@Value("${application.debug:false}")
    private boolean _debug;
	
    private Logger log = Logger.getLogger(this.getClass());

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

	
    public pcfQueueManager(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();
    	
    }
	
    // Reset iterations
	public void ResetIteration() {
		
		Long l = resetIterations;		
        AtomicLong q = mqReset.get(this.queueManager);
		if (q == null) {
			mqReset.put(this.queueManager, 
					Metrics.gauge(new StringBuilder()
							.append(MQPREFIX)
							.append("ResetIterations").toString(),  
					Tags.of("queueManagerName", this.queueManager),
					new AtomicLong(l)));
		} else {
			q.set(0);
		}        
		
	}
	
	// Get Cluster details
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
	
	private void UpdateQMMetrics() throws PCFException, 
											MQException, 
											IOException, 
											MQDataException {


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
	
	// command server
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
	
}
