package maersk.com.mq.pcf.queue;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Get queue details
 * 
 */

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQBase;
import maersk.com.mq.metrics.mqmetrics.MQBase.MQPCFConstants;

@Component
public class pcfQueue extends MQBase {

    private Logger log = Logger.getLogger(this.getClass());

	private String queueManager;
	
	@Value("${ibm.mq.objects.queues.exclude}")
    private String[] excludeQueues;
	@Value("${ibm.mq.objects.queues.include}")
    private String[] includeQueues;

	
    private int queueMonitoringFromQmgr;
    public int getQueueMonitoringFromQmgr() {
		return queueMonitoringFromQmgr;
    }
	public void setQueueMonitoringFromQmgr(int value) {
		this.queueMonitoringFromQmgr = value;
	}

	protected static final String lookupQueDepth = MQPREFIX + "queueDepth";
	protected static final String lookupOpenIn = MQPREFIX + "openInputCount";
	protected static final String lookupOpenOut = MQPREFIX + "openOutputCount";
	protected static final String lookupMaxDepth = MQPREFIX + "MaxQueueDepth";
	protected static final String lookupLastGetDateTime = MQPREFIX + "LastGetDateTime";
	protected static final String lookupLastPutDateTime = MQPREFIX + "LastPutDateTime";
	protected static final String lookupOldMsgAge = MQPREFIX + "oldestMsgAge";
	protected static final String lookupdeQueued = MQPREFIX + "deQueued";
	protected static final String lookupenQueued = MQPREFIX + "enQueued";
	protected static final String lookupQueueProcesses = MQPREFIX + "queueProcesses";

