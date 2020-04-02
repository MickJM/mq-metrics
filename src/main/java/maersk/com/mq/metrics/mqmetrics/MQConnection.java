package maersk.com.mq.metrics.mqmetrics;

/*
 * Copyright 2019
 * Maersk
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
public class MQConnection {

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
	private String getQueueManagerName() {
		return this.queueManager;
	}
		
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
	
    @Value("${ibm.mq.event.delayInMilliSeconds:10000}")
    private long resetIterations;

    private MQQueueManager queManager = null;
    private MQQueueManager getMQQueueManager() {
    	return this.queManager;
    }
    private void setMQQueueManager(MQQueueManager v) {
    	this.queManager = v;
    }
    
    private PCFMessageAgent messageAgent = null;
    private PCFMessageAgent getMessageAgent() {
    	return this.messageAgent;
    }
    private void setMessageAgent(PCFMessageAgent v) {
    	this.messageAgent = v;
    }
    
	@Autowired
	private MQMonitorBase base;
	
    @Autowired
    private pcfQueueManager pcfQueueManager;
    private pcfQueueManager getQueueManagerObject() {
    	return this.pcfQueueManager;
    }
    @Autowired
    private pcfListener pcfListener;
    private pcfListener getListenerObject() {
    	return this.pcfListener;
    }
    @Autowired
    private pcfQueue pcfQueue;
    private pcfQueue getQueueObject() {
    	return this.pcfQueue;
    }
    @Autowired
    private pcfChannel pcfChannel;
    private pcfChannel getChannelObject() {
    	return this.pcfChannel;
    }

    @Autowired
    public MQMetricsQueueManager mqMetricsQueueManager;
    private MQMetricsQueueManager getMQMetricQueueManager() {
    	return this.mqMetricsQueueManager;
    }
    
    public MQMetricSummary metricSummary;
    
    @Bean
    public JSONController JSONController() {
    	return new JSONController();
    }

    // Constructor
	public MQConnection() {
	}
	
	@PostConstruct
	public void setProperties() throws MQException, MQDataException {
		
		if (!(base.getDebugLevel() == MQPCFConstants.NONE)) { log.info("MQConnection: Object created"); }
		//setDebugLevel();
		getChannelObject().loadProperties(this.summaryRequired);
		
		/*
		 * Make a connection to the queue manager
		 */
		connectToQueueManager();
	}
	
	/*
	 * Every 'x' seconds, start the processing to get the MQ metrics
	 * 
	 * Main loop
	 *    if we have a messageAgent object
	 *        call 'getMetrics'
	 *            
	 *    if not
	 *        call 'connectToQueueManager'
	 *    
	 */
	@Scheduled(fixedDelayString="${ibm.mq.event.delayInMilliSeconds}")
    public void scheduler() {
	
		resetIterations();

		try {
			if (getMessageAgent() != null) {
				getMetrics();
				
			} else {
				connectToQueueManager();
				
			}
			
		} catch (PCFException p) {
			if (base.getDebugLevel() == MQPCFConstants.WARN
					|| base.getDebugLevel() == MQPCFConstants.TRACE 
					|| base.getDebugLevel() == MQPCFConstants.ERROR
					|| base.getDebugLevel() == MQPCFConstants.DEBUG) { 
				log.error("PCFException " + p.getMessage());
			}
			if (base.getDebugLevel() == MQPCFConstants.WARN
				|| base.getDebugLevel() == MQPCFConstants.TRACE 
				|| base.getDebugLevel() == MQPCFConstants.ERROR
				|| base.getDebugLevel() == MQPCFConstants.DEBUG) { 
					log.warn("PCFException: ReasonCode " + p.getReason());
			}
			if (base.getDebugLevel() == MQPCFConstants.TRACE) { p.printStackTrace(); }
			closeQMConnection(p.getReason());
			queueManagerIsNotRunning(p.getReason());
			
		} catch (MQException m) {
			if (base.getDebugLevel() == MQPCFConstants.WARN
					|| base.getDebugLevel() == MQPCFConstants.TRACE 
					|| base.getDebugLevel() == MQPCFConstants.ERROR
					|| base.getDebugLevel() == MQPCFConstants.DEBUG) { 
				log.error("MQException " + m.getMessage());
			}
			if (base.getDebugLevel() == base.TRACE) { m.printStackTrace(); }
			closeQMConnection(m.getReason());
			queueManagerIsNotRunning(m.getReason());
			this.messageAgent = null;
			
		} catch (IOException i) {
			if (base.getDebugLevel() == MQPCFConstants.WARN
					|| base.getDebugLevel() == MQPCFConstants.TRACE 
					|| base.getDebugLevel() == MQPCFConstants.ERROR
					|| base.getDebugLevel() == MQPCFConstants.DEBUG) { 
				log.error("IOException " + i.getMessage());
			}
			if (base.getDebugLevel() == MQPCFConstants.TRACE) { i.printStackTrace(); }
			closeQMConnection();
			queueManagerIsNotRunning(MQPCFConstants.PCF_INIT_VALUE);
			
		} catch (Exception e) {
			if (base.getDebugLevel() == MQPCFConstants.WARN
					|| base.getDebugLevel() == MQPCFConstants.TRACE 
					|| base.getDebugLevel() == MQPCFConstants.ERROR
					|| base.getDebugLevel() == MQPCFConstants.DEBUG) { 
				log.error("Exception " + e.getMessage());
			}
			if (base.getDebugLevel() == MQPCFConstants.TRACE) { e.printStackTrace(); }
			closeQMConnection();
			queueManagerIsNotRunning(MQPCFConstants.PCF_INIT_VALUE);
		}
    }
    
	
	/*
	 * Set the pcfAgent in each class
	 */
	private void setPCFParameters() {
		getQueueManagerObject().setMessageAgent(getMessageAgent());
		
		getListenerObject().setMessageAgent(getMessageAgent());
		getListenerObject().setQueueManagerName();
		
		getQueueObject().setMessageAgent(getMessageAgent());		
		getChannelObject().setMessageAgent(getMessageAgent());	
		
	}

	/*
	 * Connect to the queue manager
	 */
	private void connectToQueueManager() throws MQException, MQDataException {
		if (!(base.getDebugLevel() == MQPCFConstants.NONE)) { log.error("No MQ queue manager object"); }

		createQueueManagerConnection();
		setPCFParameters();		
	}
	
	/*
	 * Create an MQ connection to the queue manager
	 * ... once connected, create a messageAgent for PCF commands
	 * 
	 */
	public void createQueueManagerConnection() throws MQException, MQDataException {
		
		setMQQueueManager(getMQMetricQueueManager().createQueueManager());
		setMessageAgent(getMQMetricQueueManager().createMessageAgent(getMQQueueManager()));
	}
		
	/*
	 * When the queue manager isn't running, send back a status of inactive 
	 */
	private void queueManagerIsNotRunning(int status) {

		if (getQueueManagerObject() != null) {
			getQueueManagerObject().notRunning(getQueueManagerName(), isMultiInstance(), status);
		}

		/*
		 * Clear the metrics, but ...
		 * ... dont clear them if the queue manager is down 
		 */
		if (!keepMetricsWhenQueueManagerIsDown) {
			if (getListenerObject() != null) {
				getListenerObject().resetMetrics();
			}
			if (getChannelObject() != null) {
				getChannelObject().resetMetrics();
			}
			if (getQueueManagerObject() != null) {
				getQueueManagerObject().resetMetrics();			
			}
			if (getQueueObject() != null) {
				getQueueObject().resetMetrics();			
			}
		}
	}

	/*
	 * Reset iterations value between capturing performance metrics
	 */
	private void resetIterations() {
		getQueueManagerObject().ResetIteration(getQueueManagerName());
			
	}
	
	/*
	 * Get metrics
	 */
	public void getMetrics() throws PCFException, MQException, 
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
		getQueueManagerObject().checkQueueManagerCluster();
				
	}
	
	/*
	 * Update the queue manager metrics 
	 */
	private void updateQMMetrics() throws PCFException, 
		MQException, 
		IOException, 
		MQDataException {

		getQueueManagerObject().updateQMMetrics();
		getQueueObject().setQueueMonitoringFromQmgr(getQueueManagerObject().getQueueMonitoringFromQmgr());		
		
	}

	/*
	 * Update the queue manager listener metrics
	 * 
	 */
	private void updateListenerMetrics() throws MQException, 
		IOException, 
		MQDataException {

		getListenerObject().UpdateListenerMetrics();
			
	}
		
	/*
	 * Update the Channel Metrics
	 * 
	 */
	private void updateChannelMetrics() throws MQException, IOException, 
		PCFException, 
		MQDataException, 
		ParseException {
		
		getChannelObject().updateChannelMetrics();
		
	}

	/*
	 * Update queue metrics
	 * 
	 */
	private void updateQueueMetrics() throws MQException, 
		IOException, 
		MQDataException {

		getQueueObject().updateQueueMetrics();
				
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
		getMQMetricQueueManager().CloseConnection(getMQQueueManager(), getMessageAgent());
    	this.queManager = null;
		this.messageAgent = null;
		
    }
	        
    public void closeQMConnection() {

		log.info("Disconnected from the queue manager"); 
		getMQMetricQueueManager().CloseConnection(getMQQueueManager(), getMessageAgent());
    	this.queManager = null;
		this.messageAgent = null;
		
    }
    
}


