package monitor.mq.metrics.mqmetrics;

/*
 * Copyright 2020
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

import monitor.mq.json.controller.JSONController;
import monitor.mq.pcf.channel.pcfChannel;
import monitor.mq.pcf.connections.pcfConnections;
import monitor.mq.pcf.listener.pcfListener;
import monitor.mq.pcf.queue.pcfQueue;
import monitor.mq.pcf.queuemanager.pcfQueueManager;

import com.ibm.mq.headers.pcf.PCFException;

@Component
public class MQConnection {

    private final static Logger log = LoggerFactory.getLogger(MQConnection.class);

	@Value("${ibm.mq.multiInstance:false}")
	private boolean multiInstance;
	public boolean MultiInstance() {
		return this.multiInstance;
	}
	
	@Value("${ibm.mq.queueManager}")
	private String queueManager;
	private String QueueManagerName() {
		return this.queueManager;
	}
		
	@Value("${ibm.mq.keepMetricsWhenQueueManagerIsDown:false}")
	private boolean keepMetricsWhenQueueManagerIsDown;
		
    @Value("${ibm.mq.event.delayInMilliSeconds:10000}")
    private long resetiterations;
    public long ResetIterations() {
    	return resetiterations;
    }
    
    private MQQueueManager queManager = null;
    public MQQueueManager MQQueueManager() {
    	return this.queManager;
    }
    public void MQQueueManager(MQQueueManager v) {
    	this.queManager = v;
    }
    
    private PCFMessageAgent messageAgent = null;
    private PCFMessageAgent MessageAgent() {
    	return this.messageAgent;
    }
    private void MessageAgent(PCFMessageAgent v) {
    	this.messageAgent = v;
    }
    
    private int reasonCode;
    private void ReasonCode(int v) {
    	this.reasonCode = v;
    }
    public int ReasonCode() {
    	return this.reasonCode;
    }
    
    
	@Autowired
	private MQMonitorBase base;

    @Autowired
    public MQMetricsQueueManager mqMetricsQueueManager;
    private MQMetricsQueueManager MQMetricQueueManager() {
    	return this.mqMetricsQueueManager;
    }
	
    @Autowired
    private pcfQueueManager pcfQueueManager;
    public pcfQueueManager QueueManagerObject() {
    	return this.pcfQueueManager;
    }
    @Autowired
    private pcfListener pcfListener;
    private pcfListener ListenerObject() {
    	return this.pcfListener;
    }    
    @Autowired
    private pcfQueue pcfQueue;
    private pcfQueue QueueObject() {
    	return this.pcfQueue;
    }
    @Autowired
    private pcfChannel pcfChannel;
    private pcfChannel ChannelObject() {
    	return this.pcfChannel;
    }
    @Autowired
    private pcfConnections pcfConnections;
    private pcfConnections ConnectionsObject() {
    	return this.pcfConnections;
    }
    
    @Bean
    public JSONController JSONController() {
    	return new JSONController();
    }

    // Constructor
	public MQConnection() {
	}
	
	@PostConstruct
	public void SetProperties() throws MQException, MQDataException, MalformedURLException {
		
		log.info("MQConnection: Object created");
		try {
			log.info("OS : {}", System.getProperty("os.name").trim() );

		} catch (Exception e) {
			// continue
		}
		
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
    public void Scheduler() {
	
		ResetMetricsIterations();

		try {
			if (MessageAgent() != null) {
				Metrics();
				
			} else {
				ConnectToQueueManager();
				
			}
			
		} catch (PCFException p) {
			log.error("PCFException " + p.getMessage());
			log.error("PCFException: ReasonCode " + p.getReason());
			ReasonCode(p.getReason());
			if (log.isTraceEnabled()) { p.printStackTrace(); }
			CloseQMConnection(p.getReason());
			QueueManagerObject().connectionBroken(p.getReason());
			QueueManagerIsNotRunning(p.getReason());
			
		} catch (MQException m) {
			log.error("MQException " + m.getMessage());
			log.error("MQException: ReasonCode " + m.getReason());			
			ReasonCode(m.getReason());
			if (log.isTraceEnabled()) { m.printStackTrace(); }
			if (m.getReason() == MQConstants.MQRC_JSSE_ERROR) {
				log.info("Cause: {}", m.getCause());
			}
			CloseQMConnection(m.getReason());
			QueueManagerObject().connectionBroken(m.getReason());
			QueueManagerIsNotRunning(m.getReason());
			this.messageAgent = null;

		} catch (MQExceptionWrapper w) {
			ReasonCode(9000);
			CloseQMConnection();
			QueueManagerObject().connectionBroken(w.getReason());
			QueueManagerIsNotRunning(w.getReason());
			
		} catch (IOException i) {
			ReasonCode(9001);
			log.error("IOException " + i.getMessage());
			if (log.isTraceEnabled()) { i.printStackTrace(); }
			CloseQMConnection();
			QueueManagerObject().connectionBroken();
			QueueManagerIsNotRunning(MQPCFConstants.PCF_INIT_VALUE);

		} catch (Exception e) {
			ReasonCode(9002);
			log.error("Exception " + e.getMessage());
			if (log.isTraceEnabled()) { e.printStackTrace(); }
			CloseQMConnection();
			QueueManagerObject().connectionBroken();
			QueueManagerIsNotRunning(MQPCFConstants.PCF_INIT_VALUE);
		}
    }
    	
	/*
	 * Set the pcfAgent in each class
	 */
	private void SetPCFParameters() {
		QueueManagerObject().setMessageAgent(MessageAgent());
		
		ListenerObject().setMessageAgent(MessageAgent());
		ListenerObject().setQueueManagerName();
		
		QueueObject().setMessageAgent(MessageAgent());		
		ChannelObject().setMessageAgent(MessageAgent());
		
		ConnectionsObject().setMessageAgent(MessageAgent());
		ConnectionsObject().setQueueManagerName();
		
		
	}

	/*
	 * Connect to the queue manager
	 */
	private void ConnectToQueueManager() throws MQException, MQDataException, MalformedURLException {
		log.warn("No MQ queue manager object");
		
		CreateQueueManagerConnection();
		SetPCFParameters();
		QueueManagerObject().connectionBroken();
	}
	
	/*
	 * Create an MQ connection to the queue manager
	 * ... once connected, create a messageAgent for PCF commands
	 * 
	 */
	public void CreateQueueManagerConnection() throws MQException, MQDataException, MalformedURLException {

		MQQueueManager(MQMetricQueueManager().MultipleQueueManagers());
		MessageAgent(MQMetricQueueManager().CreateMessageAgent(MQQueueManager()));
		
	}
		
	/*
	 * When the queue manager isn't running, send back a status of inactive 
	 */
	private void QueueManagerIsNotRunning(int status) {

		if (QueueManagerObject() != null) {
			QueueManagerObject().notRunning(QueueManagerName(), MultiInstance(), status);
		}

		/*
		 * Clear the metrics, but ...
		 * ... dont clear them if the queue manager is down 
		 */
		if (!keepMetricsWhenQueueManagerIsDown) {
			if (ListenerObject() != null) {
				ListenerObject().ResetMetrics();
			}
			if (ChannelObject() != null) {
				ChannelObject().ResetMetrics();
			}
			//if (getQueueManagerObject() != null) {
			//	getQueueManagerObject().resetMetrics();			
			//}
			if (QueueObject() != null) {
				QueueObject().ResetMetrics();			
			}
		}
	}

	/*
	 * Reset iterations value between capturing performance metrics
	 */
	private void ResetMetricsIterations() {
		QueueManagerObject().ResetMetricsIteration(QueueManagerName());
			
	}
	
	/*
	 * Get metrics
	 */
	public void Metrics() throws PCFException, MQException, 
			IOException, MQDataException, ParseException {
		
		CheckQueueManagerCluster();
		UpdateQMMetrics();
		UpdateListenerMetrics();
		UpdateQueueMetrics();
		UpdateChannelMetrics();
		UpdateConnectionMetrics();
		UpdateMemoryMetrics();
		
		base.setCounter();
		if (base.getCounter() % base.ClearMetrics() == 0) {
			base.setCounter(0);
			log.debug("Resetting metrics reset counter");
		}

	}
	
	/*
	 * Check if the queue manager belongs to a cluster ...
	 */
	private void CheckQueueManagerCluster() {
		QueueManagerObject().checkQueueManagerCluster();
				
	}
	
	/*
	 * Update the queue manager metrics 
	 */
	private void UpdateQMMetrics() throws PCFException, 
		MQException, 
		IOException, 
		MQDataException {

		QueueManagerObject().updateQMMetrics();
		QueueObject().setQueueMonitoringFromQmgr(QueueManagerObject().getQueueMonitoringFromQmgr());		
		
	}

	/*
	 * Update the queue manager listener metrics
	 * 
	 */
	private void UpdateListenerMetrics() throws MQException, 
		IOException, 
		MQDataException {

		ListenerObject().UpdateListenerMetrics();
			
	}
		
	/*
	 * Update the Channel Metrics
	 * 
	 */
	private void UpdateChannelMetrics() throws MQException, IOException, 
		PCFException, 
		MQDataException, 
		ParseException {
		
		ChannelObject().updateChannelMetrics();
		
	}

	/*
	 * Update queue metrics
	 * 
	 */
	private void UpdateQueueMetrics() throws MQException, 
		IOException, 
		MQDataException {

		QueueObject().updateQueueMetrics();
				
	}

	/*
	 * Update the Channel Metrics
	 * 
	 */
	private void UpdateConnectionMetrics() throws MQException, 
		IOException,  
		MQDataException {
		
		ConnectionsObject().updateConnectionsMetrics();
		
	}
	
	private void UpdateMemoryMetrics() {
		
		try {
			this.pcfQueueManager.memoryMetrics();
		} catch (Exception e) {
			//
		}
	}

	
	/*
	 * Disconnect cleanly from the queue manager
	 */
    @PreDestroy
    public void Disconnect() {
    	CloseQMConnection();
    }
    
    /*
     * Disconnect, showing the reason
     */
    public void CloseQMConnection(int reasonCode) {

		log.info("Disconnected from the queue manager"); 
		log.info("Reason code: " + reasonCode);
		MQMetricQueueManager().CloseConnection(MQQueueManager(), MessageAgent());
    	this.queManager = null;
		this.messageAgent = null;
		
    }
	        
    public void CloseQMConnection() {

		log.info("Disconnected from the queue manager"); 
		MQMetricQueueManager().CloseConnection(MQQueueManager(), MessageAgent());
    	this.queManager = null;
		this.messageAgent = null;
		
    }
    
}


