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
import java.net.MalformedURLException;
import java.text.ParseException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.MQExceptionWrapper;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.headers.pcf.PCFException;

import maersk.com.mq.pcf.queuemanager.pcfQueueManager;
import maersk.com.mq.pcf.listener.pcfListener;
import maersk.com.mq.pcf.queue.pcfQueue;
import maersk.com.mq.pcf.channel.pcfChannel;
import maersk.com.mq.pcf.connections.pcfConnections;
import maersk.com.mq.json.controller.JSONController;

@Component
public class MQConnection {

    private final static Logger log = LoggerFactory.getLogger(MQConnection.class);

	@Value("${application.debug:false}")
    protected boolean _debug;
	
	@Value("${application.debugLevel:NONE}")
	protected String _debugLevel;
    
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
    public MQQueueManager getMQQueueManager() {
    	return this.queManager;
    }
    public void setMQQueueManager(MQQueueManager v) {
    	this.queManager = v;
    }
    
    private PCFMessageAgent messageAgent = null;
    private PCFMessageAgent getMessageAgent() {
    	return this.messageAgent;
    }
    private void setMessageAgent(PCFMessageAgent v) {
    	this.messageAgent = v;
    }
    
    private int reasonCode;
    private void saveReasonCode(int v) {
    	this.reasonCode = v;
    }
    public int getReasonCode() {
    	return this.reasonCode;
    }
    
    
	@Autowired
	private MQMonitorBase base;
	
    @Autowired
    private pcfQueueManager pcfQueueManager;
    public pcfQueueManager getQueueManagerObject() {
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
    private pcfConnections pcfConnections;
    private pcfConnections getConnectionsObject() {
    	return this.pcfConnections;
    }

    @Autowired
    public MQMetricsQueueManager mqMetricsQueueManager;
    private MQMetricsQueueManager getMQMetricQueueManager() {
    	return this.mqMetricsQueueManager;
    }
    
    //public MQMetricSummary metricSummary;
    
    @Bean
    public JSONController JSONController() {
    	return new JSONController();
    }

    // Constructor
	public MQConnection() {
	}
	
	@PostConstruct
	public void setProperties() throws MQException, MQDataException, MalformedURLException {
		
		log.info("MQConnection: Object created");
		log.info("OS : {}", System.getProperty("os.name").trim() );
		log.info("PID: {}", ProcessHandle.current().pid() );
		
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
			log.error("PCFException " + p.getMessage());
			log.error("PCFException: ReasonCode " + p.getReason());
			saveReasonCode(p.getReason());
			if (log.isTraceEnabled()) { p.printStackTrace(); }
			closeQMConnection(p.getReason());
			getQueueManagerObject().connectionBroken(p.getReason());
			queueManagerIsNotRunning(p.getReason());
			
		} catch (MQException m) {
			log.error("MQException " + m.getMessage());
			log.error("MQException: ReasonCode " + m.getReason());			
			saveReasonCode(m.getReason());
			if (log.isTraceEnabled()) { m.printStackTrace(); }
			if (m.getReason() == MQConstants.MQRC_JSSE_ERROR) {
				log.info("Cause: {}", m.getCause());
			}
			closeQMConnection(m.getReason());
			getQueueManagerObject().connectionBroken(m.getReason());
			queueManagerIsNotRunning(m.getReason());
			this.messageAgent = null;

		} catch (MQExceptionWrapper w) {
			saveReasonCode(9000);
			closeQMConnection();
			getQueueManagerObject().connectionBroken(w.getReason());
			queueManagerIsNotRunning(w.getReason());
			
		} catch (IOException i) {
			saveReasonCode(9001);
			log.error("IOException " + i.getMessage());
			if (log.isTraceEnabled()) { i.printStackTrace(); }
			closeQMConnection();
			getQueueManagerObject().connectionBroken();
			queueManagerIsNotRunning(MQPCFConstants.PCF_INIT_VALUE);

		} catch (Exception e) {
			saveReasonCode(9002);
			log.error("Exception " + e.getMessage());
			if (log.isTraceEnabled()) { e.printStackTrace(); }
			closeQMConnection();
			getQueueManagerObject().connectionBroken();
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
		
		getConnectionsObject().setMessageAgent(getMessageAgent());
		getConnectionsObject().setQueueManagerName();
		
		
	}

	/*
	 * Connect to the queue manager
	 */
	private void connectToQueueManager() throws MQException, MQDataException, MalformedURLException {
		log.warn("No MQ queue manager object");

		
		createQueueManagerConnection();
		setPCFParameters();
		getQueueManagerObject().connectionBroken();
	}
	
	/*
	 * Create an MQ connection to the queue manager
	 * ... once connected, create a messageAgent for PCF commands
	 * 
	 */
	public void createQueueManagerConnection() throws MQException, MQDataException, MalformedURLException {

		//setMQQueueManager(getMQMetricQueueManager().createQueueManager());

		setMQQueueManager(getMQMetricQueueManager().multipleQueueManagers());
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
			//if (getQueueManagerObject() != null) {
			//	getQueueManagerObject().resetMetrics();			
			//}
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
		updateConnectionMetrics();
		
		base.setCounter();
		if (base.getCounter() % base.getClearMetrics() == 0) {
			base.setCounter(0);
			log.debug("Resetting metrics reset counter");
		}

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
	 * Update the Channel Metrics
	 * 
	 */
	private void updateConnectionMetrics() throws MQException, 
		IOException,  
		MQDataException {
		
		getConnectionsObject().updateConnectionsMetrics();
		
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


