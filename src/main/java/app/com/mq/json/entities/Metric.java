package app.com.mq.json.entities;

import java.util.List;

/*
 * Metric object
 */
public class Metric {

	public String name;
	public List tags;
	public double value;

	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public List getTags() {
		return tags;
	}
	public void setTags(List tags) {
		this.tags = tags;
	}
	public double getValue() {
		return value;
	}
	public void setValue(double value) {
		this.value = value;
	}
	
}
