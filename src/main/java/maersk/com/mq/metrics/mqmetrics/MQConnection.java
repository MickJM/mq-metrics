package maersk.com.mq.metrics.mqmetrics;

/*
 * Copyright 2019
 * Mick Moriarty - Maersk
 *
 * Connect to a queue manager
 * 
 * 22/10/2019 - Capture the return code when the queue manager throws an error so multi-instance queue
 *              managers can be checked
 */

import java.io.IOException;
import java.text.ParseException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.headers.pcf.PCFException;

import maersk.com.mq.pcf.queuemanager.pcfQueueManager;
import maersk.com.mq.pcf.listener.pcfListener;
import maersk.com.mq.pcf.queue.pcfQueue;
//import maersk.com.mq.metricsummary.Channels;
//import maersk.com.mq.metricsummary.Channel;
import maersk.com.mq.metricsummary.MQMetricSummary;
import maersk.com.mq.pcf.channel.pcfChannel;
import maersk.com.mq.json.controller.JSONController;

@Component
public class MQConnection extends MQBase {

    static Logger log = Logger.getLogger(MQConnection.class);

	@Value("${application.debug:false}")
    protected boolean _debug;
	
	@Value("${application.debugLevel:NONE}")
	protected String _debugLevel;
    
	@Value("${application.save.metrics.required:false}")
    private boolean summaryRequired;

	@Value("${ibm.mq.multiInstance:false}")
	private boolean multiInstance;
	public boolean isMultiInstance() {
		return this.multiInstance;
	}
	
	@Value("${ibm.mq.queueManager}")
	private String queueManager;
		
	@Value("${ibm.mq.local:false}")
	private boolean local;
	public boolean isRunningLocal() {
		return this.local;
	}
	
	@Value("${ibm.mq.keepMetricsWhenQueueManagerIsDown:false}")
	private boolean keepMetricsWhenQueueManagerIsDown;
	
	//
	@Value("${ibm.mq.useSSL:false}")
	private boolean bUseSSL;
	public boolean usingSSL() {
		return this.bUseSSL;
	}
	
	@Value("${ibm.mq.security.truststore:}")
	private String truststore;
	@Value("${ibm.mq.security.truststore-password:}")
	private String truststorepass;
	@Value("${ibm.mq.security.keystore:}")
	private String keystore;
	@Value("${ibm.mq.security.keystore-password:}")
	private String keystorepass;
	
    //MQ reset
    @Value("${ibm.mq.event.delayInMilliSeconds:10000}")
    private long resetIterations;

    private MQQueueManager queManager = null;
    private PCFMessageAgent messageAgent = null;
    //private PCFAgent agent = null;
    
    // MAP details for the metrics
    //private Map<String,AtomicInteger>runModeMap = new HashMap<String,AtomicInteger>();
	protected static final String runMode = MQPREFIX + "runMode";

    //
    @Autowired
    public pcfQueueManager pcfQueueManager;
    @Autowired
    public pcfListener pcfListener;
    @Autowired
    public pcfQueue pcfQueue;
    @Autowired
    public pcfChannel pcfChannel;

    @Autowired
    public MQMetricsQueueManager mqMetricsQueueManager;
    
    public MQMetricSummary metricSummary;

    @Bean
    public pcfQueueManager QueueManager() {
    	return new pcfQueueManager();
    }
    @Bean
    public pcfListener Listener() {
    	return new pcfListener();
    }
    @Bean
    public pcfQueue Queue() {
    	return new pcfQueue();
    }
    
    @Bean("MetricsSummary")
    public MQMetricSummary metricSummary() {
    	this.metricSummary = new MQMetricSummary();
    	return this.metricSummary;
    }

    @Bean
    @DependsOn("MetricsSummary")
    public pcfChannel Channel() {
    	return new pcfChannel(this.metricSummary);
    }

    @Bean
    public MQMetricsQueueManager CreateMetricsQueueManager() {
    	return new MQMetricsQueueManager();
    }
    
    // 
    @Bean
    public JSONController JSONController() {
    	return new JSONController();
    }
    @Autowired
    private JSONController jsonapi;
    
	// Constructor
	public MQConnection() {
	}
	
