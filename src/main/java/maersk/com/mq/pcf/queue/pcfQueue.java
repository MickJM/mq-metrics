package maersk.com.mq.pcf.queue;

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

@Component
public class pcfQueue {

	private static final String MQPREFIX = "mq:";

	private String queueManager;

	@Value("${application.debug:false}")
    private boolean _debug;
	
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

    
    private Logger log = Logger.getLogger(this.getClass());

    //Queue maps
    private Map<String,AtomicInteger>queueDepMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueDeqMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueEnqMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicLong>queueLGDMap = new HashMap<String, AtomicLong>();
    private Map<String,AtomicLong>queueLPDMap = new HashMap<String, AtomicLong>();
    private Map<String,AtomicLong>queueAgeMap = new HashMap<String, AtomicLong>();
    private Map<String,AtomicInteger>queueOpenInMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueOpenOutMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueMaxDepthMap = new HashMap<String, AtomicInteger>();
    private Map<String,AtomicInteger>queueHandleMap = new HashMap<String, AtomicInteger>();
    
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

		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);
		pcfRequest.addParameter(MQConstants.MQCA_Q_NAME, "*");
		pcfRequest.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL);		
        PCFMessage[] pcfResponse = this.messageAgent.send(pcfRequest);
			        
		for (PCFMessage pcfMsg : pcfResponse) {
			String queueName = null;
			try {
				queueName = pcfMsg.getStringParameterValue(MQConstants.MQCA_Q_NAME).trim();
				int qType = pcfMsg.getIntParameterValue(MQConstants.MQIA_Q_TYPE);
				String queueType = GetQueueType(qType);
				
				int qUsage = pcfMsg.getIntParameterValue(MQConstants.MQIA_USAGE);
				String queueUsage = "Normal";
				if (qUsage != MQConstants.MQUS_NORMAL) {
					queueUsage = "Transmission";
				}
				if (checkQueueNames(queueName)) {
					PCFMessage pcfInqStat = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
					pcfInqStat.addParameter(MQConstants.MQCA_Q_NAME, queueName);
					//int[] pcfParmAttrs = { MQConstants.MQIACF_ALL };
					//pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_ATTRS, pcfParmAttrs);
					pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_STATUS);					
					PCFMessage[] pcfResStat = this.messageAgent.send(pcfInqStat);

					PCFMessage pcfReset = new PCFMessage(MQConstants.MQCMD_RESET_Q_STATS);
					pcfReset.addParameter(MQConstants.MQCA_Q_NAME, queueName);
					PCFMessage[] pcfResResp = this.messageAgent.send(pcfReset);
					
					int value = pcfMsg.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
					String queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();

					// Queue depth
					AtomicInteger i = queueDepMap.get(queueName);
					if (i == null) {
						queueDepMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("queueDepth").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(value)));
					} else {
						i.set(value);
					}
					
					// OpenInput count
					int openInvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_INPUT_COUNT);
					AtomicInteger inC = queueOpenInMap.get(queueName);
					if (inC == null) {
						queueOpenInMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("openInputCount").toString(),
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(openInvalue)));
					} else {
						inC.set(openInvalue);
					}

					// Open ouput count
					int openOutvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT);
					AtomicInteger outC = queueOpenOutMap.get(queueName);
					if (outC == null) {
						queueOpenOutMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("openOutputCount").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(openOutvalue)));
					} else {
						outC.set(openOutvalue);
					}

					if ((openInvalue > 0) || (openOutvalue > 0) ) {
					//	ProcessQueueHandlers(queueName);
						
					}
					
					// Maximum queue depth
					value = pcfMsg.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH);
					AtomicInteger maxqd = queueMaxDepthMap.get(queueName);
					if (maxqd == null) {
						queueMaxDepthMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("maxQueueDepth").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(value)));
					} else {
						maxqd.set(value);
					}
					
					
					// for dates / time - the queue manager or queue monitoring must be at least 'low'
					// MQMON_OFF 	- Monitoring data collection is turned off
					// MQMON_NONE	- Monitoring data collection is turned off for queues, regardless of their QueueMonitor attribute
					// MQMON_LOW	- Monitoring data collection is turned on, with low ratio of data collection
					// MQMON_MEDIUM	- Monitoring data collection is turned on, with moderate ratio of data collection
					// MQMON_HIGH	- Monitoring data collection is turned on, with high ratio of data collection
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
							AtomicLong lgd = queueLGDMap.get(queueName);
							if (lgd == null) {
								queueLGDMap.put(queueName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("lastGetDateTime").toString(), 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"type", "timestamp"
												),
										new AtomicLong(ld)));
							} else {
								lgd.set(ld);
							}							
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
							AtomicLong lpd = queueLPDMap.get(queueName);
							if (lpd == null) {
								queueLPDMap.put(queueName, Metrics.gauge(new StringBuilder()
										.append(MQPREFIX)
										.append("lastPutDateTime").toString(), 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"type", "timestamp"
												),
										new AtomicLong(ld)));
							} else {
								lpd.set(ld);
							}							
						}										
						
						// Oldest message age
						int old = pcfResStat[0].getIntParameterValue(MQConstants.MQIACF_OLDEST_MSG_AGE);
						AtomicLong age = queueAgeMap.get(queueName);
						if (age == null) {
							queueAgeMap.put(queueName, Metrics.gauge(new StringBuilder()
									.append(MQPREFIX)
									.append("oldestMsgAge").toString(), 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster,
											"type", "seconds"
											),
									new AtomicLong(old)));
						} else {
							age.set(old);
						}							
					}

					// Messages DeQueued
					int devalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_DEQ_COUNT);
					AtomicInteger d = queueDeqMap.get(queueName);
					if (d == null) {
						queueDeqMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("deQueued").toString(),
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(devalue)));
					} else {
						d.set(devalue);
					}

					// Messages EnQueued
					int envalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_ENQ_COUNT);
					AtomicInteger e = queueEnqMap.get(queueName);
					if (e == null) {
						queueEnqMap.put(queueName, Metrics.gauge(new StringBuilder()
								.append(MQPREFIX)
								.append("enQueued").toString(), 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(envalue)));
					} else {
						e.set(envalue);
					}					
				}
				
			} catch (Exception e) {
				// do nothing
			}
		}
	}
    
	/*
	 * Check for the queue names
	 */
	private boolean checkQueueNames(String name) {

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
	
	
	// Not running
	public void NotRunning() {
		SetMetricsValue(0);
	}

	private void ResetMetrics() {
		SetMetricsValue(-1);
		
	}
	
	// If the queue manager is not running, set any listeners state not running
	public void SetMetricsValue(int val) {

		// For each listener, set the status to indicate its not running, as the ...
		// ... queue manager is not running
		Iterator<Entry<String, AtomicInteger>> qdepth = this.queueDepMap.entrySet().iterator();
		while (qdepth.hasNext()) {
	        Map.Entry pair = (Map.Entry)qdepth.next();
	        try {
				AtomicInteger i = (AtomicInteger) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set QueueDepth metrics");
	        }
		}
		
		Iterator<Entry<String, AtomicInteger>> deq = this.queueDeqMap.entrySet().iterator();
		while (deq.hasNext()) {
	        Map.Entry pair = (Map.Entry)deq.next();
	        try {
				AtomicInteger i = (AtomicInteger) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set DeQueued metrics");
	        }
		}

		Iterator<Entry<String, AtomicInteger>> enq = this.queueEnqMap.entrySet().iterator();
		while (enq.hasNext()) {
	        Map.Entry pair = (Map.Entry)enq.next();
	        try {
				AtomicInteger i = (AtomicInteger) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set EnqQueued metrics");
	        }
		}
		
		Iterator<Entry<String, AtomicLong>> lgd = this.queueLGDMap.entrySet().iterator();
		while (lgd.hasNext()) {
	        Map.Entry pair = (Map.Entry)lgd.next();
	        try {
				AtomicLong i = (AtomicLong) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set LastGetDate metrics");
	        }
		}
		
		Iterator<Entry<String, AtomicLong>> lpd = this.queueLPDMap.entrySet().iterator();
		while (lpd.hasNext()) {
	        Map.Entry pair = (Map.Entry)lpd.next();
	        try {
				AtomicLong i = (AtomicLong) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set LastPutDate metrics");
	        }
		}
		
		Iterator<Entry<String, AtomicLong>> age = this.queueAgeMap.entrySet().iterator();
		while (age.hasNext()) {
	        Map.Entry pair = (Map.Entry)age.next();
	        try {
				AtomicLong i = (AtomicLong) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set QueueMessageAge metrics");
	        }
		}

		Iterator<Entry<String, AtomicInteger>> oin = this.queueOpenInMap.entrySet().iterator();
		while (oin.hasNext()) {
	        Map.Entry pair = (Map.Entry)oin.next();
	        try {
				AtomicLong i = (AtomicLong) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set QueueOpenInCount metrics");
	        }
		}

		Iterator<Entry<String, AtomicInteger>> oout = this.queueOpenOutMap.entrySet().iterator();
		while (oout.hasNext()) {
	        Map.Entry pair = (Map.Entry)oout.next();
	        try {
				AtomicLong i = (AtomicLong) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set QueueOpenOutCount metrics");
	        }
		}
		
		Iterator<Entry<String, AtomicInteger>> max = this.queueMaxDepthMap.entrySet().iterator();
		while (max.hasNext()) {
	        Map.Entry pair = (Map.Entry)max.next();
	        try {
				AtomicLong i = (AtomicLong) pair.getValue();
				if (i != null) {
					i.set(val);
				}
	        } catch (Exception e) {
	        	log.error("Unable to set QueueOpenOutCount metrics");
	        }
		}
		
	}

}
