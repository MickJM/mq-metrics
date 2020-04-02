package maersk.com.mq.metrics.accounting;

/*
 * Accounting PCF entity
 */
public class AccountingEntity {

	private int type;
	private String queueName;
	private int[] values;

	public int getType() {
		return type;
	}
	public void setType(int type) {
		this.type = type;
	}		
	public String getQueueName() {
		return queueName;
	}
	public void setQueueName(String queueName) {
		this.queueName = queueName;
	}
	public int[] getValues() {
		return values;
	}
	public void setValues(int[] values) {
		this.values = values;
	}
}