	@PostConstruct
	public void setProperties() {
		
		if (!(getDebugLevel() == LEVEL.NONE)) { log.info("MQConnection: Object created"); }
		setDebugLevel();
		this.pcfChannel.loadProperties(this.summaryRequired);
		
	}
	
	/*
	 * Every 'x' seconds, start the processing to get the MQ metrics
	 */
	@Scheduled(fixedDelayString="${ibm.mq.event.delayInMilliSeconds}")
    public void scheduler() {
	
		resetIterations();

		try {
			if (this.messageAgent != null) {
				getMetrics();
				
			} else {
				conectToQueueManager();
				
			}
			
		} catch (PCFException p) {
			if (getDebugLevel() == LEVEL.WARN
					|| getDebugLevel() == LEVEL.TRACE 
					|| getDebugLevel() == LEVEL.ERROR
					|| getDebugLevel() == LEVEL.DEBUG) { 
				log.error("PCFException " + p.getMessage());
			}
			if (getDebugLevel() == LEVEL.WARN
				|| getDebugLevel() == LEVEL.TRACE 
				|| getDebugLevel() == LEVEL.ERROR
				|| getDebugLevel() == LEVEL.DEBUG) { 
					log.warn("PCFException: ReasonCode " + p.getReason());
			}
			if (getDebugLevel() == LEVEL.TRACE) { p.printStackTrace(); }
			closeQMConnection(p.getReason());
			queueManagerIsNotRunning(p.getReason());
			
		} catch (MQException m) {
			if (getDebugLevel() == LEVEL.WARN
					|| getDebugLevel() == LEVEL.TRACE 
					|| getDebugLevel() == LEVEL.ERROR
					|| getDebugLevel() == LEVEL.DEBUG) { 
				log.error("MQException " + m.getMessage());
			}
			if (getDebugLevel() == LEVEL.TRACE) { m.printStackTrace(); }
			closeQMConnection(m.getReason());
			queueManagerIsNotRunning(m.getReason());
			this.messageAgent = null;
			
		} catch (IOException i) {
			if (getDebugLevel() == LEVEL.WARN
					|| getDebugLevel() == LEVEL.TRACE 
					|| getDebugLevel() == LEVEL.ERROR
					|| getDebugLevel() == LEVEL.DEBUG) { 
				log.error("IOException " + i.getMessage());
			}
			if (getDebugLevel() == LEVEL.TRACE) { i.printStackTrace(); }
			closeQMConnection();
			queueManagerIsNotRunning(MQPCFConstants.PCF_INIT_VALUE);
			
		} catch (Exception e) {
			if (getDebugLevel() == LEVEL.WARN
					|| getDebugLevel() == LEVEL.TRACE 
					|| getDebugLevel() == LEVEL.ERROR
					|| getDebugLevel() == LEVEL.DEBUG) { 
				log.error("Exception " + e.getMessage());
			}
			if (getDebugLevel() == LEVEL.TRACE) { e.printStackTrace(); }
			closeQMConnection();
			queueManagerIsNotRunning(MQPCFConstants.PCF_INIT_VALUE);
		}
    }
    
	/*
	 * Set debug level
	 */
	private void setDebugLevel() {
		if (this._debug) {
			if (this._debugLevel.equals("NONE")) {
					this._debugLevel = "DEBUG";
			}
		}
		if (!this._debug) {
			this._debugLevel = "NONE";
		}
		setDebugLevel(this._debugLevel);
		
	}
	
	
	/*
	 * Set the pcfAgent in each class
	 */
	private void setPCFParameters() {
		this.pcfQueueManager.setMessageAgent(this.messageAgent);
		this.pcfQueueManager.setDebugLevel(this._debugLevel);

		this.pcfListener.setMessageAgent(this.messageAgent);
		this.pcfListener.setDebugLevel(this._debugLevel);

		this.pcfQueue.setMessageAgent(this.messageAgent);
		this.pcfQueue.setDebugLevel(this._debugLevel);

		this.pcfChannel.setMessageAgent(this.messageAgent);		
		this.pcfChannel.setDebugLevel(this._debugLevel);
		
		//this.jsonapi.setDebugLevel(this._debugLevel);
		
	}

