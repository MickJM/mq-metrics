package maersk.com.mq.pcfConnections;

import com.ibm.mq.headers.pcf.PCFMessage;

public class ConnectionsObject {

	private String label;
	private int count;
	private PCFMessage pcfMsg;
	
	
	public String getLabel() {
		return label;
	}
	public void setLabel(String label) {
		this.label = label;
	}
	public int getCount() {
		return count;
	}
	public void setCount(int count) {
		this.count = count;
	}
	public PCFMessage getPcfMsg() {
		return pcfMsg;
	}
	public void setPcfMsg(PCFMessage pcfMsg) {
		this.pcfMsg = pcfMsg;
	}
	
}
