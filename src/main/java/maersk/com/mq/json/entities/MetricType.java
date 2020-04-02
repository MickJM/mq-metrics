package maersk.com.mq.json.entities;

import java.util.List;

/*
 * Metric Type object
 */
public class MetricType {

	public String name;
	public List value;
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public List getValue() {
		return value;
	}
	public void setValue(List value) {
		this.value = value;
	}
	
}
