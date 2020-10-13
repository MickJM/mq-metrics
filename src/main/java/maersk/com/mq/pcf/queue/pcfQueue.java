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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.MQCFGR;
import com.ibm.mq.headers.pcf.MQCFH;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

import io.micrometer.core.instrument.Tags;
import maersk.com.mq.metrics.mqmetrics.MQMetricsQueueManager;
import maersk.com.mq.metrics.mqmetrics.MQMonitorBase;

@Component
public class pcfQueue {

    private final static Logger log = LoggerFactory.getLogger(pcfQueue.class);

	private String queueManager;
	
	@Value("${ibm.mq.objects.queues.exclude}")
    private String[] excludeQueues;
	@Value("${ibm.mq.objects.queues.include}")
    private String[] includeQueues;
	
	/*
	 * 0 - milli seconds
	 * 1 - seconds
	 */
	@Value("${ibm.mq.objects.epoch:0}")
    private int epoch;
	public int Epoch() { return this.epoch; }
	
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
    private Map<String,AtomicLong>oldAgeMap = new HashMap<String,AtomicLong>();    
    private Map<String,AtomicInteger>deQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>enQueMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>procMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>msgMaxPutMsgSizeMap = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>msgMaxGetMsgSizeMap = new HashMap<String,AtomicInteger>();
    
    private Map<String,AtomicLong>msgPutPMsgCountMap = new HashMap<String,AtomicLong>();
    private Map<String,AtomicLong>msgGetPMsgCountMap = new HashMap<String,AtomicLong>();
        
    private String lookupQueDepth = "mq:queueDepth";
    private String lookupOpenIn = "mq:openInputCount";
    private String lookupOpenOut = "mq:openOutputCount";
    private String lookupMaxDepth = "mq:maxQueueDepth";
    private String lookupLastGetDateTime = "mq:lastGetDateTime";
    private String lookupLastPutDateTime = "mq:lastPutDateTime";
    private String lookupOldMsgAge = "mq:oldestMsgAge";
    private String lookupdeQueued = "mq:deQueued";
    private String lookupenQueued = "mq:enQueued";
    private String lookupQueueProcesses = "mq:queueProcesses";
		
    @Value("${ibm.mq.alerts.highqueuedepth.queue:#{null}}")
    private String[] inhibQueues;
    
    @Value("${ibm.mq.alerts.highqueuedepth.value:percentage}")
    private String highDepthValueOrPercentage;
    
    private Map<String, String> inhibMap = new HashMap<String,String>();
    private Map<String, String> whatTheAPISet = new HashMap<String,String>();
    
    private PCFMessageAgent messageAgent;
	public void setMessageAgent(PCFMessageAgent agent) {
    	this.messageAgent = agent;
    	this.queueManager = this.messageAgent.getQManagerName().trim();    	
    }
	private PCFMessageAgent getMessageAgent() {
		return this.messageAgent;
	}
	
	@Value("${info.app.version}")
	private String appversion;	
	public String VersionNumeric() {
		return this.appversion;
	}
	public boolean CompareVersion(String versionToCompare) {
		
		boolean ret = false;
		try {
			int versionA = Integer.parseInt(VersionNumeric().replaceAll("\\.", ""));
			int versionB = Integer.parseInt(versionToCompare.replaceAll("\\.", ""));
			if (versionB >= versionA) {
				ret = true;
				
			} else {
				ret =false;
			}
		} catch (Exception e) {
			ret = false;
		}
		return ret;
	}
	
	@Autowired
	private MQMetricsQueueManager conn;
	public MQMetricsQueueManager getMQConn() {
		return this.conn;
	}
	
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
		log.info("pcfQueue: Object created"); 

    	log.debug("Excluding queues ;");
    	for (String s : this.excludeQueues) {
    		log.debug(s);
    	}
    	
    	/*
    	 * Load the putInhibit queue table
    	 */