	/*
	 * Connect to the queue manager
	 */
	private void conectToQueueManager() throws MQException, MQDataException {
		if (!(getDebugLevel() == LEVEL.NONE)) { log.error("No MQ queue manager object"); }

		createQueueManagerConnection();
		setPCFParameters();		
	}
	
	/*
	 * Create an MQ connection to the queue manager
	 * ... once connected, create a messageAgent for PCF commands
	 * 
	 */
	public void createQueueManagerConnection() throws MQException, MQDataException {
		
		this.queManager = this.mqMetricsQueueManager.createQueueManager();
		this.messageAgent = this.mqMetricsQueueManager.createMessageAgent(this.queManager);
	}
		
	/*
	 * When the queue manager isn't running, send back a status of inactive 
	 */
	private void queueManagerIsNotRunning(int status) {

		if (this.pcfQueueManager != null) {
			this.pcfQueueManager.notRunning(this.queueManager, isMultiInstance(), status);
		}

		/*
		 * Clear the metrics, but ...
		 * ... dont clear them if the queue manager is down 
		 */
		if (!keepMetricsWhenQueueManagerIsDown) {
			if (this.pcfListener != null) {
				this.pcfListener.resetMetrics();
			}
			if (this.pcfChannel != null) {
				this.pcfChannel.resetMetrics();
			}
			if (this.pcfChannel != null) {
				this.pcfChannel.resetMetrics();			
			}
			if (this.pcfQueue != null) {
				this.pcfQueue.resetMetrics();			
			}
		}
	}

	/*
	 * Reset iterations value between capturing performance metrics
	 */
	private void resetIterations() {
		
		this.pcfQueueManager.ResetIteration(this.queueManager);
			
	}
	
	/*
	 * Get metrics
	 */
	private void getMetrics() throws PCFException, MQException, 
			IOException, MQDataException, ParseException {
		
		checkQueueManagerCluster();
		updateQMMetrics();
		updateListenerMetrics();
		updateQueueMetrics();
		updateChannelMetrics();
		
	}
	
	/*
	 * Check if the queue manager belongs to a cluster ...
	 */
	private void checkQueueManagerCluster() {

		this.pcfQueueManager.checkQueueManagerCluster();
				
	}
	
	/*
	 * Update the queue manager metrics 
	 */
	private void updateQMMetrics() throws PCFException, 
		MQException, 
		IOException, 
		MQDataException {

		this.pcfQueueManager.updateQMMetrics();
		this.pcfQueue.setQueueMonitoringFromQmgr(this.pcfQueueManager.getQueueMonitoringFromQmgr());		
		
	}

	/*
	 * Update the queue manager listener metrics
	 * 
	 */
	private void updateListenerMetrics() throws MQException, 
		IOException, 
		MQDataException {

		this.pcfListener.UpdateListenerMetrics();
			
	}
		
	/*
	 * Update the Channel Metrics
	 * 
	 */
	private void updateChannelMetrics() throws MQException, IOException, 
		PCFException, 
		MQDataException, 
		ParseException {
		
		this.pcfChannel.updateChannelMetrics();
		
	}

	/*
	 * Update queue metrics
	 * 
	 */
	private void updateQueueMetrics() throws MQException, 
		IOException, 
		MQDataException {

		this.pcfQueue.updateQueueMetrics();
				
	}
	
	/*
	 * Disconnect cleanly from the queue manager
	 */
    @PreDestroy
    public void disconnect() {
    	closeQMConnection();
    }
    
    /*
     * Disconnect, showing the reason
     */
    public void closeQMConnection(int reasonCode) {

		log.info("Disconnected from the queue manager"); 
		log.info("Reason code: " + reasonCode);
    	this.mqMetricsQueueManager.CloseConnection(this.queManager, this.messageAgent);
    	this.queManager = null;
		this.messageAgent = null;
		
    }
	        
    public void closeQMConnection() {

		log.info("Disconnected from the queue manager"); 
    	this.mqMetricsQueueManager.CloseConnection(this.queManager, this.messageAgent);
    	this.queManager = null;
		this.messageAgent = null;
		
    }
    
}


