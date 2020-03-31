package maersk.com.mq.pcf.queue;

import java.io.DataInput;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Get queue details
 * 
 * 17/10/2019 - Amended MaxQueueDepth to maxQueueDepth, LastGetDateTime to lastGetDateTime, LastPutDateTime to lastPutDateTime
 * 17/10/2019 - Amended the calculation of the Epoch value for lastGetDateTime and lastPutDateTime
 * 
 */

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
//import com.ibm.mq.MQMessage;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.MQCFGR;
import com.ibm.mq.headers.pcf.MQCFH;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.accounting.AccountingEntity;
import maersk.com.mq.metrics.mqmetrics.MQBaseNotNeeded;
import maersk.com.mq.metrics.mqmetrics.MQMetricsQueueManager;
import maersk.com.mq.metrics.mqmetrics.MQMonitorBase;
import maersk.com.mq.metrics.mqmetrics.MQPCFConstants;

@Component
public class pcfQueue {

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

    private Map<String,AtomicInteger>queueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>openInMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>openOutMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>maxQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicLong>lastGetMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>lastPutMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicInteger>oldAgeMap = new HashMap<String,AtomicInteger>();    
    private Map<String,AtomicInteger>deQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>enQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>procMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>msgMaxPutMsgSizeMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>msgMaxGetMsgSizeMap = new HashMap<String,AtomicInteger>();
    
    private Map<String,AtomicLong>msgPutPMsgCountMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgGetPMsgCountMap = new HashMap<String,AtomicLong>();
    
//    private Map<String,AtomicInteger>msgMaxPutNMsgSizeMap = new HashMap<String,AtomicInteger>();
//    private Map<String,AtomicInteger>msgMaxGetNMsgSizeMap = new HashMap<String,AtomicInteger>();
    
	protected static final String lookupQueDepth = "mq:queueDepth";
	protected static final String lookupOpenIn = "mq:openInputCount";
	protected static final String lookupOpenOut = "mq:openOutputCount";
	protected static final String lookupMaxDepth = "mq:maxQueueDepth";
	protected static final String lookupLastGetDateTime = "mq:lastGetDateTime";
	protected static final String lookupLastPutDateTime = "mq:lastPutDateTime";
	protected static final String lookupOldMsgAge = "mq:oldestMsgAge";
	protected static final String lookupdeQueued = "mq:deQueued";
	protected static final String lookupenQueued = "mq:enQueued";
	protected static final String lookupQueueProcesses = "mq:queueProcesses";
	protected static final String lookupMaxPutMsgSize = "mq:queueMaxPutMsgSize";
	protected static final String lookupMaxGetMsgSize = "mq:queueMaxGetMsgSize";
	
	protected static final String lookupPutMsgCount = "mq:queuePutMsgCount";
	protected static final String lookupGetMsgCount = "mq:queueGetMsgCOunt";
	
//	protected static final String lookupMaxPutNMsgSize = "mq:queueMaxPutNonPMsgSize";
//	protected static final String lookupMaxGetNMsgSize = "mq:queueMaxGetNonPMsgSize";