    	if (this.inhibQueues != null) {
			for (String s : this.inhibQueues) {
				long count = s.chars().filter(ch -> ch == ':').count();
				if (count == 1) {
					String[] values = s.split(":");			
					this.inhibMap.put(values[0], values[1]);
				
				} else {
					log.warn("Invalid PUT inhibit values for {}, each value must be in the format of 'queueName:value'", s);
					
				}
			}
    	}
    }

    
    /*
     * Get the metrics for each queue that we want
     */
	public void updateQueueMetrics() throws MQException, IOException, MQDataException {
	
		log.debug("pcfQueue: inquire queue request");

		/*
		 * Clear the metrics every 'x' iteration
		 */
		//base.setCounter();
		if (base.getCounter() % base.ClearMetrics() == 0) {
			//base.setCounter(0);
			log.debug("Clearing queue metrics");
			ResetMetrics();
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
			pcfResponse = getMessageAgent().send(pcfRequest);
		} catch (Exception e) {
			log.warn("pcfQueue: no response returned - " + e.getMessage());
			
		}
		log.debug("pcfQueue: inquire queue response");

		/*
		 * For each queue, build the response
		 */
		for (PCFMessage pcfMsg : pcfResponse) {
			String queueName = null;
			try {
				queueName = pcfMsg.getStringParameterValue(MQConstants.MQCA_Q_NAME).trim();
				log.debug("pcfQueue: queue name: " + queueName);

				if (checkQueueNames(queueName)) {
					int qType = pcfMsg.getIntParameterValue(MQConstants.MQIA_Q_TYPE);
					if ((qType != MQConstants.MQQT_LOCAL) && (qType != MQConstants.MQQT_ALIAS)) {
						log.debug("pcfQueue: Queue type is not required : {} for queue {}", qType, queueName);
						throw new Exception("Queue type is not required : " + qType + " for queue " + queueName);
					}
					
					int qUsage = 0;
					int value = 0;
	 				String queueType = getQueueType(qType);
					String queueCluster = "";
					String queueUsage = "";

					log.debug("pcfQueue: inquire queue local");
					if (qType != MQConstants.MQQT_ALIAS) {
						qUsage = pcfMsg.getIntParameterValue(MQConstants.MQIA_USAGE);
						queueUsage = "Normal";
						if (qUsage != MQConstants.MQUS_NORMAL) {
							queueUsage = "Transmission";
						}
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					} else {
						log.debug("pcfQueue: inquire queue alias");
						queueUsage = "Alias";
						queueCluster = pcfMsg.getStringParameterValue(MQConstants.MQCA_CLUSTER_NAME).trim();
	
					}
					log.debug("pcfQueue: inquire queue status");
					PCFMessage pcfInqStat = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
					pcfInqStat.addParameter(MQConstants.MQCA_Q_NAME, queueName);

					/*
					 * Reset Queue status
					 */
					PCFMessage[] pcfResStat = null;
					PCFMessage[] pcfResResp = null;
					if (qType != MQConstants.MQQT_ALIAS) {
						pcfInqStat.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_STATUS);					
						pcfResStat = getMessageAgent().send(pcfInqStat);
						PCFMessage pcfReset = new PCFMessage(MQConstants.MQCMD_RESET_Q_STATS);
						pcfReset.addParameter(MQConstants.MQCA_Q_NAME, queueName);
						pcfResResp = getMessageAgent().send(pcfReset);
						log.debug("pcfQueue: Reset queue status responsev {}", queueName);

					}
					
					/*
					 * Queue depth
					 */
					log.debug("pcfQueue: queue depth");
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
					log.debug("pcfQueue: queue depth: {} : {}", queueName, value);
					
					/*
					 * Open input count
					 */
					log.debug("pcfQueue: inquire queue input count"); 
					int openInvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						openInvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_INPUT_COUNT);
						if (openInvalue > 0) {
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
					}

					/*
					 * Open output count
					 */
					log.debug("pcfQueue: inquire queue output count");
					int openOutvalue = 0;
					if (qType != MQConstants.MQQT_ALIAS) {
						openOutvalue = pcfMsg.getIntParameterValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT);
						if (openOutvalue > 0) {
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
					}

					if ((openInvalue > 0) || (openOutvalue > 0) ) {
						processQueueHandlers(queueName, queueCluster);
					}

					/*
					 * Maximum queue depth
					 */
					log.debug("pcfQueue: inquire queue depth");
					if (qType != MQConstants.MQQT_ALIAS) {
						value = pcfMsg.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH);
						if (value > 0) {
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
					}

					/*
					 * Only interested in local queue, should really get the base queue from the alias queue
					 */
					if (CompareVersion("1.0.0.17")) {
						if (qType == MQConstants.MQQT_LOCAL) {
							CheckQueueInhibit(queueName, qType, value, pcfMsg );
						}
					}
					
					// for dates / time - the queue manager or queue monitoring must be at least 'low'
					// MQMON_Q_MGR     - -3
					// MQMON_NONE	   - -1 Monitoring data collection is turned off for queues, regardless of their QueueMonitor attribute
					// MQMON_NOT_AVAILABLE -1
					// MQMON_OFF 	   -  0 Monitoring data collection is turned off
					// MQMON_DISABLED  -  0
					// MQMON_ENABLED   -  1
					// MQMON_ON        -  1
					// MQMON_LOW	   - 17 Monitoring data collection is turned on, with low ratio of data collection
					// MQMON_MEDIUM	   - 33 Monitoring data collection is turned on, with moderate ratio of data collection
					// MQMON_HIGH	   - 65 Monitoring data collection is turned on, with high ratio of data collection
					boolean req = false;
					// MQMON_Q_QMGR    - 65 This comes through against the queue and not -3 
					if (qType != MQConstants.MQQT_ALIAS) {
						int queueMon = pcfResStat[0].getIntParameterValue(MQConstants.MQIA_MONITORING_Q);
						log.debug("pcfQueue: LASTPUT/LASTGET {} queueMon {} queueManager {}", queueName, queueMon, getQueueMonitoringFromQmgr());
						if (queueMon == MQConstants.MQMON_Q_MGR) {					// This never gets executed, as queueMon, never gets set to -3
																					// This is kept in, just in case

							if (!((getQueueMonitoringFromQmgr() == MQConstants.MQMON_OFF) 
									|| (getQueueMonitoringFromQmgr() == MQConstants.MQMON_NONE))) {
								log.debug("pcfQueue: queuemanager monq {}", req);
								req = true;
							}
						} else {
							if (queueMon != MQConstants.MQMON_OFF) {
								log.debug("pcfQueue: queuemon monq {}", req);
								req = true;
							}
						}
					}
					log.debug("pcfQueue: inquire queue LAST GET DATE : " + queueName);
					if (qType != MQConstants.MQQT_ALIAS) {
						if (req) {
							String lastGetDate = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_DATE);
							String lastGetTime = pcfResStat[0].getStringParameterValue(MQConstants.MQCACF_LAST_GET_TIME);
							if (!(lastGetDate.equals(" ") && lastGetTime.equals(" "))) {
					
								/*
								 *  17/10/2019 - Amended to correctly calculate the epoch value			
								 */
								Date dt = formatter.parse(lastGetDate + " " + lastGetTime);
								long ld = dt.getTime();
								if (Epoch() == 1) {
									ld = ld / 1000;
								}
								
								// Last Get date and time
								AtomicLong getDate = lastGetMap.get(lookupLastGetDateTime + "_" + queueName);
								if (getDate == null) {
									lastGetMap.put(lookupLastGetDateTime + "_" + queueName, 
											base.meterRegistry.gauge(lookupLastGetDateTime, 
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
								log.debug("pcfQueue: inquire queue LAST PUT DATE : " + queueName);
								
								/*
								 *  17/10/2019 - amended to correctly calculate the epoch value				
								 */
								Date dt = formatter.parse(lastPutDate + " " + lastPutTime);
								long ld = dt.getTime();
								if (Epoch() == 1) {
									ld = ld / 1000;
								}
								if (queueName.equals("CARTA")) {
									log.debug("Date = {} {} - EPOCH {}", lastPutDate, lastPutTime, ld);
									
								}
								
								// Last put date and time
								AtomicLong lastDate = lastPutMap.get(lookupLastPutDateTime + "_" + queueName);
								log.debug("pcfQueue: lastDate : " + lastDate);

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
							log.debug("pcfQueue: inquire queue old-age");
							int old = pcfResStat[0].getIntParameterValue(MQConstants.MQIACF_OLDEST_MSG_AGE);
							AtomicLong oldAge = oldAgeMap.get(lookupOldMsgAge + "_" + queueName);
							if (old > 0) {
								if (oldAge == null) {
									oldAgeMap.put(lookupOldMsgAge + "_" + queueName, 
											base.meterRegistry.gauge(lookupOldMsgAge, 
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
					}

					/*
					 *  Messages DeQueued
					 */
					log.debug("pcfQueue: inquire queue de-queued");
					if (qType != MQConstants.MQQT_ALIAS) {
						int devalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_DEQ_COUNT);
						if (devalue > 0) {
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
					}

					/*
					 *  Messages EnQueued
					 */
					log.debug("pcfQueue: inquire queue en-queued");
					if (qType != MQConstants.MQQT_ALIAS) {
						// Messages EnQueued
						int envalue = pcfResResp[0].getIntParameterValue(MQConstants.MQIA_MSG_ENQ_COUNT);
						if (envalue > 0) {
							AtomicInteger enQue = enQueMap.get(lookupenQueued + "_" + queueName);
							if (enQue == null) {
								enQueMap.put(lookupenQueued + "_" + queueName, 
										base.meterRegistry.gauge(lookupenQueued, 
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
					}
				}
				
			} catch (Exception e) {
				log.debug("pcfQueue: unable to get queue metrcis : " + e.getMessage());
				
			}
		}
	}

	/*
	 * Inhibit PUTs on queues
	 */
	private void CheckQueueInhibit(String queueName, int qType, int maxQueueDepth, PCFMessage pcfMsg) {

		if (this.inhibMap.size() == 0) {
			return;
		}
		
		int queueDepth;
		
		try {
			queueDepth = pcfMsg.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
			double perageFull = 0;
			
			if (this.highDepthValueOrPercentage.toUpperCase().equals("PERCENTAGE")) {
				perageFull = ((double)queueDepth / (double)maxQueueDepth) * 100; 		// calculate %age value 
			
			} else {
				perageFull = (double)queueDepth; 										// or use the actual value specified
			}
			
			int putInhinit = pcfMsg.getIntParameterValue(MQConstants.MQIA_INHIBIT_PUT);
			String watermark = this.inhibMap.get(queueName);
			if (watermark == null) {
				watermark = this.inhibMap.get("*");				
			}
			if (watermark != null) {
				int waterm = Integer.parseInt(watermark);
				if (putInhinit == MQConstants.MQQA_PUT_ALLOWED) {

					if (perageFull >= waterm) {
						if (this.highDepthValueOrPercentage.toUpperCase().equals("PERCENTAGE")) {
							log.warn("High queue depth alter is based on percentages");
						} else {
							log.warn("High queue depth alter is based on values");							
						}
						
						log.warn("QUEUE DEPTH HIGH: Queue- {} MaxDepth- {} CURDEPTH- {} WATERMARK is {}",queueName, maxQueueDepth, queueDepth, waterm);
						log.warn("Queue {} is PUT ALLOWED, setting to PUT INHIBITED, threashold is {}",queueName, perageFull);
	
						PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_CHANGE_Q);
						pcfRequest.addParameter(MQConstants.MQCA_Q_NAME, queueName);
						pcfRequest.addParameter(MQConstants.MQIA_Q_TYPE, qType);		
						pcfRequest.addParameter(MQConstants.MQIA_INHIBIT_PUT, MQConstants.MQQA_PUT_INHIBITED);		
						PCFMessage[] pcfResponse = null;
						pcfResponse = getMessageAgent().send(pcfRequest);
						log.debug("pcfQueue (putinhibit): queue {} PUT INHIBIT enabled", queueName);
						this.whatTheAPISet.put(queueName, "set");
						
					}
				}  else {
					if (putInhinit == MQConstants.MQQA_PUT_INHIBITED) {

						if (this.whatTheAPISet.get(queueName) == "set") {
							if (perageFull < waterm) {
							
								if (queueDepth > 0) {
									log.warn("QUEUE DEPTH HIGH: Queue- {} MaxDepth- {} CURDEPTH- {}",queueName, maxQueueDepth, queueDepth);
									log.warn("Queue {} is PUT INHIBITED, setting to PUT ALLOWED",queueName);
		
									PCFMessage pcfRequest = new PCFMessage(MQConstants.MQCMD_CHANGE_Q);
									pcfRequest.addParameter(MQConstants.MQCA_Q_NAME, queueName);
									pcfRequest.addParameter(MQConstants.MQIA_Q_TYPE, qType);		
									pcfRequest.addParameter(MQConstants.MQIA_INHIBIT_PUT, MQConstants.MQQA_PUT_ALLOWED);				
									PCFMessage[] pcfResponse = null;
									pcfResponse = getMessageAgent().send(pcfRequest);
									log.debug("pcfQueue (putinhibit): queue {} PUT ALLOWED enabled", queueName);
								}
							}
							this.whatTheAPISet.remove(queueName);
							
						} else {
							log.info("Queue {} is set as PUT INHIBITED, saving in API",queueName);							
							this.whatTheAPISet.put(queueName, "set");
						}
					}
					
				}
			}
			
		} catch (PCFException e) {
			log.warn("pcfQueue (putinhibit): no response returned - " + e.getMessage());
			
		} catch (MQDataException e) {
			log.warn("pcfQueue (putinhibit): no response returned - " + e.getMessage());

		} catch (IOException e) {
			log.warn("pcfQueue (putinhibit): no response returned - " + e.getMessage());

		}
		
		
		
	}
	
	
	/*
	 * Get the handles from each queue
	 */
	private void processQueueHandlers(String queueName, String cluster ) throws MQException, IOException, MQDataException {
		
		PCFMessage pcfInqHandle = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_STATUS);	
		pcfInqHandle.addParameter(MQConstants.MQCA_Q_NAME, queueName);
		pcfInqHandle.addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_HANDLE);					
		PCFMessage[] pcfResHandle = getMessageAgent().send(pcfInqHandle);

		int openInput = 0;
		int openOutput = 0;
		int v = 0;

		/*
		 * Create an openOutput and openInput 'event' for each MQhandle 
		 * ... MQExplorer shows both input and output handles
		 * Not really a required, but shows no ambiguity
		 */
		for (PCFMessage pcfMsg : pcfResHandle) {

			String appName = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_APPL_TAG).trim();			
			String userId = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_USER_IDENTIFIER).trim();			
			int procId = 
					pcfMsg.getIntParameterValue(MQConstants.MQIACF_PROCESS_ID);

			AtomicInteger openIn = procMap.get(lookupQueueProcesses + "_" + queueName + "_" + procId + "_" + appName.trim() + "_openInput");
			AtomicInteger openOut = procMap.get(lookupQueueProcesses + "_" + queueName + "_" + procId + "_" + appName.trim() + "_openOutput");

			if (openIn != null) {
				openIn.set(0);
			}
			if (openOut != null) {
				openOut.set(0);
			}

		}
		
		/*
		 * For each MQhandle, set increment the count
		 */
		for (PCFMessage pcfMsg : pcfResHandle) {
			
			String conn = pcfMsg.getStringParameterValue(MQConstants.MQCACH_CONNECTION_NAME).trim();
			String appName = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_APPL_TAG).trim();			
			String userId = 
					pcfMsg.getStringParameterValue(MQConstants.MQCACF_USER_IDENTIFIER).trim();			
			int procId = 
					pcfMsg.getIntParameterValue(MQConstants.MQIACF_PROCESS_ID);

			int vin = pcfMsg.getIntParameterValue(MQConstants.MQIACF_OPEN_INQUIRE);		
			int vout = pcfMsg.getIntParameterValue(MQConstants.MQIACF_OPEN_OUTPUT);		
			log.debug("App: {}, Queue: {}, in: {}, out: {}", appName, queueName, vin, vout);

			AtomicInteger openIn = procMap.get(lookupQueueProcesses + "_" + queueName + "_" + procId + "_" + appName.trim() + "_openInput");
			AtomicInteger openOut = procMap.get(lookupQueueProcesses + "_" + queueName + "_" + procId + "_" + appName.trim() + "_openOutput");
	
			//if (vin > 0) {
				if (openIn == null) {
					procMap.put(lookupQueueProcesses + "_" + queueName + "_" + procId + "_" + appName.trim() + "_openInput" , 
							base.meterRegistry.gauge(lookupQueueProcesses, 
							Tags.of("queueManagerName", this.queueManager,
									"cluster",cluster,
									"queueName", queueName.trim(),
									"application", appName.trim(),
									"processId", Integer.toString(procId),
									"user", userId,
									"type","openInput"
						//			"seq",Integer.toString(seq)
									),
							new AtomicInteger(vin))
							);
				} else {
					v = openIn.get() + vin;
					openIn.set(v);
				}	
			//}
			//if (openOutput > 0) {
				if (openOut == null) {
					procMap.put(lookupQueueProcesses + "_" + queueName + "_" + procId + "_" + appName.trim() + "_openOutput" , 
							base.meterRegistry.gauge(lookupQueueProcesses, 
							Tags.of("queueManagerName", this.queueManager,
									"cluster",cluster,
									"queueName", queueName.trim(),
									"application", appName.trim(),
									"processId", Integer.toString(procId),
									"user", userId,
									"type","openOutput"
						//			"seq",Integer.toString(seq)
									),
							new AtomicInteger(vout))
							);
				} else {
					v = openOut.get() + vout;
					openOut.set(v);
				}	
			//}
		} // end of loop			
	}
	
	/*
	 * Check for the queue names
	 */
	private boolean checkQueueNames(String name) {

		if (name.equals(null)) {
			return false;
		}
			
		int inc = includeQueue(name);
		if (inc == 1) {
			return true;
		}
		
		int exc = excludeQueue(name);
		if (exc == 1) {
			return false;
		}
		
		return true;

	}

	private int includeQueue(String name) {
		
		int ret = 0;
		
		// Check queues against the list 
		for (String s : this.includeQueues) {
			if (s.equals("*")) {
				ret = 2;
				break;
			} else {
				if (name.startsWith(s)) {
					ret = 1;
					break;
				}				
			}
		}
		return ret;
	}
	
	private int excludeQueue(String name) {

		int ret = 0;
		
		// Exclude ...
		for (String s : this.excludeQueues) {
			if (s.equals("*")) {
				ret = 2;
				break;
			} else {
				if (name.startsWith(s)) {
					ret = 1;
					break;
				}
			}
		}
		return ret;
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
	public void ResetMetrics() {
		log.debug("pcfQueue: resetting metrics");
		DeleteMetrics();
	}
	
	/*
	 * Clear the metrics ....
	 * 
	 * Cant put MAX PUT/GET here, as the messages might be deleted
	 * 
	 */
	private void DeleteMetrics() {

		base.DeleteMetricEntry(lookupQueDepth);
		base.DeleteMetricEntry(lookupOpenIn);
		base.DeleteMetricEntry(lookupOpenOut);		
		base.DeleteMetricEntry(lookupMaxDepth);		
		base.DeleteMetricEntry(lookupLastGetDateTime);
		base.DeleteMetricEntry(lookupLastPutDateTime);
		base.DeleteMetricEntry(lookupOldMsgAge);
		base.DeleteMetricEntry(lookupdeQueued);
		base.DeleteMetricEntry(lookupenQueued);
		base.DeleteMetricEntry(lookupQueueProcesses);
		
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
