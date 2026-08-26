package com.datagraph.bank.entity;

import java.util.Map;

public class GraphNode {
    private String id;
    private String label;
    private Map<String, Object> properties;

    public GraphNode() {}

    public GraphNode(String id, String label, Map<String, Object> properties) {
        this.id = id;
        this.label = label;
        this.properties = properties;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }
}