	//protected static final String lookupPutCount = "mq:putCount";
	//protected static final String lookupPut1Count = "mq:put1Count";
	//protected static final String lookupPutBytes = "mq:putBytes";

	
    private PCFMessageAgent messageAgent;
	public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    }
	
	@Autowired
	private MQMetricsQueueManager conn;
	//public void setQueueManager(MQMetricsQueueManager conn) {
	//	this.conn = conn;
	//	
	//}
	
	@Autowired
	private MQMonitorBase base;

	/*
	 * Constructor
	 */
    public pcfQueue() {
    }

    /*
     * When the class is fully created ...
     */
    @PostConstruct
    private void PostMethod() {
		if (!(base.getDebugLevel() == MQPCFConstants.NONE)) { log.info("pcfQueue: Object created"); }

		if (!(base.getDebugLevel() == MQPCFConstants.NONE)) {
	    	log.info("Excluding queues ;");
	    	for (String s : this.excludeQueues) {
	    		log.info(s);
	    	}
		}
    }

    
    /*
     * Get the metrics for each queue that we want
     */
	public void updateQueueMetrics() throws MQException, IOException, MQDataException {
	
		if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue request"); }

		/*
		 * Clear the metrics every 'x' iteration
		 */
		base.setCounter();
		if (base.getCounter() % base.getClearMetrics() == 0) {
			base.setCounter(0);
			if (base.getDebugLevel() == MQPCFConstants.DEBUG 
					|| base.getDebugLevel() == MQPCFConstants.TRACE) {
				log.trace("Clearing queue metrics");
			}
			resetMetrics();
		}
		
		// 17/10/2019 Amended to include HH.mm.ss
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");
		PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);
		pcfRequest.addParameter(MQConstants.MQCA_Q_NAME, "*");
		pcfRequest.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL);		
        
		/*
		 * Get a list of queues
		 */
		PCFMessage[] pcfResponse = null;
		try {
			pcfResponse = this.messageAgent.send(pcfRequest);
		} catch (Exception e) {
			if (base.getDebugLevel() == MQPCFConstants.DEBUG) { log.warn("pcfQueue: no response returned - " + e.getMessage()); }
			
		}
		if (base.getDebugLevel() == MQPCFConstants.DEBUG) { log.debug("pcfQueue: inquire queue response"); }

		/*
		 * For each queue, build the response
		 */
		for (PCFMessage pcfMsg : pcfResponse) {
			String queueName = null;
			try {
				queueName = pcfMsg.getStringParameterValue(MQConstants.MQCA_Q_NAME).trim();
				if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: queue name: " + queueName); }

				if (checkQueueNames(queueName)) {
					int qType = pcfMsg.getIntParameterValue(MQConstants.MQIA_Q_TYPE);
					if ((qType != MQConstants.MQQT_LOCAL) && (qType != MQConstants.MQQT_ALIAS)) {
						if (base.getDebugLevel() == MQPCFConstants.TRACE ) { log.trace("pcfQueue: Queue type is not required : " + qType); }
						throw new Exception("Queue type is not required");
					}
					
					int qUsage = 0;
					int value = 0;
	 				String queueType = getQueueType(qType);
					String queueCluster = "";
					String queueUsage = "";

					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue local"); }
					if (qType != MQConstants.MQQT_ALIAS) {
						qUsage = pcfMsg.getIntParameterValue(MQConstants.MQIA_USAGE);
						queueUsage = "Normal";
						if (qUsage != MQConstants.MQUS_NORMAL) {
							queueUsage = "Transmission";
						}
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					} else {
						if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue alias"); }
						queueUsage = "Alias";
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					}

					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue status"); }

					PCFMessage pcfInqStat = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
					pcfInqStat.addParameter(MQConstants.MQCA_Q_NAME, queueName);

					/*
					 * Reset Queue status
					 */
					PCFMessage[] pcfResStat = null;
					PCFMessage[] pcfResResp = null;
					if (qType != MQConstants.MQQT_ALIAS) {
						pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_STATUS);					
						pcfResStat = this.messageAgent.send(pcfInqStat);
						PCFMessage pcfReset = new PCFMessage(MQConstants.MQCMD_RESET_Q_STATS);
						pcfReset.addParameter(MQConstants.MQCA_Q_NAME, queueName);
						pcfResResp = this.messageAgent.send(pcfReset);
						if (base.getDebugLevel() == MQPCFConstants.DEBUG) { log.debug("pcfQueue: inquire queue status response"); }

					}
					
					/*
					 * Queue depth
					 */
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: queue depth"); }
					AtomicInteger qdep = queueMap.get(lookupQueDepth + "_" + queueName);
					if (qdep == null) {
						queueMap.put(lookupQueDepth + "_" + queueName, base.meterRegistry.gauge(lookupQueDepth, 
								Tags.of("queueManagerName", this.queueManager,
										"queueName", queueName,
										"queueType", queueType,
										"usage",queueUsage,
										"cluster",queueCluster
										),
								new AtomicInteger(value))
								);
					} else {
						qdep.set(value);
					}
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: queue depth: " + queueName +": " + value); }
					
					/*
					 * Open input count
					 */
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue input count"); }
					int openInvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						openInvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_INPUT_COUNT);
						AtomicInteger openIn = openInMap.get(lookupOpenIn + "_" + queueName);
						if (openIn == null) {
							openInMap.put(lookupOpenIn + "_" + queueName, base.meterRegistry.gauge(lookupOpenIn, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(openInvalue))
									);
						} else {
							openIn.set(openInvalue);
						}

					}

					/*
					 * Open output count
					 */
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue output count"); }
					int openOutvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						openOutvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT);
						AtomicInteger openOut = openOutMap.get(lookupOpenOut + "_" + queueName);
						if (openOut == null) {
							openOutMap.put(lookupOpenOut + "_" + queueName, base.meterRegistry.gauge(lookupOpenOut, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(openOutvalue))
									);
						} else {
							openOut.set(openOutvalue);
						}

					}

					if ((openInvalue > 0) || (openOutvalue > 0) ) {
						processQueueHandlers(queueName, queueCluster);
					}

					/*
					 * Maximum queue depth
					 */
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue depth"); }
					if (qType != MQConstants.MQQT_ALIAS) {
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH);
						AtomicInteger openMax = maxQueMap.get(lookupMaxDepth + "_" + queueName);
						if (openMax == null) {
							maxQueMap.put(lookupMaxDepth + "_" + queueName, base.meterRegistry.gauge(lookupMaxDepth, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(value))
									);
						} else {
							openMax.set(value);
						}

					}

					
					// for dates / time - the queue manager or queue monitoring must be at least 'low'
					// MQMON_OFF 	- Monitoring data collection is turned off
					// MQMON_NONE	- Monitoring data collection is turned off for queues, regardless of their QueueMonitor attribute
					// MQMON_LOW	- Monitoring data collection is turned on, with low ratio of data collection
					// MQMON_MEDIUM	- Monitoring data collection is turned on, with moderate ratio of data collection
					// MQMON_HIGH	- Monitoring data collection is turned on, with high ratio of data collection
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue LAST GET DATE : " + queueName); }
					if (qType != MQConstants.MQQT_ALIAS) {
						if (!((getQueueMonitoringFromQmgr() == MQConstants.MQMON_OFF) 
								|| (getQueueMonitoringFromQmgr() == MQConstants.MQMON_NONE))) {
							String lastGetDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_DATE);
							String lastGetTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_TIME);
							if (!(lastGetDate.equals(" ") && lastGetTime.equals(" "))) {
					
								/*
								 *  17/10/2019 - Amended to correctly calculate the epoch value			
								 */
								Date dt = formatter.parse(lastGetDate + " " + lastGetTime);
								long ld = dt.getTime();
								
								// Last Get date and time
								AtomicLong getDate = lastGetMap.get(lookupLastGetDateTime + "_" + queueName);
								if (getDate == null) {
									lastGetMap.put(lookupLastGetDateTime + "_" + queueName, base.meterRegistry.gauge(lookupLastGetDateTime, 
											Tags.of("queueManagerName", this.queueManager,
													"queueName", queueName,
													"queueType", queueType,
													"usage",queueUsage,
													"cluster",queueCluster
													),
											new AtomicLong(ld))
											);
								} else {
									getDate.set(ld);
								}
									
							}
		
							String lastPutDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_DATE);
							String lastPutTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_PUT_TIME);
							if (!(lastPutDate.equals(" ") && lastPutTime.equals(" "))) {
								if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue LAST PUT DATE : " + queueName); }
								
								/*
								 *  17/10/2019 - amended to correctly calculate the epoch value				
								 */
								Date dt = formatter.parse(lastPutDate + " " + lastPutTime);
								long ld = dt.getTime();
								
								// Last put date and time
								AtomicLong lastDate = lastPutMap.get(lookupLastPutDateTime + "_" + queueName);
								if (base.getDebugLevel() == MQPCFConstants.TRACE ) { log.trace("pcfQueue: lastDate : " + lastDate); }

								if (lastDate == null) {
									lastPutMap.put(lookupLastPutDateTime + "_" + queueName, base.meterRegistry.gauge(lookupLastPutDateTime, 
											Tags.of("queueManagerName", this.queueManager,
													"queueName", queueName,
													"queueType", queueType,
													"usage",queueUsage,
													"cluster",queueCluster
													),
											new AtomicLong(ld))
											);
								} else {
									lastDate.set(ld);
								}

							}										
							
							/*
							 *  Oldest message age
							 */
							if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue old-age"); }
							int old = pcfResStat[0].getIntParameterValue(MQConstants.MQIACF_OLDEST_MSG_AGE);
							AtomicInteger oldAge = oldAgeMap.get(lookupOldMsgAge + "_" + queueName);
							if (oldAge == null) {
								lastPutMap.put(lookupOldMsgAge + "_" + queueName, base.meterRegistry.gauge(lookupOldMsgAge, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster
												),
										new AtomicLong(old))
										);
							} else {
								oldAge.set(old);
							}

						}
					}

					/*
					 *  Messages DeQueued
					 */
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue de-queued"); }
					if (qType != MQConstants.MQQT_ALIAS) {
						int devalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_DEQ_COUNT);
						AtomicInteger deQue = deQueMap.get(lookupdeQueued + "_" + queueName);
						if (deQue == null) {
							deQueMap.put(lookupdeQueued + "_" + queueName, base.meterRegistry.gauge(lookupdeQueued, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(devalue))
									);
						} else {
							deQue.set(devalue);
						}

					}

					/*
					 *  Messages EnQueued
					 */
					if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: inquire queue en-queued"); }
					if (qType != MQConstants.MQQT_ALIAS) {
						// Messages EnQueued
						int envalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_ENQ_COUNT);
						AtomicInteger enQue = enQueMap.get(lookupenQueued + "_" + queueName);
						if (enQue == null) {
							enQueMap.put(lookupenQueued + "_" + queueName, base.meterRegistry.gauge(lookupenQueued, 
									Tags.of("queueManagerName", this.queueManager,
											"queueName", queueName,
											"queueType", queueType,
											"usage",queueUsage,
											"cluster",queueCluster
											),
									new AtomicInteger(envalue))
									);
						} else {
							enQue.set(envalue);
						}
					}
					
					/*
					 *  For queues, get the MAX GET/PUT stats for PERSISTENT and NON_PERSISTENT messages
					 */
					if (qType == MQConstants.MQQT_LOCAL) {

						int acctQ = pcfMsg.getIntParameterValue(MQConstants.MQIA_ACCOUNTING_Q);
						
						/*
						 * If set to MQMON_Q_QMGR or MQMON_ON, then continue
						 */
						List<AccountingEntity> pcfAccts = this.conn.readAccountData(queueName, acctQ);
						if (pcfAccts.isEmpty()) {
							continue;
						}
						/*
						 * PUT PERSISTENT messages
						 */
						int[] putValues =  sumPutsAndGets(pcfAccts, queueName, MQConstants.MQIAMO_PUTS);						
						int putMsgs = putValues[MQConstants.MQPER_PERSISTENT];
						if (putMsgs > 0) {
							AtomicLong puts = msgPutPMsgCountMap.get(lookupPutMsgCount + "_" + queueName + MQConstants.MQPER_PERSISTENT);
							if (puts == null) {							
								msgPutPMsgCountMap.put(lookupPutMsgCount + "_" + queueName + MQConstants.MQPER_PERSISTENT
										, base.meterRegistry.gauge(lookupPutMsgCount, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","true"
												),
										new AtomicLong(putMsgs))
										);
							} else {
								putMsgs += (puts.get());
								puts.set(putMsgs);
							}
						}
						
						/*
						 * PUT NOT PERSISTENT messages
						 */
						putMsgs = putValues[MQConstants.MQPER_NOT_PERSISTENT];
						if (putMsgs > 0) {
							AtomicLong puts = msgPutPMsgCountMap.get(lookupPutMsgCount + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT);
							if (puts == null) {
								msgPutPMsgCountMap.put(lookupPutMsgCount + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT
										, base.meterRegistry.gauge(lookupPutMsgCount, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","false"
												),
										new AtomicLong(putMsgs))
										);
							} else {
								putMsgs += (puts.get());
								puts.set(putMsgs);
							}
						}

						
						/*
						 * GET PERSISTENT messages
						 */
						int[] getValues =  sumPutsAndGets(pcfAccts, queueName, MQConstants.MQIAMO_GETS);						
						int getMsgs = putValues[MQConstants.MQPER_PERSISTENT];
						if (getMsgs > 0) {
							AtomicLong gets = msgGetPMsgCountMap.get(lookupGetMsgCount + "_" + queueName + MQConstants.MQPER_PERSISTENT);
							if (gets == null) {
								msgGetPMsgCountMap.put(lookupGetMsgCount + "_" + queueName + MQConstants.MQPER_PERSISTENT
										, base.meterRegistry.gauge(lookupGetMsgCount, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","true"
												),
										new AtomicLong(getMsgs))
										);
							} else {
								getMsgs += (gets.get());
								gets.set(getMsgs);
							}
						}

						/*
						 * GET NOT PERSISTENT messages
						 */
						getMsgs = getValues[MQConstants.MQPER_NOT_PERSISTENT];
						if (getMsgs > 0) {
							AtomicLong gets = msgGetPMsgCountMap.get(lookupGetMsgCount + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT);
							if (gets == null) {
								msgGetPMsgCountMap.put(lookupGetMsgCount + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT
										, base.meterRegistry.gauge(lookupGetMsgCount, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","false"
												),
										new AtomicLong(getMsgs))
										);
							} else {
								getMsgs += (gets.get());
								gets.set(getMsgs);
							}
						}
						
						/*
						 * PUT PERSISTENT message size mozz
						 */
						int[] maxValues = findMaxValues(pcfAccts, queueName, 
								MQConstants.MQIAMO_PUT_MAX_BYTES, 
								MQConstants.MQPER_PERSISTENT);						
						int maxMsgSize = maxValues[MQConstants.MQPER_PERSISTENT];
						if (maxMsgSize > 0) {
							AtomicInteger maxLen = msgMaxPutMsgSizeMap.get(lookupMaxPutMsgSize + "_" + queueName + MQConstants.MQPER_PERSISTENT);
							if (maxLen == null) {
								msgMaxPutMsgSizeMap.put(lookupMaxPutMsgSize + "_" + queueName + MQConstants.MQPER_PERSISTENT
										, base.meterRegistry.gauge(lookupMaxPutMsgSize, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","true"
												),
										new AtomicInteger(maxMsgSize))
										);
							} else {
								maxLen.set(maxMsgSize);
							}
						}
						
						/*
						 * Max PUT NON PERSISTENT message size
						 */
						maxValues = findMaxValues(pcfAccts, queueName, 
								MQConstants.MQIAMO_PUT_MAX_BYTES, 
								MQConstants.MQPER_NOT_PERSISTENT);						
						maxMsgSize = maxValues[MQConstants.MQPER_NOT_PERSISTENT];
						if (maxMsgSize > 0) {
							AtomicInteger maxLen = msgMaxPutMsgSizeMap.get(lookupMaxPutMsgSize + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT);
							if (maxLen == null) {
								msgMaxPutMsgSizeMap.put(lookupMaxPutMsgSize + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT
												, base.meterRegistry.gauge(lookupMaxPutMsgSize, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","false"
												),
										new AtomicInteger(maxMsgSize))
										);
							} else {
								maxLen.set(maxMsgSize);
							}
						}
						
						/*
						 * Max GET PERSISTENT message size
						 */
						maxValues = findMaxValues(pcfAccts, queueName, 
								MQConstants.MQIAMO_GET_MAX_BYTES,
								MQConstants.MQPER_PERSISTENT);						
						maxMsgSize = maxValues[MQConstants.MQPER_PERSISTENT];
						if (maxMsgSize > 0) {
							AtomicInteger maxLen = msgMaxGetMsgSizeMap.get(lookupMaxGetMsgSize + "_" + queueName + MQConstants.MQPER_PERSISTENT);
							if (maxLen == null) {
								msgMaxGetMsgSizeMap.put(lookupMaxGetMsgSize + "_" + queueName + MQConstants.MQPER_PERSISTENT
											, base.meterRegistry.gauge(lookupMaxGetMsgSize, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","true"
												),
										new AtomicInteger(maxMsgSize))
										);
							} else {
								maxLen.set(maxMsgSize);
							}
						}
						
						/*
						 * Max GET NON PERSISTENT message size
						 */
						maxValues = findMaxValues(pcfAccts, queueName, 
								MQConstants.MQIAMO_GET_MAX_BYTES, 
								MQConstants.MQPER_NOT_PERSISTENT);						
						maxMsgSize = maxValues[MQConstants.MQPER_NOT_PERSISTENT];
						if (maxMsgSize > 0) {
							AtomicInteger maxLen = msgMaxGetMsgSizeMap.get(lookupMaxGetMsgSize + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT);
							if (maxLen == null) {
								msgMaxGetMsgSizeMap.put(lookupMaxGetMsgSize + "_" + queueName + MQConstants.MQPER_NOT_PERSISTENT
											, base.meterRegistry.gauge(lookupMaxGetMsgSize, 
										Tags.of("queueManagerName", this.queueManager,
												"queueName", queueName,
												"queueType", queueType,
												"usage",queueUsage,
												"cluster",queueCluster,
												"persistence","false"
												),
										new AtomicInteger(maxMsgSize))
										);
							} else {
								maxLen.set(maxMsgSize);
							}
						}
					}					
				}
				
			} catch (Exception e) {
				if ((base.getDebugLevel() == MQPCFConstants.DEBUG )
						|| (base.getDebugLevel() == MQPCFConstants.TRACE )
						|| (base.getDebugLevel() == MQPCFConstants.WARN )) { log.warn("pcfQueue: unable to get queue metrcis : " + e.getMessage()); }
				
			}
		}
	}

	/*
	 * 
	 */
	private int[] sumPutsAndGets(List<AccountingEntity> pcfAccts, String queueName, int params ) {

		int[] values = {0,0};

		for (AccountingEntity act: pcfAccts) {
			if (act.getType() == MQConstants.MQIAMO_PUTS) {
				if (act.getQueueName().equals(queueName)) {
					int[] v = act.getValues();
					if (params == MQConstants.MQIAMO_PUTS) {
						values[MQConstants.MQPER_NOT_PERSISTENT] += v[MQConstants.MQPER_NOT_PERSISTENT];
						values[MQConstants.MQPER_PERSISTENT] += v[MQConstants.MQPER_PERSISTENT];
					}
				}
			}
			if (act.getType() == MQConstants.MQIAMO_GETS) {
				if (act.getQueueName().equals(queueName)) {
					int[] v = act.getValues();
					if (params == MQConstants.MQIAMO_GETS) {
						values[MQConstants.MQPER_NOT_PERSISTENT] += v[MQConstants.MQPER_NOT_PERSISTENT];
						values[MQConstants.MQPER_PERSISTENT] += v[MQConstants.MQPER_PERSISTENT];
					}
				}
			}
		}
		return values;
	
	}
	
	/*
	 * Find the MAX value, for PERSISTENT and NON_PERSISTENT messages
	 */
	private int[] findMaxValues(List<AccountingEntity> pcfAccts, String queueName, int param, int pers) {
		
		int[] max = {0,0};
		for (AccountingEntity act: pcfAccts) {
			if (act.getType() == param) {
				if (act.getQueueName().equals(queueName)) {
					int[] v = act.getValues();
					if (pers == MQConstants.MQPER_NOT_PERSISTENT) {
						if (v[MQConstants.MQPER_NOT_PERSISTENT] > max[MQConstants.MQPER_NOT_PERSISTENT]) {
							max = v;
						}
					}
					if (pers == MQConstants.MQPER_PERSISTENT) {
						if (v[MQConstants.MQPER_PERSISTENT] > max[MQConstants.MQPER_PERSISTENT]) {
							max = v;
						}
					}
					
				}
			}
			
		}
		
		return max;
	}
	
	/*
	 * Get the handles from each queue
	 */
	private void processQueueHandlers(String queueName, String cluster ) throws MQException, IOException, MQDataException {
		
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

			AtomicInteger proc = procMap.get(lookupQueueProcesses + "_" + appName.trim() + "_" + seq);
			if (proc == null) {
				procMap.put(lookupQueueProcesses + "_" + appName.trim() + "_" + seq, 
						base.meterRegistry.gauge(lookupQueueProcesses, 
						Tags.of("queueManagerName", this.queueManager,
								"cluster",cluster,
								"queueName", queueName.trim(),
								"application", appName.trim(),
								"user", userId,
								"seq",Integer.toString(seq)
								),
						new AtomicInteger(state))
						);
			} else {
				proc.set(state);
			}	
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
	private String getQueueType(int qType) {

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
	public void resetMetrics() {
		if (base.getDebugLevel() == MQPCFConstants.TRACE) { log.trace("pcfQueue: resetting metrics"); }
		deleteMetrics();
	}
	
	/*
	 * Clear the metrics ....
	 * 
	 * Cant put MAX PUT/GET here, as the messages might be deleted
	 * 
	 */
	private void deleteMetrics() {

		base.deleteMetricEntry(lookupQueDepth);
		base.deleteMetricEntry(lookupOpenIn);
		base.deleteMetricEntry(lookupOpenOut);		
		base.deleteMetricEntry(lookupMaxDepth);		
		base.deleteMetricEntry(lookupLastGetDateTime);
		base.deleteMetricEntry(lookupLastPutDateTime);
		base.deleteMetricEntry(lookupOldMsgAge);
		base.deleteMetricEntry(lookupdeQueued);
		base.deleteMetricEntry(lookupenQueued);
		base.deleteMetricEntry(lookupQueueProcesses);
		
	    this.queueMap.clear();
	    this.openInMap.clear();
	    this.openOutMap.clear();
	    this.maxQueMap.clear();
	    this.lastGetMap.clear();
	    this.lastPutMap.clear();
	    this.oldAgeMap.clear();
	    this.deQueMap.clear();
	    this.enQueMap.clear();
	    this.procMap.clear();
		
	}
	
	
}
