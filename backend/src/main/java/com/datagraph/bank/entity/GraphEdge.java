package com.datagraph.bank.entity;

import java.util.Map;

public class GraphEdge {
    private String id;
    private String type;
    private String source;
    private String target;
    private Map<String, Object> properties;

    public GraphEdge() {}

    public GraphEdge(String id, String type, String source, String target, Map<String, Object> properties) {
        this.id = id;
        this.type = type;
        this.source = source;
        this.target = target;
        this.properties = properties;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }
}