    private PCFMessageAgent messageAgent;
    public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    
    }
	
    public pcfQueue() {    	
    }
    
    /*
     * Get the metrics for each queue that we want
     */
	public void UpdateQueueMetrics() throws MQException, IOException, MQDataException {

		if (this._debug) { log.info("pcfQueue: inquire queue request"); }

		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);
		pcfRequest.addParameter(MQConstants.MQCA_Q_NAME, "*");
		pcfRequest.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL);		
        
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = this.messageAgent.send(pcfRequest);
		} catch (Exception e) {
			if (this._debug) { log.warn("pcfQueue: no response returned - " + e.getMessage()); }
			
		}
		if (this._debug) { log.info("pcfQueue: inquire queue response"); }

		// Delete all the metrics for this iteration ... this is the only way I can get working to delete
		// ... old metric objects ...
		
		SetMetricsValue();

		/*
		 * For each response, get the MQ details
		 */
		for (PCFMessage pcfMsg : pcfResponse) {
			String queueName = null;
			try {
				queueName = pcfMsg.getStringParameterValue(MQConstants.MQCA_Q_NAME).trim();
				if (this._debug) { log.info("pcfQueue: queue name: " + queueName); }

				if (checkQueueNames(queueName)) {
				
					int qType = pcfMsg.getIntParameterValue(MQConstants.MQIA_Q_TYPE);
					if ((qType != MQConstants.MQQT_LOCAL) && (qType != MQConstants.MQQT_ALIAS)) {
						if (this._debug) { log.info("pcfQueue: not needed : "); }
						throw new Exception("Not needed");
					}
					
					int qUsage = 0;
					int value = 0;
					
	 				String queueType = GetQueueType(qType);
					String queueCluster = "";
					String queueUsage = "";
					
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue local"); }
						qUsage = pcfMsg.getIntParameterValue(MQConstants.MQIA_USAGE);
						queueUsage = "Normal";
						if (qUsage != MQConstants.MQUS_NORMAL) {
							queueUsage = "Transmission";
						}
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					} else {
						if (this._debug) { log.info("pcfQueue: inquire queue alias"); }
						queueUsage = "Alias";
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					}

					if (this._debug) { log.info("pcfQueue: inquire queue status"); }

					PCFMessage pcfInqStat = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
					pcfInqStat.addParameter(MQConstants.MQCA_Q_NAME, queueName);

					PCFMessage[] pcfResStat = null;
					PCFMessage[] pcfResResp = null;
					if (qType != MQConstants.MQQT_ALIAS) {
						pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_STATUS);					
						pcfResStat = this.messageAgent.send(pcfInqStat);
						PCFMessage pcfReset = new PCFMessage(MQConstants.MQCMD_RESET_Q_STATS);
						pcfReset.addParameter(MQConstants.MQCA_Q_NAME, queueName);
						pcfResResp = this.messageAgent.send(pcfReset);
						if (this._debug) { log.info("pcfQueue: inquire queue status response"); }

					}
					
					// Queue depth
					if (this._debug) { log.info("pcfQueue: queue depth"); }
					meterRegistry.gauge(lookupQueDepth, 
							Tags.of("queueManagerName", this.queueManager,
									"queueName", queueName,
									"queueType", queueType,
									"usage",queueUsage,
									"cluster",queueCluster
									)
							,value);

					int openInvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue input count"); }
						// OpenInput count
						openInvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_INPUT_COUNT);						
						meterRegistry.gauge(lookupOpenIn, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,openInvalue);
					}
					
					int openOutvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue output count"); }
					// Open output count
						openOutvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT);
						meterRegistry.gauge(lookupOpenOut, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,openOutvalue);
						
					}

					if ((openInvalue > 0) || (openOutvalue > 0) ) {
						ProcessQueueHandlers(queueName, queueCluster);
					}

					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue depth"); }
						// Maximum queue depth
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH);
						meterRegistry.gauge(lookupMaxDepth, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,value);

					}

					
					// for dates / time - the queue manager or queue monitoring must be at least 'low'
					// MQMON_OFF 	- Monitoring data collection is turned off
					// MQMON_NONE	- Monitoring data collection is turned off for queues, regardless of their QueueMonitor attribute
					// MQMON_LOW	- Monitoring data collection is turned on, with low ratio of data collection
					// MQMON_MEDIUM	- Monitoring data collection is turned on, with moderate ratio of data collection
					// MQMON_HIGH	- Monitoring data collection is turned on, with high ratio of data collection
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue monitoring"); }
						if (!((getQueueMonitoringFromQmgr() == MQConstants.MQMON_OFF) 
								|| (getQueueMonitoringFromQmgr() == MQConstants.MQMON_NONE))) {
							String lastGetDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_DATE);
							String lastGetTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_TIME);
							if (!(lastGetDate.equals(" ") && lastGetTime.equals(" "))) {
								Date dt = formatter.parse(lastGetDate);
								long ld = dt.getTime() / (24*60*60*1000);	
								long hrs = Integer.parseInt(lastGetTime.substring(0, 2));
								long min = Integer.parseInt(lastGetTime.substring(3, 5));
								long sec = Integer.parseInt(lastGetTime.substring(6, 8));
								long seconds = sec + (60 * min) + (3600 * hrs);
								ld *= 86400;
								ld += seconds;
								
								// Last Get date and time
								meterRegistry.gauge(lookupLastGetDateTime, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster
												)
										,ld);
								
							}
		
							String lastPutDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_DATE);
							String lastPutTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_TIME);
							if (!(lastPutDate.equals(" ") && lastPutTime.equals(" "))) {
								Date dt = formatter.parse(lastPutDate);
								long ld = dt.getTime() / (24*60*60*1000);	
								long hrs = Integer.parseInt(lastPutTime.substring(0, 2));
								long min = Integer.parseInt(lastPutTime.substring(3, 5));
								long sec = Integer.parseInt(lastPutTime.substring(6, 8));
								long seconds = sec + (60 * min) + (3600 * hrs);
								ld *= 86400;
								ld += seconds;
								
								// Last put date and time
								meterRegistry.gauge(lookupLastPutDateTime, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster
												)
										,ld);
							}										
							
							if (this._debug) { log.info("pcfQueue: inquire queue old-age"); }
							// Oldest message age
							int old = pcfResStat[0].getIntParameterValue(MQConstants.MQIACF_OLDEST_MSG_AGE);
							meterRegistry.gauge(lookupOldMsgAge, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster,
											"type","seconds"
											)
									,old);
							
						}
					}
					
					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue de-queued"); }
						// Messages DeQueued
						int devalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_DEQ_COUNT);
						meterRegistry.gauge(lookupdeQueued, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,devalue);
						
					}

					if (qType != MQConstants.MQQT_ALIAS) {
						if (this._debug) { log.info("pcfQueue: inquire queue en-queued"); }
						// Messages EnQueued
						int envalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_ENQ_COUNT);
						meterRegistry.gauge(lookupenQueued, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										)
								,envalue);

					}
				}
				
			} catch (Exception e) {
				if (this._debug) { log.warn("pcfQueue: unable to get queue metrcis " + e.getMessage()); }
				
			}
		}
	}

	/*
	 * Get the handles from each queue
	 */
	private void ProcessQueueHandlers(String queueName, String cluster ) throws MQException, IOException, MQDataException {
		
		PCFMessage pcfInqHandle = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
		pcfInqHandle.addParameter(MQConstants.MQCA_Q_NAME, queueName);
		pcfInqHandle.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_HANDLE);					
		PCFMessage[] pcfResHandle = this.messageAgent.send(pcfInqHandle);

		int seq = 0;
		for (PCFMessage pcfMsg : pcfResHandle) {
			int state = pcfMsg.getIntParameterValue(MQConstants.MQIACF_HANDLE_STATE);
			String conn = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).trim();
			String appName = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_APPL_TAG).trim();			
			String userId = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_USER_IDENTIFIER).trim();			

			meterRegistry.gauge(lookupQueueProcesses, 
					Tags.of("queueManagerName", this.queueManager,
							"cluster",cluster,
							"queueName", queueName.trim(),
							"application", appName.trim(),
							"user", userId,
							"seq",Integer.toString(seq)
							)
					, state);
			
			seq++;
		}
				
	}
	
	
	/*
	 * Check for the queue names
	 */
	private boolean checkQueueNames(String name) {

		if (name.equals(null)) {
			return false;
		}
		
		// Exclude ...
		for (String s : this.excludeQueues) {
			if (s.equals("*")) {
				break;
			} else {
				if (name.startsWith(s)) {
					return false;
				}
			}
		}
	
		// Check queues against the list 
		for (String s : this.includeQueues) {
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
	 * Queue types
	 */
	private String GetQueueType(int qType) {

		String queueType = "";
		switch (qType) {
			case MQConstants.MQQT_ALIAS:
			{
				queueType = "Alias";
				break;
			}
			case MQConstants.MQQT_LOCAL:
			{
				queueType = "Local";
				break;
			}
			case MQConstants.MQQT_REMOTE:
			{
				queueType = "Remote";
				break;
			}
			case MQConstants.MQQT_MODEL:
			{
				queueType = "Model";
				break;
			}
			case MQConstants.MQQT_CLUSTER:
			{
				queueType = "Cluster";
				break;
			}
			
			default:
			{
				queueType = "Local";
				break;
			}
		}

		return queueType;
	}
	
	/*
	 * Reset metrics
	 */
	public void resetMetric() {
		SetMetricsValue();
		
	}
	
	// If the queue manager is not running, set any listeners state not running
	private void SetMetricsValue() {

		DeleteMetricEntry(lookupQueDepth);
		DeleteMetricEntry(lookupOpenIn);
		DeleteMetricEntry(lookupOpenOut);
		DeleteMetricEntry(lookupMaxDepth);
		DeleteMetricEntry(lookupLastGetDateTime);
		DeleteMetricEntry(lookupLastPutDateTime);
		DeleteMetricEntry(lookupOldMsgAge);
		DeleteMetricEntry(lookupdeQueued);
		DeleteMetricEntry(lookupenQueued);
		DeleteMetricEntry(lookupQueueProcesses);

		
	}
	

}
